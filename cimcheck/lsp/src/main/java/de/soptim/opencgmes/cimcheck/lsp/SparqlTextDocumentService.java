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

import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimcheck.core.SourceLocator;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationCode;
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
                    DiagnosticSeverity.Information, "cimcheck");
            publishDiagnostics(uri, List.of(hint));
            return;
        }

        try {
            var result      = apiOpt.get().validateSparql(text);
            var effective   = schemaManager.strictnessLevel().apply(result.annotations());
            var diagnostics = effective.stream()
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
                    DiagnosticSeverity.Information, "cimcheck")));
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
            var strictness  = schemaManager.strictnessLevel();
            var diagnostics = new ArrayList<Diagnostic>();

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
                DiagnosticSeverity.Error, "cimcheck");
    }

    private static Diagnostic convertEmbeddedAnnotation(
            SparqlValidationAnnotation a,
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
            SparqlValidationAnnotation a,
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
                message, lspSeverity, "cimcheck");
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

    private static final Pattern PREFIX_PATTERN = Pattern.compile(
            "(?i)(?:@prefix|PREFIX)\\s+(\\w+):\\s*<([^>]*)>");

    /** Extracts all PREFIX / @prefix declarations from raw text (handles invalid documents). */
    private static PrefixMapping extractPrefixes(String text) {
        PrefixMapping pm = PrefixMapping.Factory.create();
        if (text == null) return pm;
        Matcher m = PREFIX_PATTERN.matcher(text);
        while (m.find()) {
            try { pm.setNsPrefix(m.group(1), m.group(2)); } catch (Exception ignored) {}
        }
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
