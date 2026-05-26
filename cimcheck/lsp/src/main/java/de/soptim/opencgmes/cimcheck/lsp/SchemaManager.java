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

import de.soptim.opencgmes.cimcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.analysis.SparqlQueryAnalyzer;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.lsp.config.ConfigLoader;
import de.soptim.opencgmes.cimcheck.lsp.config.LspConfig;
import de.soptim.opencgmes.cimcheck.lsp.schema.SchemaLoader;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private volatile Path workspaceRoot;
    private volatile LanguageClient client;

    // ---- API -------------------------------------------------------------------------------

    void setClient(LanguageClient client) {
        this.client = client;
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
            apiRef.set(new SparqlValidationApi(loaded.index()));
            levelRef.set(parseLevel(config));
            defRef.set(DefinitionIndex.build(loaded.index(), loaded.sourcePaths()));
            namedGraphRef.set(buildNamedGraphScope(config, loaded.index()));
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

    private static Map<Node, Collection<VersionIri>> buildNamedGraphScope(
            LspConfig config, RdfsSchemaIndex index) {

        if (!config.hasNamedGraphs()) return Map.of();

        var map = new LinkedHashMap<Node, Collection<VersionIri>>();
        for (var entry : config.namedGraphs().entrySet()) {
            String key = entry.getKey();
            // Relative keys (no colon) are resolved against the same base URI used by the
            // SPARQL parser so that <EQ> in a query matches the config key "EQ".
            Node graphNode = key.contains(":")
                    ? NodeFactory.createURI(key)
                    : NodeFactory.createURI(SparqlQueryAnalyzer.RELATIVE_IRI_BASE + key);

            var versionIris = new ArrayList<VersionIri>();
            for (String profileUri : entry.getValue()) {
                VersionIri vIri = VersionIri.of(profileUri);
                if (index.getAllProfiles().contains(vIri)) {
                    versionIris.add(vIri);
                } else {
                    LOG.warn("namedGraph '{}' references unknown profile '{}' — profile will be skipped.",
                            key, profileUri);
                }
            }
            if (!versionIris.isEmpty()) {
                map.put(graphNode, versionIris);
            } else {
                LOG.warn("namedGraph '{}' has no known profiles — graph will be excluded from scope.", key);
            }
        }
        return Map.copyOf(map);
    }

    private void notify(MessageType type, String message) {
        LanguageClient c = client;
        if (c != null) c.showMessage(new MessageParams(type, message));
    }
}
