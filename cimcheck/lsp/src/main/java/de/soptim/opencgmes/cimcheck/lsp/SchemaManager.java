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
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.lsp.config.ConfigLoader;
import de.soptim.opencgmes.cimcheck.lsp.config.LspConfig;
import de.soptim.opencgmes.cimcheck.lsp.schema.EndpointGraphFetcher;
import de.soptim.opencgmes.cimcheck.lsp.schema.SchemaLoader;
import org.apache.jena.graph.Graph;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final AtomicReference<SparqlValidationApi>              apiRef       = new AtomicReference<>();
    private final AtomicReference<StrictnessLevel>                  levelRef     = new AtomicReference<>(StrictnessLevel.DEFAULT);
    private final AtomicReference<DefinitionIndex>                  defRef       = new AtomicReference<>();
    private final AtomicReference<Map<Node, Collection<VersionIri>>> namedGraphRef = new AtomicReference<>(Map.of());
    private final AtomicBoolean checkStdVocabRef = new AtomicBoolean(true);
    private final List<Runnable> onLoadedCallbacks = new CopyOnWriteArrayList<>();

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
     * <p>When {@code endpoint} is {@code null}/blank, the default workspace schema (from
     * {@code .cgmes/validation.json}) is used. Otherwise the schema is loaded from the endpoint —
     * a local {@code .ttl}/{@code .rdf}/{@code .owl} file for now — and cached by resolved source.
     * Remote SPARQL endpoints are recognised but not yet loaded (handled in a later stage).</p>
     *
     * @param endpoint the {@code # [endpoint=...]} value, or {@code null} for the default schema
     * @param docDir   the document's own directory, used to resolve relative endpoint paths
     */
    Optional<ResolvedSchema> resolveSchema(String endpoint, Path docDir) {
        if (endpoint == null || endpoint.isBlank()) {
            SparqlValidationApi api = apiRef.get();
            return api == null
                    ? Optional.empty()
                    : Optional.of(new ResolvedSchema(api, levelRef.get(), namedGraphRef.get()));
        }
        return isRemote(endpoint)
                ? resolveRemote(endpoint)
                : resolveLocal(resolveLocalEndpoint(endpoint, docDir));
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
            ResolvedSchema schema = buildSchema(index);
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
     * a CONSTRUCT per profile graph) runs on the background loader thread so the validator thread
     * never blocks; open documents are revalidated once the schema lands. Returns empty until then.
     */
    private Optional<ResolvedSchema> resolveRemote(String endpoint) {
        ResolvedSchema cached = endpointCache.get(endpoint);
        if (cached != null) return Optional.of(cached);
        if (isFailed(endpoint)) return Optional.empty();
        if (inFlightEndpoints.add(endpoint)) {
            notify(MessageType.Info, "CIMcheck: loading schema from endpoint " + endpoint + " …");
            executor.submit(() -> loadRemoteEndpoint(endpoint));
        }
        return Optional.empty();
    }

    private void loadRemoteEndpoint(String endpoint) {
        try {
            List<Graph> graphs = EndpointGraphFetcher.fetchProfileGraphs(endpoint, REMOTE_TIMEOUT);
            ResolvedSchema schema = buildSchema(CgmesSchemaLoader.indexFromGraphs(graphs));
            endpointCache.put(endpoint, schema);
            LOG.info("Loaded schema from endpoint {}", endpoint);
            notify(MessageType.Info, "CIMcheck: schema loaded from endpoint " + endpoint + ".");
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

    /** Builds a {@link ResolvedSchema} from an index, using default prefixes and full-profile scope. */
    private ResolvedSchema buildSchema(RdfsSchemaIndex index) {
        var prefixes = DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, index);
        var api      = new SparqlValidationApi(index, prefixes, checkStdVocabRef.get());
        return new ResolvedSchema(api, levelRef.get(), Map.of());
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
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- Private ---------------------------------------------------------------------------

    private void loadSync(Path root) {
        // Endpoint schemas are cached for the session; drop them on reload so a strictness
        // change in the config propagates and any transient load failures get retried.
        endpointCache.clear();
        failedEndpoints.clear();
        try {
            var discovered = ConfigLoader.discover(root);
            if (discovered.isEmpty()) {
                LOG.warn("No .cgmes/validation.json found under {}", root);
                notify(MessageType.Warning,
                        "SPARQL Validation: No .cgmes/validation.json found — " +
                        "validation is disabled until a config is added.");
                apiRef.set(null);
                defRef.set(null);
                namedGraphRef.set(Map.of());
                return;
            }
            LspConfig config = discovered.get();
            var loaded = SchemaLoader.loadWithSources(config, root);
            var effectivePrefixes = config.prefixes() != null
                    ? config.prefixes()
                    : DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, loaded.index());
            checkStdVocabRef.set(config.checkStandardVocabulary());
            apiRef.set(new SparqlValidationApi(loaded.index(), effectivePrefixes, config.checkStandardVocabulary()));
            levelRef.set(parseLevel(config));
            defRef.set(DefinitionIndex.build(loaded.index(), loaded.sourcePaths()));
            namedGraphRef.set(SparqlValidationApi.buildNamedGraphScope(
                    config.namedGraphs(), loaded.index(), msg -> LOG.warn("{}", msg)));
            LOG.info("Schema loaded successfully from {} (strictness: {})", root, levelRef.get());
            if (!loaded.skippedFiles().isEmpty()) {
                String detail = String.join("\n", loaded.skippedFiles());
                notify(MessageType.Warning,
                        "SPARQL Validation: Schema loaded with warnings — "
                        + loaded.skippedFiles().size()
                        + " file(s) could not be parsed and were skipped:\n" + detail);
            } else {
                notify(MessageType.Info, "SPARQL Validation: Schema loaded successfully.");
            }
            fireOnLoaded();
        } catch (Exception e) {
            LOG.error("Failed to load schema: {}", e.getMessage(), e);
            notify(MessageType.Error, "SPARQL Validation: Schema load failed — " + e.getMessage());
            apiRef.set(null);
            defRef.set(null);
            namedGraphRef.set(Map.of());
        }
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
