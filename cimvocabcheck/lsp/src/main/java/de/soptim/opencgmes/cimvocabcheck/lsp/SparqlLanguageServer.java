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

import de.soptim.opencgmes.cimvocabcheck.core.config.ConfigLoader;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root LSP server object.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@code initialize} — declare capabilities, start async schema load
 *   <li>{@code initialized} — register a file watcher for {@code opencgmes.jsonc}/{@code
 *       opencgmes.json}
 *   <li>Normal operation — text-document events drive validation via {@link
 *       SparqlTextDocumentService}
 *   <li>{@code shutdown} / {@code exit} — clean up threads
 * </ol>
 */
public final class SparqlLanguageServer implements LanguageServer, LanguageClientAware {

  private static final Logger LOG = LoggerFactory.getLogger(SparqlLanguageServer.class);

  private final SchemaManager schemaManager;
  private final SparqlTextDocumentService textDocumentService;
  private final SparqlWorkspaceService workspaceService;
  private final NotebookCommandHandler notebookCommandHandler;

  private LanguageClient client;

  /** Creates the server and wires up its document, workspace, and schema services. */
  public SparqlLanguageServer() {
    schemaManager = new SchemaManager();
    NotebookDefaults notebookDefaults = new NotebookDefaults();
    textDocumentService = new SparqlTextDocumentService(schemaManager, notebookDefaults);
    notebookCommandHandler = new NotebookCommandHandler();
    workspaceService =
        new SparqlWorkspaceService(schemaManager, notebookCommandHandler, notebookDefaults);
    // After each successful schema load, revalidate all open documents.
    schemaManager.addOnLoadedCallback(textDocumentService::revalidateAll);
    // Likewise when a notebook's default endpoint changes: its directive-less cells now validate
    // against a different schema.
    notebookDefaults.addOnChangeCallback(textDocumentService::revalidateAll);
  }

  // ---- LanguageClientAware ---------------------------------------------------------------

  @Override
  public void connect(LanguageClient client) {
    this.client = client;
    schemaManager.setClient(client);
    textDocumentService.setClient(client);
  }

  // ---- LanguageServer lifecycle ----------------------------------------------------------

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    Path workspaceRoot = resolveWorkspaceRoot(params);
    LOG.info("Initializing with workspace root: {}", workspaceRoot);
    schemaManager.loadAsync(workspaceRoot);

    ServerCapabilities caps = new ServerCapabilities();
    caps.setTextDocumentSync(TextDocumentSyncKind.Full);
    caps.setHoverProvider(true);
    caps.setCompletionProvider(new CompletionOptions(false, List.of(":")));
    caps.setDefinitionProvider(true);
    caps.setWorkspaceSymbolProvider(true);

    InitializeResult result = new InitializeResult(caps);
    result.setServerInfo(new ServerInfo("SPARQL Validation Server", "1.0.0"));
    return CompletableFuture.completedFuture(result);
  }

  @Override
  public void initialized(InitializedParams params) {
    // Register a file-system watcher so we reload schema when the config file changes.
    if (client != null) {
      try {
        List<FileSystemWatcher> watchers =
            ConfigLoader.CONFIG_FILENAMES.stream()
                .map(
                    fileName -> {
                      FileSystemWatcher watcher = new FileSystemWatcher();
                      watcher.setGlobPattern(Either.forLeft("**/" + fileName));
                      return watcher;
                    })
                .collect(Collectors.toList());

        var regOptions = new DidChangeWatchedFilesRegistrationOptions(watchers);
        var reg =
            new Registration("cgmes-config-watcher", "workspace/didChangeWatchedFiles", regOptions);
        client.registerCapability(new RegistrationParams(List.of(reg)));
        LOG.info("Registered file watcher for {}", ConfigLoader.CONFIG_FILENAMES);
      } catch (Exception e) {
        LOG.warn("Could not register file watcher: {}", e.getMessage());
      }
    }
  }

  @Override
  public void setTrace(SetTraceParams params) {
    // VS Code sends $/setTrace when trace is enabled in settings — no-op is correct here;
    // tracing is handled by the client-side output channel, not the server.
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    schemaManager.shutdown();
    textDocumentService.shutdown();
    notebookCommandHandler.shutdown();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    System.exit(0);
  }

  // ---- Services --------------------------------------------------------------------------

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocumentService;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return workspaceService;
  }

  // ---- Private ---------------------------------------------------------------------------

  @SuppressWarnings(
      "deprecation") // getRootUri() deprecated in LSP 3.6; kept for broad client compat
  private static Path resolveWorkspaceRoot(InitializeParams params) {
    // Prefer rootUri (deprecated but widely sent).
    String rootUri = params.getRootUri();
    if (rootUri != null && !rootUri.isBlank()) {
      try {
        return Path.of(new URI(rootUri));
      } catch (Exception ignored) {
        // Intentionally ignored.
      }
    }
    // Fall back to workspaceFolders (LSP 3.6+).
    var folders = params.getWorkspaceFolders();
    if (folders != null && !folders.isEmpty()) {
      try {
        return Path.of(new URI(folders.get(0).getUri()));
      } catch (Exception ignored) {
        // Intentionally ignored.
      }
    }
    return Path.of(".");
  }
}
