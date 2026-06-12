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
    private final List<Runnable> onLoadedCallbacks = new CopyOnWriteArrayList<>();

    /** Schemas loaded from a {@code # [endpoint=...]} directive, keyed by resolved source. */
    private final Map<String, ResolvedSchema> endpointCache = new ConcurrentHashMap<>();
    /** Endpoint sources already reported as failing, to avoid repeating the same notification. */
    private final Set<String> warnedEndpoints = ConcurrentHashMap.newKeySet();

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
        boolean remote = isRemote(endpoint);
        String cacheKey = remote ? endpoint : resolveLocalEndpoint(endpoint, docDir).toString();
        ResolvedSchema cached = endpointCache.get(cacheKey);
        if (cached != null) return Optional.of(cached);
        Optional<ResolvedSchema> loaded = remote
                ? loadRemoteEndpoint(endpoint)
                : loadLocalEndpoint(resolveLocalEndpoint(endpoint, docDir));
        loaded.ifPresent(rs -> endpointCache.put(cacheKey, rs));
        return loaded;
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

    private Optional<ResolvedSchema> loadRemoteEndpoint(String endpoint) {
        if (warnedEndpoints.add(endpoint)) {
            notify(MessageType.Warning,
                    "CIMcheck: Loading a schema from a remote SPARQL endpoint is not yet supported: "
                    + endpoint);
        }
        return Optional.empty();
    }

    private Optional<ResolvedSchema> loadLocalEndpoint(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                if (warnedEndpoints.add(file.toString())) {
                    notify(MessageType.Warning, "CIMcheck: endpoint schema file not found: " + file);
                }
                return Optional.empty();
            }
            var index    = CgmesSchemaLoader.fromFiles(List.of(file)).loadIndex();
            var prefixes = DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, index);
            var api      = new SparqlValidationApi(index, prefixes);
            LOG.info("Loaded endpoint schema from file {}", file);
            return Optional.of(new ResolvedSchema(api, levelRef.get(), Map.of()));
        } catch (Exception e) {
            LOG.error("Failed to load endpoint schema {}: {}", file, e.getMessage(), e);
            if (warnedEndpoints.add(file.toString())) {
                notify(MessageType.Error,
                        "CIMcheck: failed to load schema from " + file + " — " + e.getMessage());
            }
            return Optional.empty();
        }
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
        warnedEndpoints.clear();
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
            apiRef.set(new SparqlValidationApi(loaded.index(), effectivePrefixes));
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
            for (Runnable cb : onLoadedCallbacks) {
                try {
                    cb.run();
                } catch (Exception cbEx) {
                    LOG.error("On-loaded callback failed: {}", cbEx.getMessage(), cbEx);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load schema: {}", e.getMessage(), e);
            notify(MessageType.Error, "SPARQL Validation: Schema load failed — " + e.getMessage());
            apiRef.set(null);
            defRef.set(null);
            namedGraphRef.set(Map.of());
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
