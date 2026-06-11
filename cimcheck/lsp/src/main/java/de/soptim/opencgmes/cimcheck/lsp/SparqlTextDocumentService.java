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

import de.soptim.opencgmes.cimcheck.core.DefaultPrefixes;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimcheck.core.SourceLocator;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationResult;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationSeverity;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.schema.SchemaIndex;
import de.soptim.opencgmes.cimcheck.core.shacl.EmbeddedSparql;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<LanguageClient> client = new AtomicReference<>();

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
        this.client.set(client);
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

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        try {
            return CompletableFuture.completedFuture(computeHover(params));
        } catch (Exception e) {
            LOG.error("Hover error for {}: {}", params.getTextDocument().getUri(), e.getMessage(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        try {
            String uri  = params.getTextDocument().getUri();
            String text = documents.get(uri);
            if (text == null) return noDefinition();

            var apiOpt = schemaManager.getApi();
            if (apiOpt.isEmpty()) return noDefinition();

            var defIndexOpt = schemaManager.getDefinitionIndex();
            if (defIndexOpt.isEmpty()) return noDefinition();

            int line = params.getPosition().getLine();
            int col  = params.getPosition().getCharacter();

            PrefixMapping prefixes = extractPrefixes(text);
            Node term = termAtPosition(text, line, col, prefixes);
            if (term == null) return noDefinition();

            return defIndexOpt.get().locationOf(term)
                    .map(loc -> CompletableFuture.completedFuture(
                            Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                                    List.of(loc))))
                    .orElseGet(SparqlTextDocumentService::noDefinition);
        } catch (Exception e) {
            LOG.error("Definition error for {}: {}", params.getTextDocument().getUri(), e.getMessage(), e);
            return noDefinition();
        }
    }

    private static CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
            noDefinition() {
        return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        try {
            return CompletableFuture.completedFuture(Either.forLeft(computeCompletions(params)));
        } catch (Exception e) {
            LOG.error("Completion error for {}: {}", params.getTextDocument().getUri(), e.getMessage(), e);
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
    }

    private List<CompletionItem> computeCompletions(CompletionParams params) {
        String uri  = params.getTextDocument().getUri();
        String text = documents.get(uri);
        if (text == null) return List.of();
        var apiOpt = schemaManager.getApi();
        if (apiOpt.isEmpty()) return List.of();
        SchemaIndex index = apiOpt.get().schemaIndex();
        int line = params.getPosition().getLine();
        int col  = params.getPosition().getCharacter();
        return buildCompletionItems(text, line, col, index);
    }

    private Hover computeHover(HoverParams params) {
        String uri  = params.getTextDocument().getUri();
        String text = documents.get(uri);
        if (text == null) return null;

        var apiOpt = schemaManager.getApi();
        if (apiOpt.isEmpty()) return null;

        SchemaIndex index = apiOpt.get().schemaIndex();
        int line = params.getPosition().getLine();
        int col  = params.getPosition().getCharacter();

        PrefixMapping prefixes = extractPrefixes(text);
        Node term = termAtPosition(text, line, col, prefixes);
        if (term == null) return null;

        String markdown = buildHoverMarkdown(term, index, prefixes);
        if (markdown == null || markdown.isBlank()) return null;

        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown));
    }

    // ---- Internal API ----------------------------------------------------------------------

    /** Called by {@link SchemaManager} after a successful schema load/reload. */
    void revalidateAll() {
        for (var entry : documents.entrySet()) {
            scheduleValidation(entry.getKey(), entry.getValue());
        }
    }

    void shutdown() {
        scheduler.shutdownNow();
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
        LanguageClient c = client.get();
        if (c == null) return;

        var apiOpt = schemaManager.getApi();
        if (apiOpt.isEmpty()) {
            var range = new Range(new Position(0, 0), new Position(0, 1));
            var hint  = new Diagnostic(range,
                    "No schema loaded. Add .cgmes/validation.json to enable validation.",
                    DiagnosticSeverity.Information, "cimcheck");
            publishDiagnostics(uri, List.of(hint));
            return;
        }

        try {
            var namedGraphScope = schemaManager.namedGraphScope();
            SparqlValidationResult result = namedGraphScope.isEmpty()
                    ? apiOpt.get().validateSparql(text)
                    : apiOpt.get().validateSparql(text, namedGraphScope);
            var effective   = schemaManager.strictnessLevel().apply(result.annotations());
            var diagnostics = effective.stream()
                    .map(a -> convertSparqlAnnotation(a, text))
                    .toList();
            publishDiagnostics(uri, diagnostics);
            LOG.debug("Validated {}: {} annotation(s)", uri, diagnostics.size());
        } catch (Exception e) {
            LOG.error("Error validating {}: {}", uri, e.getMessage(), e);
        }
    }

    /**
     * Converts a standalone-SPARQL annotation to an LSP diagnostic.
     *
     * <p>Semantic errors (class/property references) keep their positions unchanged — they come
     * from {@link SourceLocator} and are reliable.</p>
     *
     * <p>For syntax errors Jena's {@code getLine()}/{@code getColumn()} is unreliable (it can
     * point to the end of the preceding successfully-parsed token). Two strategies are tried:
     * <ul>
     *   <li><b>Lexical errors</b> (message starts with {@code "Lexical error at line N, column C"}):
     *       the message position IS reliable — Jena points one character past the bad token, so a
     *       backward scan finds the token start.</li>
     *   <li><b>Parse errors</b> (message starts with {@code "Encountered …"}): Javacc error
     *       recovery can jump to an earlier valid position (e.g. the first INSERT after a bad
     *       CREEATE). We ignore the message position and instead scan the query for the first line
     *       that begins with an all-uppercase identifier not in the SPARQL keyword set — that
     *       identifier is the misspelled keyword.</li>
     * </ul>
     * If neither strategy finds a position, a zero-position fallback is used.</p>
     */
    static Diagnostic convertSparqlAnnotation(SparqlValidationAnnotation a, String text) {
        if (a.term() != null) {
            int line = a.line() != null ? a.line() - 1 : 0;
            int col  = a.column() != null ? a.column() - 1 : 0;
            var loc  = new SourceLocator.Location(a.line(), a.column());
            int endCol = col + tokenLengthInSource(text, loc);
            return buildDiagnostic(a.severity(), a.message(), a.code(), line, col, Math.max(col + 1, endCol));
        }

        int line = 0, col = 0;

        if (a.message() != null && a.message().startsWith("Lexical error")) {
            // Lexical error: message position is reliable. Column is one past the bad token.
            int[] pos = positionFromMessage(a.message(), text);
            if (pos != null) { line = pos[0]; col = pos[1]; }
        } else {
            // Parse error: message position is unreliable (Javacc error recovery).
            // Scan for a line starting with an unrecognised all-uppercase SPARQL keyword.
            int[] bad = findBadKeywordLine(text);
            if (bad != null) {
                line = bad[0];
                col  = bad[1];
            } else {
                int[] pos = positionFromMessage(a.message(), text);
                if (pos != null) { line = pos[0]; col = pos[1]; }
            }
        }

        int endCol = col + tokenLengthInSource(text, new SourceLocator.Location(line + 1, col + 1));
        return buildDiagnostic(a.severity(), a.message(), a.code(), line, col, Math.max(col + 1, endCol));
    }

    /**
     * Scans the query text for the first line that starts with an all-uppercase identifier that
     * is NOT in {@link #SPARQL_STATEMENT_KEYWORDS}. Such an identifier is most likely a
     * misspelled SPARQL keyword (e.g. {@code CREEATE}, {@code INSEEERT}).
     *
     * @return {@code [lineIndex, colIndex]} (0-based) of the bad token, or {@code null}
     */
    static int[] findBadKeywordLine(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line    = lines[i];
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // Lines not starting with a letter cannot be misspelled keywords.
            if (!Character.isLetter(trimmed.charAt(0))) continue;
            int tokenStart = line.length() - trimmed.length();
            int tokenEnd   = tokenStart;
            while (tokenEnd < line.length() && Character.isLetter(line.charAt(tokenEnd))) tokenEnd++;
            String token = line.substring(tokenStart, tokenEnd);
            // Must be all-uppercase to look like a keyword (prefix names like "cim" are not).
            if (!token.equals(token.toUpperCase(Locale.ROOT))) continue;
            // Skip built-in function calls (e.g. COALESCE(), STR()) that follow a token boundary.
            if (tokenEnd < line.length() && line.charAt(tokenEnd) == '(') continue;
            if (!SPARQL_STATEMENT_KEYWORDS.contains(token)) return new int[]{i, tokenStart};
        }
        return null;
    }

    /** Scans backward from {@code col} (0-based) on the given line to find where the token starts. */
    private static int backScanToTokenStart(String text, int line, int col) {
        if (col <= 0 || text == null) return Math.max(0, col);
        String[] srcLines = text.split("\n", -1);
        if (line < 0 || line >= srcLines.length) return col;
        String src = srcLines[line];
        int end   = Math.min(col, src.length());
        int start = end;
        while (start > 0 && !Character.isWhitespace(src.charAt(start - 1))) start--;
        return (start < end) ? start : col;
    }

    private static int[] positionFromMessage(String message, String text) {
        if (message == null) return null;
        Matcher m = SPARQL_POSITION.matcher(message);
        if (!m.find()) return null;
        int line = Integer.parseInt(m.group(1)) - 1;
        int col  = backScanToTokenStart(text, line, Integer.parseInt(m.group(2)) - 1);
        return new int[]{line, col};
    }

    private void validateShacl(String uri, String text) {
        LanguageClient c = client.get();
        if (c == null) return;

        var apiOpt = schemaManager.getApi();
        if (apiOpt.isEmpty()) {
            publishDiagnostics(uri, List.of(new Diagnostic(
                    new Range(new Position(0, 0), new Position(0, 1)),
                    "No schema loaded. Add .cgmes/validation.json to enable SHACL validation.",
                    DiagnosticSeverity.Information, "cimcheck")));
            return;
        }

        var diagnostics = new ArrayList<Diagnostic>();

        // Parse Turtle. On failure, collect the parse error and attempt recovery so that
        // CIMcheck schema errors are still shown alongside the syntax problem.
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFParser.fromString(text, Lang.TURTLE).parse(model);
        } catch (Exception e) {
            diagnostics.add(turtleParseErrorDiagnostic(e, text));
            // Recovery: replace unrecognised @keyword directives with @prefix
            // so Jena can parse the rest of the file and CIMcheck errors still appear.
            String recovered = fixWrongTurtleDirectives(text);
            if (!recovered.equals(text)) {
                model = ModelFactory.createDefaultModel();
                try {
                    RDFParser.fromString(recovered, Lang.TURTLE).parse(model);
                } catch (Exception ignored) { /* recovery also failed; model stays empty */ }
            }
        }

        try {
            ShaclValidationResult result = apiOpt.get().validateShacl(model.getGraph());
            var strictness = schemaManager.strictnessLevel();

            // Shape-structure annotations: apply strictness, then locate in Turtle source.
            for (var a : strictness.apply(result.shapeAnnotations())) {
                diagnostics.add(convertShapeAnnotation(a, text, model));
            }

            // Embedded-SPARQL annotations: apply strictness per embedded result.
            for (var er : result.embeddedResults()) {
                String kind = er.embedded().kind().toString();
                for (var a : strictness.apply(er.result().annotations())) {
                    diagnostics.add(convertEmbeddedAnnotation(a, kind, er.embedded(), text));
                }
            }

            publishDiagnostics(uri, diagnostics);
            LOG.debug("Validated SHACL {}: {} diagnostic(s)", uri, diagnostics.size());
        } catch (Exception e) {
            LOG.error("Error validating SHACL {}: {}", uri, e.getMessage(), e);
            publishDiagnostics(uri, diagnostics); // still publish any parse errors
        }
    }

    private static Diagnostic convertShapeAnnotation(
            SparqlValidationAnnotation a,
            String text, PrefixMapping prefixes) {
        var loc = SourceLocator.locate(text, a.term(), prefixes);
        int line   = loc.line()   != null ? loc.line()   - 1 : 0;
        int col    = loc.column() != null ? loc.column() - 1 : 0;
        int endCol = col + tokenLengthInSource(text, loc);
        return buildDiagnostic(a.severity(), a.message(), a.code(), line, col, endCol);
    }

    /**
     * Returns the character length of the SPARQL/Turtle token that starts at {@code loc}.
     *
     * <p>Handles three token forms: {@code <full-IRI>} (scan to {@code >}),
     * {@code prefix:local} and keywords (scan to first delimiter), and falls back to 1.</p>
     */
    private static int tokenLengthInSource(String text, SourceLocator.Location loc) {
        if (loc.line() == null || loc.column() == null || text == null) return 1;
        String[] lines = text.split("\n", -1);
        int li = loc.line() - 1;
        if (li < 0 || li >= lines.length) return 1;
        String src = lines[li];
        int ci = loc.column() - 1;
        if (ci < 0 || ci >= src.length()) return 1;
        if (src.charAt(ci) == '<') {
            int close = src.indexOf('>', ci);
            return close >= 0 ? close - ci + 1 : 1;
        }
        int end = ci;
        while (end < src.length()) {
            char c = src.charAt(end);
            if (Character.isWhitespace(c) || c == ';' || c == ',' || c == '('
                    || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' || c == '#') {
                break;
            }
            end++;
        }
        return Math.max(1, end - ci);
    }

    private static final Pattern JENA_POSITION = Pattern.compile("\\[line:\\s*(\\d+),\\s*col:\\s*(\\d+)\\s*\\]");

    /** Matches Jena's human-readable SPARQL error position: "at line N, column C". */
    private static final Pattern SPARQL_POSITION = Pattern.compile("line (\\d+), column (\\d+)");

    /**
     * SPARQL / SPARQL-Update keywords that can legitimately appear as the first token on a line.
     * Used to distinguish a valid keyword from a misspelled one when locating parse errors.
     */
    static final Set<String> SPARQL_STATEMENT_KEYWORDS = Set.of(
            // Query forms
            "SELECT", "CONSTRUCT", "ASK", "DESCRIBE",
            // Update operations
            "INSERT", "DELETE", "LOAD", "CLEAR", "DROP", "ADD", "MOVE", "COPY", "CREATE",
            // Clauses that routinely start a line
            "WHERE", "OPTIONAL", "FILTER", "UNION", "GRAPH", "MINUS", "SERVICE",
            "BIND", "VALUES", "LIMIT", "OFFSET", "ORDER", "GROUP", "HAVING",
            "WITH", "FROM", "NAMED", "USING", "SILENT", "ALL", "DEFAULT", "INTO", "DATA",
            "NOT", "EXISTS", "DISTINCT", "REDUCED", "AS", "BY", "IN", "TO",
            // Declarations
            "PREFIX", "BASE"
    );

    /**
     * Builds a diagnostic for a Jena Turtle parse error.
     *
     * <p>Extracts the position from the Jena exception message and extends the highlighted range
     * to cover the full token at that position (not just the single character that Jena points
     * at, which would be just the {@code @} for a directive like {@code @prfx}).</p>
     */
    private static Diagnostic turtleParseErrorDiagnostic(Exception e, String text) {
        int line = 0, col = 0;
        String msg = e.getMessage();
        if (msg != null) {
            Matcher m = JENA_POSITION.matcher(msg);
            if (m.find()) {
                line = Math.max(0, Integer.parseInt(m.group(1)) - 1);
                col  = Math.max(0, Integer.parseInt(m.group(2)) - 1);
            }
        }
        int endCol = col + tokenLengthInSource(text, new SourceLocator.Location(line + 1, col + 1));
        String display = msg != null ? msg : e.getClass().getSimpleName();
        return new Diagnostic(
                new Range(new Position(line, col), new Position(line, endCol)),
                "Turtle/SHACL parse error: " + display,
                DiagnosticSeverity.Error, "cimcheck");
    }

    private static Diagnostic convertEmbeddedAnnotation(
            SparqlValidationAnnotation a,
            String kind, EmbeddedSparql embedded, String turtleText) {
        String msg = "[embedded " + kind + "] " + a.message();

        // Jena's QueryParseException.getLine()/getColumn() can point to the wrong position
        // (e.g. end of a preceding PREFIX declaration rather than the bad token). The
        // human-readable exception message carries the correct line/column via the format
        // "line N, column C", so for syntax errors we parse position from there instead.
        int renderedLine = a.line()   != null ? a.line()   : 0;
        int renderedCol  = a.column() != null ? a.column() : 0;
        if (a.term() == null && a.message() != null) {
            Matcher m = SPARQL_POSITION.matcher(a.message());
            if (m.find()) {
                renderedLine = Integer.parseInt(m.group(1));
                renderedCol  = Integer.parseInt(m.group(2));
            }
        }

        int[] pos = embeddedAnnotationTurtlePos(renderedLine, renderedCol, embedded, turtleText);
        int line  = pos[0];
        int col   = pos[1];

        // Jena's column points to the character AFTER the bad token (e.g. the space that
        // follows the misspelled keyword). Scan backwards to find the token start.
        if (a.term() == null) col = backScanToTokenStart(turtleText, line, col);

        int endCol = col + tokenLengthInSource(turtleText,
                new SourceLocator.Location(line + 1, col + 1));
        return buildDiagnostic(a.severity(), msg, a.code(), line, col, Math.max(col + 1, endCol));
    }

    /**
     * Maps a rendered-query position (1-based line + column) back to a [line, col] pair in the
     * Turtle source (both 0-based).
     *
     * <p>The rendered query = {@code prefixes.size()} PREFIX lines + rawQuery. The raw query
     * string is an exact substring of the Turtle source (literal value between the
     * {@code """..."""} delimiters). We locate it with {@link String#indexOf} and navigate
     * forward by the required number of lines — precise and anchor-free.</p>
     *
     * <p>Falls back to a line-anchor search when {@code indexOf} cannot find the raw query.</p>
     */
    private static int[] embeddedAnnotationTurtlePos(
            int renderedLine1based, int renderedCol1based, EmbeddedSparql embedded, String turtleText) {
        if (renderedLine1based <= 0) return new int[]{0, 0};

        int lineInRendered = renderedLine1based - 1;                      // 0-based in rendered query
        int lineInRaw      = lineInRendered - embedded.prefixes().size(); // 0-based in rawQuery
        int col            = Math.max(0, renderedCol1based - 1);
        if (lineInRaw < 0) { lineInRaw = 0; col = 0; }

        String rawQuery = embedded.rawQuery();

        // Exact match: rawQuery IS a literal substring of the Turtle source.
        int rawStart = (rawQuery != null && !rawQuery.isEmpty())
                ? turtleText.indexOf(rawQuery) : -1;
        if (rawStart >= 0) {
            // Navigate lineInRaw newlines forward from rawStart.
            int offset = rawStart;
            for (int i = 0; i < lineInRaw; i++) {
                int nl = turtleText.indexOf('\n', offset);
                if (nl < 0) { offset = turtleText.length(); break; }
                offset = nl + 1;
            }
            // Count newlines before offset → 0-based line index in turtle text.
            int turtleLine = 0;
            for (int i = 0; i < offset && i < turtleText.length(); i++) {
                if (turtleText.charAt(i) == '\n') turtleLine++;
            }
            return new int[]{turtleLine, col};
        }

        // Fallback: find first non-blank, non-comment rawQuery line as search anchor.
        int rawStartLine = findRawQueryStartLine(rawQuery, turtleText);
        return new int[]{rawStartLine + lineInRaw, col};
    }

    /**
     * Fallback for {@link #embeddedAnnotationTurtlePos}: uses the first non-blank,
     * non-comment line of {@code rawQuery} as an anchor and searches for it in the
     * Turtle source.
     */
    private static int findRawQueryStartLine(String rawQuery, String turtleText) {
        if (rawQuery == null || turtleText == null) return 0;
        String[] rawLines = rawQuery.split("\n", -1);
        int anchorIdx = -1;
        String anchor = null;
        for (int i = 0; i < rawLines.length; i++) {
            String t = rawLines[i].trim();
            if (!t.isEmpty() && !t.startsWith("#")) { anchorIdx = i; anchor = t; break; }
        }
        if (anchor == null) return 0;
        String[] turtleLines = turtleText.split("\n", -1);
        for (int i = 0; i < turtleLines.length; i++) {
            if (turtleLines[i].contains(anchor)) return Math.max(0, i - anchorIdx);
        }
        return 0;
    }

    /**
     * Replaces unrecognised {@code @keyword} Turtle directives (e.g. {@code @prfx}) with
     * {@code @prefix} so that a file with a directive typo can still be partially parsed.
     * Only lines outside {@code """..."""} blocks are touched.
     */
    private static String fixWrongTurtleDirectives(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inTripleQuote = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Track whether we are inside a """ ... """ block.
            int tripleCount = 0;
            int idx = 0;
            while ((idx = line.indexOf("\"\"\"", idx)) >= 0) { tripleCount++; idx += 3; }
            if (!inTripleQuote) {
                String trimmed = line.stripLeading();
                if (trimmed.startsWith("@")
                        && !trimmed.startsWith("@prefix")
                        && !trimmed.startsWith("@base")) {
                    // Replace the @wrongKeyword with @prefix; leave the rest untouched.
                    line = line.replaceFirst("@[A-Za-z]+", "@prefix");
                }
                if (tripleCount % 2 != 0) inTripleQuote = true;
            } else {
                if (tripleCount % 2 != 0) inTripleQuote = false;
            }
            sb.append(line);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
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
                message, lspSeverity, "cimcheck");
        d.setCode(Either.forLeft(code.name()));
        return d;
    }

    private void publishDiagnostics(String uri, List<Diagnostic> diagnostics) {
        LanguageClient c = client.get();
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

    // ---- Completion helpers ----------------------------------------------------------------

    /**
     * Builds completion items for the token being typed at {@code (line, col)} in {@code text}.
     *
     * <p>Completions are only returned when the cursor is inside a {@code prefix:local} token
     * whose namespace prefix is declared in the document. This avoids flooding the list on
     * unqualified input. Context detection (class vs. property position) narrows the kind of
     * items returned when the cursor follows a type-like predicate.</p>
     */
    static List<CompletionItem> buildCompletionItems(
            String text, int line, int col, SchemaIndex index) {
        if (text == null || index == null) return List.of();
        String[] lines = text.split("\n", -1);
        if (line < 0 || line >= lines.length) return List.of();
        String src = lines[line];

        int safeCol = Math.min(col, src.length());
        if (safeCol > 0 && isInLineComment(src, safeCol - 1)) return List.of();

        PrefixMapping prefixes = extractPrefixes(text);

        // Find the start of the token being typed.
        int tokenStart = safeCol;
        while (tokenStart > 0 && isNameChar(src.charAt(tokenStart - 1))) tokenStart--;
        String typedSoFar = src.substring(tokenStart, safeCol);

        // Only complete when a recognized prefix has been typed (e.g. "cim:").
        int colonIdx = typedSoFar.indexOf(':');
        if (colonIdx <= 0) return List.of();
        String pfx         = typedSoFar.substring(0, colonIdx);
        String localFilter = typedSoFar.substring(colonIdx + 1).toLowerCase(Locale.ROOT);
        String ns          = prefixes.getNsPrefixURI(pfx);
        if (ns == null) return List.of();

        boolean classCtx = isClassContext(src, tokenStart, prefixes);
        Range replaceRange = new Range(
                new Position(line, tokenStart),
                new Position(line, safeCol));
        List<VersionIri> allProfiles = index.getAllProfiles();
        var items = new ArrayList<CompletionItem>();

        // In class context only show classes; otherwise show both classes and properties.
        for (Node cls : index.allClasses()) {
            addIfMatching(cls, ns, pfx, localFilter, replaceRange,
                    CompletionItemKind.Class, index, allProfiles, items);
        }
        if (!classCtx) {
            for (Node prop : index.allProperties()) {
                addIfMatching(prop, ns, pfx, localFilter, replaceRange,
                        CompletionItemKind.Property, index, allProfiles, items);
            }
        }

        items.sort(Comparator.comparing(CompletionItem::getLabel));
        return items;
    }

    private static void addIfMatching(
            Node term, String ns, String pfx, String localFilter,
            Range replaceRange, CompletionItemKind kind,
            SchemaIndex index, List<VersionIri> allProfiles,
            List<CompletionItem> out) {
        if (!term.isURI() || !term.getURI().startsWith(ns)) return;
        String local = term.getURI().substring(ns.length());
        if (local.isEmpty() || !local.toLowerCase(Locale.ROOT).startsWith(localFilter)) return;

        String abbrev = pfx + ":" + local;
        CompletionItem item = new CompletionItem(abbrev);
        item.setKind(kind);
        item.setTextEdit(Either.forLeft(new TextEdit(replaceRange, abbrev)));
        index.labelOf(term, allProfiles).ifPresent(label -> {
            if (!label.equals(local)) item.setDetail(label);
        });
        index.commentOf(term, allProfiles).ifPresent(comment ->
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, comment)));
        out.add(item);
    }

    /** IRIs whose object position is a class reference. */
    private static final Set<String> CLASS_POSITION_IRIS = Set.of(
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
            "http://www.w3.org/ns/shacl#targetClass",
            "http://www.w3.org/ns/shacl#class",
            "http://www.w3.org/2000/01/rdf-schema#subClassOf",
            "http://www.w3.org/ns/shacl#datatype"
    );

    /**
     * Returns {@code true} when the cursor is in object position after a type-like predicate
     * (e.g. {@code a}, {@code rdf:type}, {@code sh:targetClass}).
     *
     * <p>Scans backward from {@code tokenStart} past whitespace to find the preceding token
     * and checks it against the known set of type predicates.</p>
     */
    static boolean isClassContext(String src, int tokenStart, PrefixMapping prefixes) {
        int i = tokenStart - 1;
        while (i >= 0 && Character.isWhitespace(src.charAt(i))) i--;
        if (i < 0) return false;
        int end = i + 1;
        while (i >= 0 && isNameChar(src.charAt(i))) i--;
        String prev = src.substring(i + 1, end);
        if ("a".equals(prev)) return true;
        int colon = prev.indexOf(':');
        if (colon > 0) {
            String ns = prefixes.getNsPrefixURI(prev.substring(0, colon));
            if (ns != null) return CLASS_POSITION_IRIS.contains(ns + prev.substring(colon + 1));
        }
        return false;
    }

    // ---- Hover helpers ---------------------------------------------------------------------

    /** Extracts all PREFIX / @prefix declarations from raw text (handles invalid documents). */
    private static PrefixMapping extractPrefixes(String text) {
        PrefixMapping pm = PrefixMapping.Factory.create();
        DefaultPrefixes.declaredPrefixes(text).forEach((name, ns) -> {
            try { pm.setNsPrefix(name, ns); } catch (Exception ignored) {}
        });
        return pm;
    }

    /**
     * Returns the schema term (URI node) under the cursor, or {@code null} if none.
     *
     * <p>Tries, in order: {@code <full IRI>}, {@code prefix:local} name, and the {@code a}
     * keyword (resolved to {@code rdf:type}).</p>
     */
    static Node termAtPosition(String text, int line0, int col0, PrefixMapping prefixes) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (line0 < 0 || line0 >= lines.length) return null;
        String src = lines[line0];
        if (src.isEmpty() || col0 < 0) return null;
        int col = Math.min(col0, src.length() - 1);

        // Skip positions inside a # comment
        if (isInLineComment(src, col)) return null;

        // 1. Try <full IRI>
        {
            int lt = -1, gt = -1;
            if (src.charAt(col) == '>') {
                // Cursor is on the closing '>': scan left for the matching '<'
                gt = col;
                for (int i = col - 1; i >= 0; i--) {
                    char c = src.charAt(i);
                    if (c == '<') { lt = i; break; }
                    if (Character.isWhitespace(c) || c == '>') break;
                }
            } else {
                // Scan left for '<', stopping at '>' or whitespace (cursor inside or at '<')
                for (int i = col; i >= 0; i--) {
                    char c = src.charAt(i);
                    if (c == '<') { lt = i; break; }
                    if (Character.isWhitespace(c) || c == '>') break;
                }
                if (lt >= 0) {
                    int g = src.indexOf('>', lt + 1);
                    if (g > lt && col <= g) gt = g;
                }
            }
            if (lt >= 0 && gt > lt) {
                String iri = src.substring(lt + 1, gt);
                if (!iri.isEmpty() && iri.contains(":")) return NodeFactory.createURI(iri);
            }
        }

        // 2. Extract prefixed-name token (letters, digits, '.', '-', '_', ':', '%')
        if (!isNameChar(src.charAt(col))) return null;
        int start = col;
        while (start > 0 && isNameChar(src.charAt(start - 1))) start--;
        int end = col + 1;
        while (end < src.length() && isNameChar(src.charAt(end))) end++;
        String token = src.substring(start, end);
        if (token.isEmpty()) return null;

        // 'a' keyword → rdf:type (only if preceded by non-name char to avoid matching "name")
        if ("a".equals(token)) return RDF.type.asNode();

        int colon = token.indexOf(':');
        if (colon <= 0) return null;
        // Reject tokens with a second colon (e.g. http:// would appear as full IRI, not here)
        if (token.indexOf(':', colon + 1) >= 0) return null;
        String pfx   = token.substring(0, colon);
        String local = token.substring(colon + 1);
        if (local.isEmpty()) return null;
        String ns = prefixes.getNsPrefixURI(pfx);
        if (ns == null) return null;
        return NodeFactory.createURI(ns + local);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ':' || c == '%';
    }

    private static boolean isInLineComment(String src, int col) {
        boolean inSingle = false, inDouble = false, inIri = false;
        for (int i = 0; i < col && i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\' && (inSingle || inDouble)) { i++; continue; }
            if (inIri) {
                if (c == '>') inIri = false;
            } else if (c == '<' && !inSingle && !inDouble) {
                inIri = true;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"'  && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#'  && !inSingle && !inDouble) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a Markdown hover string for the given term, or returns {@code null} if the term
     * is not found in the schema.
     */
    private static String buildHoverMarkdown(Node term, SchemaIndex index, PrefixMapping docPrefixes) {
        if (!term.isURI()) return null;

        boolean isProperty = !index.findProperty(term).isEmpty();
        boolean isClass    = !index.findClass(term).isEmpty();
        if (!isProperty && !isClass) return null;

        List<VersionIri> termProfiles = isProperty ? index.findProperty(term) : index.findClass(term);

        var sb = new StringBuilder();

        // --- Header: abbreviated IRI in bold + label if distinct ---
        String abbrev = abbreviateUri(term.getURI(), docPrefixes);
        sb.append("**`").append(abbrev).append("`**");
        index.labelOf(term, termProfiles).ifPresent(l -> {
            // Only append if label adds information beyond the local name
            String localName = localName(term.getURI());
            if (!l.equals(localName) && !l.equals(abbrev)) sb.append(" — ").append(l);
        });
        sb.append("\n\n");

        // --- rdfs:comment ---
        index.commentOf(term, termProfiles).ifPresent(c -> sb.append(c).append("\n\n"));

        // --- Domain / Range / Profile table ---
        Set<Node> domains = isProperty ? index.domainsOf(term, termProfiles) : Set.of();
        Set<Node> ranges  = isProperty ? index.rangesOf(term, termProfiles)  : Set.of();

        sb.append("| | |\n|---|---|\n");
        if (!domains.isEmpty()) {
            sb.append("| **Domain** | ");
            domains.stream().filter(Node::isURI)
                    .map(n -> "`" + abbreviateUri(n.getURI(), docPrefixes) + "`")
                    .sorted().forEach(s -> sb.append(s).append(' '));
            sb.append("|\n");
        }
        if (!ranges.isEmpty()) {
            sb.append("| **Range** | ");
            ranges.stream().filter(Node::isURI)
                    .map(n -> "`" + abbreviateUri(n.getURI(), docPrefixes) + "`")
                    .sorted().forEach(s -> sb.append(s).append(' '));
            sb.append("|\n");
        }
        sb.append("| **Profile** | ");
        termProfiles.stream()
                .map(v -> "`" + shortProfileIri(v.iri()) + "`")
                .forEach(p -> sb.append(p).append(' '));
        sb.append("|\n");

        return sb.toString().trim();
    }

    private static String abbreviateUri(String iri, PrefixMapping pm) {
        String best = null;
        int bestNsLen = 0;
        for (var e : pm.getNsPrefixMap().entrySet()) {
            String ns = e.getValue();
            if (ns != null && iri.startsWith(ns) && ns.length() > bestNsLen) {
                String local = iri.substring(ns.length());
                if (!local.isEmpty()) {
                    best = e.getKey() + ":" + local;
                    bestNsLen = ns.length();
                }
            }
        }
        return best != null ? best : "<" + iri + ">";
    }

    private static String localName(String iri) {
        int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        return last >= 0 ? iri.substring(last + 1) : iri;
    }

    private static String shortProfileIri(String iri) {
        int last = iri.lastIndexOf('/');
        if (last < 0) return iri;
        int prev = iri.lastIndexOf('/', last - 1);
        return prev >= 0 ? iri.substring(prev + 1) : iri.substring(last + 1);
    }
}
