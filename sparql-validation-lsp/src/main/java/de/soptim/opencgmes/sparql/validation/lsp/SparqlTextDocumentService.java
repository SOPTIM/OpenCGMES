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

package de.soptim.opencgmes.sparql.validation.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Handles text-document lifecycle events and drives SPARQL validation.
 *
 * <p>Validation is debounced (300 ms) so rapid keystrokes don't flood the validator.</p>
 */
final class SparqlTextDocumentService implements TextDocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(SparqlTextDocumentService.class);
    private static final long DEBOUNCE_MS = 300;

    private final SchemaManager schemaManager;
    private volatile LanguageClient client;

    /** Current text content for each open document URI. */
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    /** Pending debounced validation task per URI. */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sparql-validator");
        t.setDaemon(true);
        return t;
    });

    SparqlTextDocumentService(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    void setClient(LanguageClient client) {
        this.client = client;
    }

    // ---- TextDocumentService ---------------------------------------------------------------

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri  = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        if (isSparqlFile(uri)) {
            documents.put(uri, text);
            scheduleValidation(uri, text);
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        if (!isSparqlFile(uri)) return;
        var changes = params.getContentChanges();
        if (changes == null || changes.isEmpty()) return;
        // TextDocumentSyncKind.Full: each change carries the complete new text.
        String text = changes.get(changes.size() - 1).getText();
        documents.put(uri, text);
        scheduleValidation(uri, text);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.remove(uri);
        cancelPending(uri);
        publishDiagnostics(uri, List.of()); // clear squiggles
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri  = params.getTextDocument().getUri();
        String text = documents.get(uri);
        if (text != null) scheduleValidation(uri, text);
    }

    // ---- Internal API ----------------------------------------------------------------------

    /** Called by {@link SchemaManager} after a successful schema load/reload. */
    void revalidateAll() {
        for (var entry : documents.entrySet()) {
            scheduleValidation(entry.getKey(), entry.getValue());
        }
    }

    void shutdown() {
        scheduler.shutdown();
    }

    // ---- Private ---------------------------------------------------------------------------

    private void scheduleValidation(String uri, String text) {
        cancelPending(uri);
        ScheduledFuture<?> future = scheduler.schedule(
                () -> validate(uri, text), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pending.put(uri, future);
    }

    private void cancelPending(String uri) {
        ScheduledFuture<?> f = pending.remove(uri);
        if (f != null) f.cancel(false);
    }

    private void validate(String uri, String text) {
        pending.remove(uri);
        LanguageClient c = client;
        if (c == null) return;

        var apiOpt = schemaManager.getApi();
        if (apiOpt.isEmpty()) {
            var range = new Range(new Position(0, 0), new Position(0, 1));
            var hint  = new Diagnostic(range,
                    "No schema loaded. Add .cgmes/validation.json to enable validation.",
                    DiagnosticSeverity.Information, "sparql-validate");
            publishDiagnostics(uri, List.of(hint));
            return;
        }

        try {
            var result      = apiOpt.get().validateSparql(text);
            var diagnostics = result.annotations().stream()
                    .map(DiagnosticConverter::convert)
                    .toList();
            publishDiagnostics(uri, diagnostics);
            LOG.debug("Validated {}: {} annotation(s)", uri, diagnostics.size());
        } catch (Exception e) {
            LOG.error("Error validating {}: {}", uri, e.getMessage(), e);
        }
    }

    private void publishDiagnostics(String uri, List<Diagnostic> diagnostics) {
        LanguageClient c = client;
        if (c != null) c.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    private static boolean isSparqlFile(String uri) {
        String lower = uri.toLowerCase();
        return lower.endsWith(".rq") || lower.endsWith(".sparql");
    }
}
