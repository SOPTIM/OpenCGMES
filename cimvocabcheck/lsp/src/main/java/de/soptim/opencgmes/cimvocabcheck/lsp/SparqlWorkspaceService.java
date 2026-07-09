/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimvocabcheck.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import de.soptim.opencgmes.cimvocabcheck.core.ConfigTemplate;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.config.ConfigLoader;
import de.soptim.opencgmes.cimvocabcheck.core.explain.QueryExplanation;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles workspace-level events.
 *
 * <p>Both configuration changes and watched-file changes (the {@code opencgmes.jsonc} file
 * registered during {@code initialized}) trigger a schema reload. Revalidation of all open
 * documents is driven by the {@code onLoaded} callback registered in {@link SparqlLanguageServer}.
 */
final class SparqlWorkspaceService implements WorkspaceService {

  private static final Logger LOG = LoggerFactory.getLogger(SparqlWorkspaceService.class);

  /** Command id for the static query-explain action (see {@link #executeCommand}). */
  static final String CMD_EXPLAIN_QUERY = "cimvocabcheck.explainQuery";

  /**
   * Command id for generating the {@code opencgmes.jsonc} scaffold. Returns the file contents as a
   * String; the client decides where to write it.
   */
  static final String CMD_CREATE_CONFIG = "cimvocabcheck.createConfig";

  /**
   * Command id for the per-notebook default endpoint (see {@link NotebookDefaults}). The client
   * sends {@code {"notebookUri": "...", "endpoint": "..."}} whenever the user picks a notebook
   * default, and re-sends the notebook defaults it remembers when the server starts.
   */
  static final String CMD_SET_DEFAULT_ENDPOINT = "cimvocabcheck.notebook.setDefaultEndpoint";

  /**
   * Command id for resolving the schema term under a cursor position. Arguments: {@code [uri, line,
   * character]} (zero-based LSP position in an open document). Returns {@code {"iri": ...}} or
   * {@code null} when no term is at the position. Editor integrations use this to link terms to
   * external tools (e.g. "Open in RDFArchitect").
   */
  static final String CMD_TERM_INFO = "cimvocabcheck.termInfo";

  /**
   * Command id for resolving the workspace schema files that apply to a document. Arguments: {@code
   * [uri]} (optional document URI; without it the workspace-root config is used). Returns {@code
   * {"configFile": ..., "schemaFiles": [...]}} with absolute paths, or {@code null} when no config
   * with schemas applies. Editor integrations use this to hand the schema to external tools (e.g.
   * "Send Schema to RDFArchitect").
   */
  static final String CMD_SCHEMA_INFO = "cimvocabcheck.schemaInfo";

  private final SchemaManager schemaManager;
  private final SparqlTextDocumentService documentService;
  private final NotebookCommandHandler notebookCommandHandler;
  private final NotebookDefaults notebookDefaults;

  SparqlWorkspaceService(
      SchemaManager schemaManager,
      SparqlTextDocumentService documentService,
      NotebookCommandHandler notebookCommandHandler,
      NotebookDefaults notebookDefaults) {
    this.schemaManager = schemaManager;
    this.documentService = documentService;
    this.notebookCommandHandler = notebookCommandHandler;
    this.notebookDefaults = notebookDefaults;
  }

  @Override
  public CompletableFuture<
          Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
      symbol(WorkspaceSymbolParams params) {
    try {
      var apiOpt = schemaManager.getApi();
      if (apiOpt.isEmpty()) {
        return CompletableFuture.completedFuture(Either.forRight(List.of()));
      }

      var defIndexOpt = schemaManager.getDefinitionIndex();
      if (defIndexOpt.isEmpty()) {
        return CompletableFuture.completedFuture(Either.forRight(List.of()));
      }

      List<WorkspaceSymbol> symbols =
          defIndexOpt.get().findSymbols(params.getQuery(), apiOpt.get().schemaIndex());
      return CompletableFuture.completedFuture(Either.forRight(symbols));
    } catch (Exception e) {
      LOG.error("Symbol search error: {}", e.getMessage(), e);
      return CompletableFuture.completedFuture(Either.forRight(List.of()));
    }
  }

