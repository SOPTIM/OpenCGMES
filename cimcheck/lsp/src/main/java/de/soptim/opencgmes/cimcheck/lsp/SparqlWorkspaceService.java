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

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimcheck.core.explain.QueryExplanation;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles workspace-level events.
 *
 * <p>Both configuration changes and watched-file changes (the {@code .cgmes/validation.json}
 * file registered during {@code initialized}) trigger a schema reload. Revalidation of all
 * open documents is driven by the {@code onLoaded} callback registered in
 * {@link SparqlLanguageServer}.</p>
 */
final class SparqlWorkspaceService implements WorkspaceService {

    private static final Logger LOG = LoggerFactory.getLogger(SparqlWorkspaceService.class);

    /** Command id for the static query-explain action (see {@link #executeCommand}). */
    static final String CMD_EXPLAIN_QUERY = "cimcheck.explainQuery";

    private final SchemaManager schemaManager;

    SparqlWorkspaceService(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
            symbol(WorkspaceSymbolParams params) {
        try {
            var apiOpt = schemaManager.getApi();
            if (apiOpt.isEmpty())
                return CompletableFuture.completedFuture(Either.forRight(List.of()));

            var defIndexOpt = schemaManager.getDefinitionIndex();
            if (defIndexOpt.isEmpty())
                return CompletableFuture.completedFuture(Either.forRight(List.of()));

            List<WorkspaceSymbol> symbols = defIndexOpt.get().findSymbols(
                    params.getQuery(), apiOpt.get().schemaIndex());
            return CompletableFuture.completedFuture(Either.forRight(symbols));
        } catch (Exception e) {
            LOG.error("Symbol search error: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(Either.forRight(List.of()));
        }
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if (!CMD_EXPLAIN_QUERY.equals(params.getCommand())) {
            LOG.warn("Unknown command: {}", params.getCommand());
            return CompletableFuture.completedFuture(null);
        }
        try {
            String queryText = firstStringArg(params.getArguments());
            if (queryText == null || queryText.isBlank()) {
                return CompletableFuture.completedFuture("# Query\n(no query text provided)\n");
            }
            // The algebra plan does not depend on the schema, only on prefix injection. Use the
            // schema-aware API (with its detected cim: prefix) when it is loaded, otherwise fall back
            // to the built-in prefixes so explain still works while the schema is loading.
            QueryExplanation explanation = schemaManager.getApi()
                    .map(api -> api.explain(queryText))
                    .orElseGet(() -> SparqlValidationApi.explainStatic(queryText));
            return CompletableFuture.completedFuture(explanation.render());
        } catch (Exception e) {
            LOG.error("explainQuery failed: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(
                    "# Error\nCould not explain query: " + e.getMessage() + "\n");
        }
    }

    /**
     * Extracts the first command argument as a String. Over JSON-RPC, lsp4j delivers arguments as
     * Gson {@link JsonElement}s; a direct in-process call may pass a plain {@link String}.
     */
    private static String firstStringArg(List<Object> args) {
        if (args == null || args.isEmpty()) return null;
        Object first = args.get(0);
        if (first instanceof String s) return s;
        if (first instanceof JsonPrimitive p) return p.getAsString();
        if (first instanceof JsonElement el && el.isJsonPrimitive()) return el.getAsString();
        return first == null ? null : first.toString();
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        LOG.info("Configuration changed — reloading schema");
        schemaManager.reloadAsync();
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        if (params.getChanges() == null) return;
        boolean configChanged = params.getChanges().stream()
                .anyMatch(e -> e.getUri().contains(".cgmes/validation.json"));
        if (configChanged) {
            LOG.info(".cgmes/validation.json changed — reloading schema");
            schemaManager.reloadAsync();
        }
    }
}
