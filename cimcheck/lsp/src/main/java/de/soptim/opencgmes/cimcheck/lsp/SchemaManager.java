/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimcheck.lsp;

import de.soptim.opencgmes.cimcheck.core.CgmesSchemaLoader;
import de.soptim.opencgmes.cimcheck.core.DefaultPrefixes;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.schema.EndpointSchema;
import de.soptim.opencgmes.cimcheck.core.schema.EndpointSchemaLoader;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.lsp.config.ConfigLoader;
import de.soptim.opencgmes.cimcheck.lsp.config.LspConfig;
import de.soptim.opencgmes.cimcheck.lsp.schema.SchemaLoader;
import org.apache.jena.graph.Node;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages schema loading on a background thread.
 *
 * <p>Callers register {@code onLoaded} callbacks that fire each time a schema load (or reload)
 * succeeds — typically to re-trigger validation on all open documents.</p>
 */
final class SchemaManager {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaManager.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schema-loader");
        t.setDaemon(true);
        return t;
    });

    /**
     * Separate pool for remote endpoint fetches. A remote load is a SELECT plus a CONSTRUCT per
     * profile graph, each with a {@link #REMOTE_TIMEOUT} timeout, so a slow or unreachable endpoint
     * can take a long time. Keeping it off the single {@link #executor} ensures a config reload
     * (opencgmes.json edit) and other endpoint loads stay responsive instead of queueing behind it.
     */
    private final ExecutorService endpointExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "schema-endpoint-loader-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * The <em>primary</em> workspace schema — the one discovered from the workspace root (or the
     * bundled default). It backs the workspace-global operations that have no document context
     * (workspace symbols, the explain command) and the endpoint-schema builds.
     */
    private final AtomicReference<SparqlValidationApi>              apiRef       = new AtomicReference<>();
    private final AtomicReference<StrictnessLevel>                  levelRef     = new AtomicReference<>(StrictnessLevel.DEFAULT);
    private final AtomicReference<DefinitionIndex>                  defRef       = new AtomicReference<>();
    private final AtomicReference<Map<Node, Collection<VersionIri>>> namedGraphRef = new AtomicReference<>(Map.of());
    private final AtomicBoolean checkStdVocabRef = new AtomicBoolean(true);
    private final List<Runnable> onLoadedCallbacks = new CopyOnWriteArrayList<>();

    /** Cache key (in {@link #workspaceSchemaCache} and {@link #primaryConfigKey}) for the bundled default. */
    private static final String BUNDLED = "<bundled>";

    /**
     * Per-config-source schema cache for git-style nearest-config resolution: a document is
     * validated against the nearest {@code opencgmes.json} above it, falling back to the bundled
     * default. Keyed by resolved config-file path (or {@link #BUNDLED}). The primary config's entry
     * is served from the {@code *Ref} fields above rather than this map; only <em>other</em> configs
     * found below the root land here. Cleared on every reload.
     */
    private final Map<String, WorkspaceSchema> workspaceSchemaCache = new ConcurrentHashMap<>();

    /** Cache key of the primary (workspace-root) config, set on each load. */
    private volatile String primaryConfigKey;

    /** Per-query timeout for fetching a schema from a remote SPARQL endpoint. */
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(30);

    /** How long a failed endpoint load is negatively cached before a retry is allowed. */
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);

    /** Schemas loaded from a {@code # [endpoint=...]} directive, keyed by resolved source. */
    private final Map<String, ResolvedSchema> endpointCache = new ConcurrentHashMap<>();
    /**
     * Endpoint sources whose load failed, mapped to the {@link System#nanoTime()} after which a
     * retry is allowed. A negative cache avoids re-fetching every keystroke, but it expires after
     * {@link #FAILURE_TTL} so a transient outage doesn't disable the cell for the whole session.
     */
    private final Map<String, Long> failedEndpoints = new ConcurrentHashMap<>();
    /** Remote endpoints whose async load is in progress, so keystrokes don't resubmit it. */
    private final Set<String> inFlightEndpoints = ConcurrentHashMap.newKeySet();

    private volatile Path workspaceRoot;
    private final AtomicReference<LanguageClient> client = new AtomicReference<>();

    // ---- API -------------------------------------------------------------------------------

    void setClient(LanguageClient client) {
        this.client.set(client);
    }

    /** Registers a callback invoked (on the schema-loader thread) after each successful load. */
    void addOnLoadedCallback(Runnable callback) {
        onLoadedCallbacks.add(callback);
    }

    /** Starts an asynchronous schema load from the given workspace root. */
    void loadAsync(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        executor.submit(() -> loadSync(workspaceRoot));
    }

    /** Triggers a reload using the previously-set workspace root. */
    void reloadAsync() {
        Path root = workspaceRoot;
        if (root != null) {
            executor.submit(() -> loadSync(root));
        }
    }

    /** Returns the loaded API, or empty if no schema has been successfully loaded yet. */
    Optional<SparqlValidationApi> getApi() {
        return Optional.ofNullable(apiRef.get());
    }

    /** Returns the strictness level from the last successfully loaded config. */
    StrictnessLevel strictnessLevel() {
        return levelRef.get();
    }

    /** Returns the definition index, or empty if the schema has not been loaded yet. */
    Optional<DefinitionIndex> getDefinitionIndex() {
        return Optional.ofNullable(defRef.get());
    }

    /**
     * Returns the per-graph profile scope derived from {@code namedGraphs} in the config, or an
     * empty map when no mapping is configured (in which case validation uses all profiles).
     */
    Map<Node, Collection<VersionIri>> namedGraphScope() {
        return namedGraphRef.get();
    }

    /**
     * Resolves the schema a document should be validated against.
     *
     * <p>When {@code endpoint} is {@code null}/blank, the document's workspace schema is used: the
     * nearest {@code opencgmes.json} above the document, or the bundled CGMES 3.0 default when none
     * is found. Otherwise the schema is loaded from the endpoint — a local
     * {@code .ttl}/{@code .rdf}/{@code .owl} file or a remote SPARQL endpoint — and cached by
     * resolved source.</p>
     *
     * @param endpoint the {@code # [endpoint=...]} value, or {@code null} for the workspace schema
     * @param docDir   the document's own directory, used for config discovery and relative endpoints
     */
    Optional<ResolvedSchema> resolveSchema(String endpoint, Path docDir) {
        if (endpoint == null || endpoint.isBlank()) {
            return workspaceSchemaFor(docDir).map(WorkspaceSchema::toResolvedSchema);
        }
        return isRemote(endpoint)
                ? resolveRemote(endpoint)
                : resolveLocal(resolveLocalEndpoint(endpoint, docDir));
    }

    /**
     * Resolves the {@link WorkspaceSchema} for a document directory using git-style nearest-config
     * discovery: the nearest {@code opencgmes.json} at or above {@code docDir} wins, else the
     * bundled default. The primary (workspace-root) config is served from the cached primary
     * schema; other configs are built lazily and cached per config path.
     *
     * @param docDir the document's directory, or {@code null} to use the primary workspace schema
     * @return the resolved schema, or empty if it has not loaded yet or failed to load
     */
    Optional<WorkspaceSchema> workspaceSchemaFor(Path docDir) {
        String key = (docDir == null)
                ? primaryConfigKey
                : ConfigLoader.discoverFile(docDir).map(Path::toString).orElse(BUNDLED);
        if (key == null) return Optional.empty();  // not initialised yet
        if (key.equals(primaryConfigKey)) {
            return primarySchema();
        }
        WorkspaceSchema ws = workspaceSchemaCache.computeIfAbsent(key, this::buildForKey);
        return ws.api() == null ? Optional.empty() : Optional.of(ws);
    }

    /** Synthesizes the primary workspace schema from the {@code *Ref} fields, or empty if unloaded. */
    private Optional<WorkspaceSchema> primarySchema() {
        SparqlValidationApi api = apiRef.get();
        if (api == null) return Optional.empty();
        return Optional.of(new WorkspaceSchema(
                api, levelRef.get(), defRef.get(), namedGraphRef.get(), checkStdVocabRef.get()));
    }

    /** Builds (and notifies on failure) the schema for a non-primary config key. */
    private WorkspaceSchema buildForKey(String key) {
        try {
            Optional<Path> configFile = BUNDLED.equals(key) ? Optional.empty() : Optional.of(Path.of(key));
            return buildSchemaForConfig(configFile);
        } catch (Exception e) {
            LOG.error("Failed to load schema for {}: {}", key, e.getMessage(), e);
            notify(MessageType.Error, "CIMcheck: schema load failed for " + key + " — " + e.getMessage());
            return new WorkspaceSchema(null, StrictnessLevel.DEFAULT, null, Map.of(), true);
        }
    }

    private static boolean isRemote(String endpoint) {
        return endpoint.startsWith("http://") || endpoint.startsWith("https://");
    }

    private Path resolveLocalEndpoint(String endpoint, Path docDir) {
        Path p = Path.of(endpoint);
        if (p.isAbsolute()) return p.normalize();
        Path base = docDir != null ? docDir : workspaceRoot;
        return base != null ? base.resolve(endpoint).normalize() : p.normalize();
    }

    /** Loads a schema from a local file synchronously (fast); caches success and failure. */
    private Optional<ResolvedSchema> resolveLocal(Path file) {
        String key = file.toString();
        ResolvedSchema cached = endpointCache.get(key);
        if (cached != null) return Optional.of(cached);
        if (isFailed(key)) return Optional.empty();
        try {
            if (!Files.isRegularFile(file)) {
                fail(key, MessageType.Warning, "CIMcheck: endpoint schema file not found: " + file);
                return Optional.empty();
            }
            var index    = CgmesSchemaLoader.fromFiles(List.of(file)).loadIndex();
            ResolvedSchema schema = buildSchema(index, Map.of());
            endpointCache.put(key, schema);
            LOG.info("Loaded endpoint schema from file {}", file);
            return Optional.of(schema);
        } catch (Exception e) {
            fail(key, MessageType.Error,
                    "CIMcheck: failed to load schema from " + file + " — " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves a schema hosted on a remote SPARQL endpoint. The fetch (one enumeration query plus
     * a CONSTRUCT per profile graph) runs on the dedicated {@link #endpointExecutor} so neither the
     * validator thread nor workspace reloads block on it; open documents are revalidated once the
     * schema lands. Returns empty until then.
     */
    private Optional<ResolvedSchema> resolveRemote(String endpoint) {
        ResolvedSchema cached = endpointCache.get(endpoint);
        if (cached != null) return Optional.of(cached);
        if (isFailed(endpoint)) return Optional.empty();
        if (inFlightEndpoints.add(endpoint)) {
            notify(MessageType.Info, "CIMcheck: loading schema from endpoint " + endpoint + " …");
            endpointExecutor.submit(() -> loadRemoteEndpoint(endpoint));
        }
        return Optional.empty();
    }

    private void loadRemoteEndpoint(String endpoint) {
        try {
            EndpointSchema es = EndpointSchemaLoader.loadFromEndpoint(endpoint, REMOTE_TIMEOUT);
            if (!es.hasSchema()) {
                // Reachable, but no CIM schema graphs to validate against. Negatively cache it so we
                // don't re-fetch on every keystroke; the document falls back to syntax-only checking.
                markFailed(endpoint);
                notify(MessageType.Warning, "CIMcheck: endpoint " + endpoint
                        + " exposes no CIM schema graphs — validating SPARQL syntax only.");
                return;
            }
            ResolvedSchema schema = buildSchema(es.index(), es.namedGraphScope());
            endpointCache.put(endpoint, schema);
            LOG.info("Loaded schema from endpoint {} ({} instance graph(s) auto-mapped, {} unmatched, {} schema graph(s))",
                    endpoint, es.instanceGraphsMapped(), es.unmatchedGraphs().size(), es.schemaGraphNames().size());
            notify(MessageType.Info, "CIMcheck: schema loaded from endpoint " + endpoint
                    + " — " + es.instanceGraphsMapped() + " instance graph(s) auto-mapped to profiles, "
                    + es.schemaGraphNames().size() + " schema graph(s) detected.");
            if (!es.unmatchedGraphs().isEmpty()) {
                notify(MessageType.Warning, "CIMcheck: could not auto-detect a CGMES profile for "
                        + es.unmatchedGraphs().size() + " named graph(s); terms in "
                        + (es.unmatchedGraphs().size() == 1 ? "it" : "them")
                        + " will be reported as unknown. Graph(s): " + describeGraphs(es.unmatchedGraphs()));
            }
            fireOnLoaded();  // revalidate open documents so the cell gets its diagnostics
        } catch (Exception e) {
            LOG.error("Failed to load schema from endpoint {}: {}", endpoint, e.getMessage(), e);
            markFailed(endpoint);
            notify(MessageType.Error,
                    "CIMcheck: failed to load schema from endpoint " + endpoint + " — " + e.getMessage());
        } finally {
            inFlightEndpoints.remove(endpoint);
        }
    }

    /** Renders up to a few graph names for a warning message, eliding the rest. */
    private static String describeGraphs(List<Node> graphs) {
        int shown = Math.min(graphs.size(), 3);
        var sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append('<').append(graphs.get(i).getURI()).append('>');
        }
        if (graphs.size() > shown) sb.append(", … (").append(graphs.size() - shown).append(" more)");
        return sb.toString();
    }

    /** Builds a {@link ResolvedSchema} from an index, using default prefixes and the given scope. */
    private ResolvedSchema buildSchema(RdfsSchemaIndex index, Map<Node, Collection<VersionIri>> scope) {
        var prefixes = DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, index);
        var api      = new SparqlValidationApi(index, prefixes, checkStdVocabRef.get());
        return new ResolvedSchema(api, levelRef.get(), scope);
    }

    /** Records an endpoint as failed (negative cache) and notifies once per failure window. */
    private void fail(String key, MessageType type, String message) {
        if (markFailed(key)) notify(type, message);
    }

    /**
     * Records {@code key} as failed until {@link #FAILURE_TTL} elapses.
     *
     * @return {@code true} if this opens a fresh failure window (no live entry was present), so the
     *         caller should notify; {@code false} if a still-valid failure was already recorded.
     */
    private boolean markFailed(String key) {
        long expiry = System.nanoTime() + FAILURE_TTL.toNanos();
        Long prev = failedEndpoints.put(key, expiry);
        return prev == null || prev - System.nanoTime() <= 0;
    }

    /**
     * Returns whether {@code key}'s last failure is still within {@link #FAILURE_TTL}. An expired
     * entry is evicted so the next {@code resolveSchema} retries the load.
     */
    private boolean isFailed(String key) {
        Long expiry = failedEndpoints.get(key);
        if (expiry == null) return false;
        if (expiry - System.nanoTime() > 0) return true;
        failedEndpoints.remove(key, expiry);
        return false;
    }

    void shutdown() {
        executor.shutdown();
        endpointExecutor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
            if (!endpointExecutor.awaitTermination(2, TimeUnit.SECONDS)) endpointExecutor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            endpointExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- Private ---------------------------------------------------------------------------

    private void loadSync(Path root) {
        // Endpoint schemas and per-config schemas are cached for the session; drop them on reload so
        // a strictness change propagates and any transient load failures get retried.
        endpointCache.clear();
        failedEndpoints.clear();
        workspaceSchemaCache.clear();

        Optional<Path> configFile = ConfigLoader.discoverFile(root);
        primaryConfigKey = configFile.map(Path::toString).orElse(BUNDLED);
        try {
            WorkspaceSchema primary = buildSchemaForConfig(configFile);
            apiRef.set(primary.api());
            levelRef.set(primary.level());
            defRef.set(primary.definitionIndex());
            namedGraphRef.set(primary.namedGraphScope());
            checkStdVocabRef.set(primary.checkStandardVocab());

            if (configFile.isEmpty()) {
                LOG.info("No opencgmes.json found under {} — using bundled CGMES 3.0 schemas", root);
                notify(MessageType.Info,
                        "CIMcheck: no opencgmes.json found — validating against the bundled CGMES 3.0 schemas.");
            } else {
                LOG.info("Schema loaded successfully from {} (strictness: {})", configFile.get(), primary.level());
                notify(MessageType.Info, "CIMcheck: schema loaded successfully.");
            }
            fireOnLoaded();
        } catch (Exception e) {
            LOG.error("Failed to load schema: {}", e.getMessage(), e);
            notify(MessageType.Error, "CIMcheck: schema load failed — " + e.getMessage());
            apiRef.set(null);
            defRef.set(null);
            namedGraphRef.set(Map.of());
        }
    }

    /**
     * Builds a {@link WorkspaceSchema} from a discovered config file, or from the bundled CGMES 3.0
     * profiles when {@code configFile} is empty. Config-relative schema paths resolve against the
     * config file's own directory.
     */
    private WorkspaceSchema buildSchemaForConfig(Optional<Path> configFile) throws Exception {
        if (configFile.isEmpty()) {
            return assemble(emptyConfig(), SchemaLoader.loadBundledWithSources());
        }
        Path base = configFile.get().toAbsolutePath().getParent();
        LspConfig config = ConfigLoader.load(configFile.get());
        return assemble(config, SchemaLoader.loadWithSources(config, base));
    }

    /** Assembles the API, strictness, definition index, and named-graph scope from a config + schema. */
    private WorkspaceSchema assemble(LspConfig config, SchemaLoader.SchemaAndSources loaded) {
        var prefixes = config.prefixes() != null
                ? config.prefixes()
                : DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, loaded.index());
        boolean checkStd = config.checkStandardVocabulary();
        var api   = new SparqlValidationApi(loaded.index(), prefixes, checkStd);
        var level = parseLevel(config);
        var defIndex = DefinitionIndex.build(loaded.index(), loaded.sourcePaths());
        var scope = SparqlValidationApi.buildNamedGraphScope(
                config.namedGraphs(), loaded.index(), msg -> LOG.warn("{}", msg));
        if (!loaded.skippedFiles().isEmpty()) {
            notify(MessageType.Warning,
                    "CIMcheck: schema loaded with warnings — " + loaded.skippedFiles().size()
                    + " file(s) could not be parsed and were skipped:\n"
                    + String.join("\n", loaded.skippedFiles()));
        }
        return new WorkspaceSchema(api, level, defIndex, scope, checkStd);
    }

    private static LspConfig emptyConfig() {
        return new LspConfig(null, null, null, null, null, null);
    }

    /** Runs every registered on-loaded callback (typically: revalidate all open documents). */
    private void fireOnLoaded() {
        for (Runnable cb : onLoadedCallbacks) {
            try {
                cb.run();
            } catch (Exception cbEx) {
                LOG.error("On-loaded callback failed: {}", cbEx.getMessage(), cbEx);
            }
        }
    }

    private static StrictnessLevel parseLevel(LspConfig config) {
        try {
            return StrictnessLevel.parse(config.strictness());
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid strictness '{}' in config, using DEFAULT: {}", config.strictness(), e.getMessage());
            return StrictnessLevel.DEFAULT;
        }
    }

    private void notify(MessageType type, String message) {
        LanguageClient c = client.get();
        if (c != null) c.showMessage(new MessageParams(type, message));
    }
}