  @Override
  public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
    if (CMD_CREATE_CONFIG.equals(params.getCommand())) {
      return CompletableFuture.completedFuture(ConfigTemplate.defaultJson());
    }
    if (NotebookCommandHandler.CMD_EXECUTE.equals(params.getCommand())) {
      return notebookCommandHandler.executeCommand(params.getArguments());
    }
    if (NotebookCommandHandler.CMD_LIST_CONNECTIONS.equals(params.getCommand())) {
      return notebookCommandHandler.listConnections(params.getArguments());
    }
    if (CMD_SET_DEFAULT_ENDPOINT.equals(params.getCommand())) {
      notebookDefaults.apply(params.getArguments());
      return CompletableFuture.completedFuture(null);
    }
    if (CMD_TERM_INFO.equals(params.getCommand())) {
      return termInfo(params.getArguments());
    }
    if (CMD_SCHEMA_INFO.equals(params.getCommand())) {
      return schemaInfo(params.getArguments());
    }
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
      QueryExplanation explanation =
          schemaManager
              .getApi()
              .map(api -> api.explain(queryText))
              .orElseGet(() -> SparqlValidationApi.explainStatic(queryText));
      return CompletableFuture.completedFuture(explanation.render());
    } catch (Exception e) {
      LOG.error("explainQuery failed: {}", e.getMessage(), e);
      return CompletableFuture.completedFuture(
          "# Error\nCould not explain query: " + e.getMessage() + "\n");
    }
  }

  /** Resolves the term under a cursor position (see {@link #CMD_TERM_INFO}). */
  private CompletableFuture<Object> termInfo(List<Object> args) {
    try {
      String uri = stringArg(args, 0);
      Integer line = intArg(args, 1);
      Integer character = intArg(args, 2);
      if (uri == null || line == null || character == null) {
        return CompletableFuture.completedFuture(null);
      }
      String iri = documentService.termIriAt(uri, line, character);
      return CompletableFuture.completedFuture(iri == null ? null : Map.of("iri", iri));
    } catch (Exception e) {
      LOG.error("termInfo failed: {}", e.getMessage(), e);
      return CompletableFuture.completedFuture(null);
    }
  }

  /** Resolves the workspace schema files for a document (see {@link #CMD_SCHEMA_INFO}). */
  private CompletableFuture<Object> schemaInfo(List<Object> args) {
    try {
      String uri = stringArg(args, 0);
      var docDir = uri == null ? null : SparqlTextDocumentService.documentDir(uri);
      return CompletableFuture.completedFuture(
          schemaManager
              .schemaFilesFor(docDir)
              .<Object>map(
                  sf ->
                      Map.of(
                          "configFile",
                          sf.configFile().toAbsolutePath().toString(),
                          "schemaFiles",
                          sf.files().stream().map(p -> p.toAbsolutePath().toString()).toList()))
              .orElse(null));
    } catch (Exception e) {
      LOG.error("schemaInfo failed: {}", e.getMessage(), e);
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * Extracts the first command argument as a String. Over JSON-RPC, lsp4j delivers arguments as
   * Gson {@link JsonElement}s; a direct in-process call may pass a plain {@link String}.
   */
  private static String firstStringArg(List<Object> args) {
    return stringArg(args, 0);
  }

  /** Command argument at {@code index} as a String, tolerating Gson and in-process forms. */
  private static String stringArg(List<Object> args, int index) {
    Object arg = argAt(args, index);
    if (arg instanceof String s) {
      return s;
    }
    if (arg instanceof JsonPrimitive p) {
      return p.getAsString();
    }
    if (arg instanceof JsonElement el && el.isJsonPrimitive()) {
      return el.getAsString();
    }
    return arg == null ? null : arg.toString();
  }

  /** Command argument at {@code index} as an Integer, tolerating Gson and in-process forms. */
  private static Integer intArg(List<Object> args, int index) {
    Object arg = argAt(args, index);
    if (arg instanceof Number n) {
      return n.intValue();
    }
    if (arg instanceof JsonPrimitive p && p.isNumber()) {
      return p.getAsInt();
    }
    if (arg instanceof JsonElement el && el.isJsonPrimitive()) {
      try {
        return el.getAsInt();
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static Object argAt(List<Object> args, int index) {
    return args == null || args.size() <= index ? null : args.get(index);
  }

  @Override
  public void didChangeConfiguration(DidChangeConfigurationParams params) {
    LOG.info("Configuration changed — reloading schema");
    schemaManager.reloadAsync();
  }

  @Override
  public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    if (params.getChanges() == null) {
      return;
    }
    boolean configChanged =
        params.getChanges().stream()
            .anyMatch(
                e ->
                    ConfigLoader.CONFIG_FILENAMES.stream()
                        .anyMatch(name -> e.getUri().endsWith(name)));
    if (configChanged) {
      LOG.info("opencgmes config file changed — reloading schema");
      schemaManager.reloadAsync();
    }
  }
}
