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

import de.soptim.opencgmes.sparql.validation.SourceLocator;
import de.soptim.opencgmes.sparql.validation.SparqlValidationCode;
import de.soptim.opencgmes.sparql.validation.SparqlValidationSeverity;
import de.soptim.opencgmes.sparql.validation.shacl.EmbeddedSparql;
import de.soptim.opencgmes.sparql.validation.shacl.ShaclValidationResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shared.PrefixMapping;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        if (isSupportedFile(uri)) {
            documents.put(uri, text);
            scheduleValidation(uri, text);
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        if (!isSupportedFile(uri)) return;
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
        Runnable task = isTurtleFile(uri)
                ? () -> validateShacl(uri, text)
                : () -> validateSparql(uri, text);
        ScheduledFuture<?> future = scheduler.schedule(task, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pending.put(uri, future);
    }

    private void cancelPending(String uri) {
        ScheduledFuture<?> f = pending.remove(uri);
        if (f != null) f.cancel(false);
    }

    private void validateSparql(String uri, String text) {
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

    private void validateShacl(String uri, String text) {
        pending.remove(uri);
        LanguageClient c = client;
        if (c == null) return;

        var apiOpt = schemaManager.getApi();
        if (apiOpt.isEmpty()) {
            publishDiagnostics(uri, List.of(new Diagnostic(
                    new Range(new Position(0, 0), new Position(0, 1)),
                    "No schema loaded. Add .cgmes/validation.json to enable SHACL validation.",
                    DiagnosticSeverity.Information, "sparql-validate")));
            return;
        }

        // Parse Turtle — report a syntax error diagnostic if it fails.
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFParser.fromString(text, Lang.TURTLE).parse(model);
        } catch (Exception e) {
            publishDiagnostics(uri, List.of(turtleParseErrorDiagnostic(e)));
            return;
        }

        try {
            ShaclValidationResult result = apiOpt.get().validateShacl(model.getGraph());
            var diagnostics = new ArrayList<Diagnostic>();

            // Shape-structure annotations: use SourceLocator to find term positions in Turtle source.
            for (var a : result.shapeAnnotations()) {
                diagnostics.add(convertShapeAnnotation(a, text, model));
            }

            // Embedded-SPARQL annotations: map positions from the renderedQuery back into
            // the Turtle source. The renderedQuery has prefixes.size() PREFIX lines prepended
            // before rawQuery; subtracting that offset gives the line within rawQuery, and
            // searching for rawQuery's first non-empty line in the Turtle source gives the
            // Turtle line offset to add.
            for (var er : result.embeddedResults()) {
                String kind = er.embedded().kind().toString();
                for (var a : er.result().annotations()) {
                    diagnostics.add(convertEmbeddedAnnotation(a, kind, er.embedded(), text));
                }
            }

            publishDiagnostics(uri, diagnostics);
            LOG.debug("Validated SHACL {}: {} diagnostic(s)", uri, diagnostics.size());
        } catch (Exception e) {
            LOG.error("Error validating SHACL {}: {}", uri, e.getMessage(), e);
        }
    }

    private static Diagnostic convertShapeAnnotation(
            de.soptim.opencgmes.sparql.validation.SparqlValidationAnnotation a,
            String text, PrefixMapping prefixes) {
        var loc = SourceLocator.locate(text, a.term(), prefixes);
        int line   = loc.line()   != null ? loc.line()   - 1 : 0;
        int col    = loc.column() != null ? loc.column() - 1 : 0;
        int endCol = (a.term() != null && a.term().isURI())
                   ? col + a.term().getURI().length() + 2
                   : col + 1;
        return buildDiagnostic(a.severity(), a.message(), a.code(), line, col, endCol);
    }

    private static final Pattern JENA_POSITION = Pattern.compile("\\[line:\\s*(\\d+),\\s*col:\\s*(\\d+)\\s*\\]");

    /** Builds a diagnostic for a Jena Turtle parse error, extracting position from the message. */
    private static Diagnostic turtleParseErrorDiagnostic(Exception e) {
        int line = 0, col = 0;
        String msg = e.getMessage();
        if (msg != null) {
            Matcher m = JENA_POSITION.matcher(msg);
            if (m.find()) {
                line = Math.max(0, Integer.parseInt(m.group(1)) - 1);
                col  = Math.max(0, Integer.parseInt(m.group(2)) - 1);
            }
        }
        return new Diagnostic(
                new Range(new Position(line, col), new Position(line, col + 1)),
                "Turtle/SHACL parse error: " + msg,
                DiagnosticSeverity.Error, "sparql-validate");
    }

    private static Diagnostic convertEmbeddedAnnotation(
            de.soptim.opencgmes.sparql.validation.SparqlValidationAnnotation a,
            String kind, EmbeddedSparql embedded, String turtleText) {
        String msg = "[embedded " + kind + "] " + a.message();
        int endCol = (a.term() != null && a.term().isURI())
                   ? a.term().getURI().length() + 2
                   : 1;
        int line = embeddedAnnotationTurtleLine(a, embedded, turtleText);
        int col  = a.column() != null ? Math.max(0, a.column() - 1) : 0;
        return buildDiagnostic(a.severity(), msg, a.code(), line, col, Math.max(col + 1, col + endCol));
    }

    /**
     * Maps an annotation position (relative to {@code renderedQuery}) back to a 0-based line
     * in the Turtle source.
     *
     * <p>{@code renderedQuery} = {@code prefixes.size()} PREFIX lines + rawQuery.
     * So the line within rawQuery is {@code annotation.line() - prefixes.size()}.
     * We then find the line in the Turtle text where rawQuery starts and add that offset.</p>
     */
    private static int embeddedAnnotationTurtleLine(
            de.soptim.opencgmes.sparql.validation.SparqlValidationAnnotation a,
            EmbeddedSparql embedded, String turtleText) {
        if (a.line() == null) return 0;
        int lineInRendered = a.line() - 1;                  // 0-based within renderedQuery
        int lineInRaw = lineInRendered - embedded.prefixes().size(); // 0-based within rawQuery

        // Find the 0-based start line of rawQuery inside the Turtle source.
        int rawStartLine = findRawQueryStartLine(embedded.rawQuery(), turtleText);
        return rawStartLine + Math.max(0, lineInRaw);
    }

    /**
     * Returns the 0-based line number in {@code turtleText} where rawQuery line 0 would appear.
     * Uses the first non-blank, non-comment line of rawQuery as a search anchor, then subtracts
     * its 0-based index within rawQuery so the result points at rawQuery[0].
     */
    private static int findRawQueryStartLine(String rawQuery, String turtleText) {
        String[] rawLines = rawQuery.split("\n", -1);
        int anchorIdxInRaw = -1;
        String anchor = null;
        for (int i = 0; i < rawLines.length; i++) {
            String trimmed = rawLines[i].trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                anchorIdxInRaw = i;
                anchor = trimmed;
                break;
            }
        }
        if (anchor == null) return 0;

        String[] turtleLines = turtleText.split("\n", -1);
        for (int i = 0; i < turtleLines.length; i++) {
            if (turtleLines[i].contains(anchor)) {
                // i is the turtle line of rawQuery[anchorIdxInRaw]; subtract to get rawQuery[0].
                return Math.max(0, i - anchorIdxInRaw);
            }
        }
        return 0;
    }

    private static Diagnostic buildDiagnostic(
            SparqlValidationSeverity severity, String message, SparqlValidationCode code,
            int line, int col, int endCol) {
        DiagnosticSeverity lspSeverity = switch (severity) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARN  -> DiagnosticSeverity.Warning;
            case INFO  -> DiagnosticSeverity.Information;
        };
        Diagnostic d = new Diagnostic(
                new Range(new Position(line, col), new Position(line, endCol)),
                message, lspSeverity, "sparql-validate");
        d.setCode(Either.forLeft(code.name()));
        return d;
    }

    private void publishDiagnostics(String uri, List<Diagnostic> diagnostics) {
        LanguageClient c = client;
        if (c != null) c.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    private static boolean isSupportedFile(String uri) {
        return isSparqlFile(uri) || isTurtleFile(uri);
    }

    private static boolean isSparqlFile(String uri) {
        String lower = uri.toLowerCase();
        return lower.endsWith(".rq") || lower.endsWith(".sparql");
    }

    private static boolean isTurtleFile(String uri) {
        String lower = uri.toLowerCase();
        return lower.endsWith(".ttl") || lower.endsWith(".shacl");
    }
}
