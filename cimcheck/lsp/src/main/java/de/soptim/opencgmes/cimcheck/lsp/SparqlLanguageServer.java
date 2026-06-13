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

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Root LSP server object.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@code initialize} — declare capabilities, start async schema load</li>
 *   <li>{@code initialized} — register a file watcher for {@code opencgmes.json}</li>
 *   <li>Normal operation — text-document events drive validation via
 *       {@link SparqlTextDocumentService}</li>
 *   <li>{@code shutdown} / {@code exit} — clean up threads</li>
 * </ol>
 */
public final class SparqlLanguageServer implements LanguageServer, LanguageClientAware {

    private static final Logger LOG = LoggerFactory.getLogger(SparqlLanguageServer.class);

    private final SchemaManager            schemaManager;
    private final SparqlTextDocumentService textDocumentService;
    private final SparqlWorkspaceService    workspaceService;

    private LanguageClient client;

    public SparqlLanguageServer() {
        schemaManager       = new SchemaManager();
        textDocumentService = new SparqlTextDocumentService(schemaManager);
        workspaceService    = new SparqlWorkspaceService(schemaManager);
        // After each successful schema load, revalidate all open documents.
        schemaManager.addOnLoadedCallback(textDocumentService::revalidateAll);
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
        caps.setExecuteCommandProvider(
                new ExecuteCommandOptions(List.of(
                        SparqlWorkspaceService.CMD_EXPLAIN_QUERY,
                        SparqlWorkspaceService.CMD_CREATE_CONFIG)));

        InitializeResult result = new InitializeResult(caps);
        result.setServerInfo(new ServerInfo("SPARQL Validation Server", "1.0.0"));
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void initialized(InitializedParams params) {
        // Register a file-system watcher so we reload schema when the config file changes.
        if (client != null) {
            try {
                FileSystemWatcher watcher = new FileSystemWatcher();
                watcher.setGlobPattern(Either.forLeft("**/opencgmes.json"));

                var regOptions = new DidChangeWatchedFilesRegistrationOptions(List.of(watcher));
                var reg = new Registration(
                        "cgmes-config-watcher",
                        "workspace/didChangeWatchedFiles",
                        regOptions);
                client.registerCapability(new RegistrationParams(List.of(reg)));
                LOG.info("Registered file watcher for opencgmes.json");
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

    @SuppressWarnings("deprecation") // getRootUri() deprecated in LSP 3.6; kept for broad client compat
    private static Path resolveWorkspaceRoot(InitializeParams params) {
        // Prefer rootUri (deprecated but widely sent).
        String rootUri = params.getRootUri();
        if (rootUri != null && !rootUri.isBlank()) {
            try { return Path.of(new URI(rootUri)); } catch (Exception ignored) {}
        }
        // Fall back to workspaceFolders (LSP 3.6+).
        var folders = params.getWorkspaceFolders();
        if (folders != null && !folders.isEmpty()) {
            try { return Path.of(new URI(folders.get(0).getUri())); } catch (Exception ignored) {}
        }
        return Path.of(".");
    }
}
