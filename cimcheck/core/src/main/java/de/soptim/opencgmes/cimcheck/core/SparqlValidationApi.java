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

package de.soptim.opencgmes.cimcheck.core;

import de.soptim.opencgmes.cimcheck.core.analysis.ClassReference;
import de.soptim.opencgmes.cimcheck.core.analysis.GraphReference;
import de.soptim.opencgmes.cimcheck.core.analysis.PropertyReference;
import de.soptim.opencgmes.cimcheck.core.analysis.SparqlQueryAnalysis;
import de.soptim.opencgmes.cimcheck.core.analysis.SparqlQueryAnalyzer;
import de.soptim.opencgmes.cimcheck.core.analysis.SparqlUpdateAnalysis;
import de.soptim.opencgmes.cimcheck.core.schema.SchemaIndex;
import de.soptim.opencgmes.cimcheck.core.schema.ValidationScope;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import de.soptim.opencgmes.cimcheck.core.shacl.EmbeddedSparql;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclEmbeddedQueryResult;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclShapeAnalyzer;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclSparqlExtractor;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;

import org.apache.jena.graph.NodeFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level façade exposing the three {@code validateSparql} overloads and the four dependency
 * extraction methods documented in the module README.
 *
 * <p>All methods accept a SPARQL query string and a schema-resolution policy that is one of:</p>
 * <ul>
 *   <li><b>(no scope)</b> — validate against every profile known to the {@link SchemaIndex};
 *       {@code FROM} / {@code FROM NAMED} / {@code GRAPH} names in the query are ignored for
 *       schema scoping.</li>
 *   <li><b>{@code Collection<VersionIri>}</b> — restrict the schema scope to the given profiles;
 *       again, in-query graph names are ignored for scoping (but still listed by
 *       {@link #getGraphDependencies}).</li>
 *   <li><b>{@code Map<Node, Collection<VersionIri>>}</b> — terms inside
 *       {@code GRAPH <g> { ... }} are validated against the profiles mapped to {@code <g>};
 *       graphs used by the query but missing from the map produce a
 *       {@code GRAPH_NOT_CONFIGURED} warning.</li>
 * </ul>
 */
public final class SparqlValidationApi {

    private final SparqlQueryValidator validator;
    private final ShaclSparqlExtractor shaclExtractor = new ShaclSparqlExtractor();
    private final ShaclShapeAnalyzer shaclAnalyzer;
    private final Map<String, String> defaultPrefixes;

    /**
     * Constructs the API with auto-detected default prefixes.
     *
     * <p>The built-in prefix set is used as the base, but the {@code cim:} entry is replaced
     * with the namespace actually used by the majority of terms in {@code schemaIndex} — so the
     * correct {@code cim:} namespace is injected regardless of whether the schema is CGMES 2.4.15,
     * CGMES 3.0, or any other CIM version.  If no single dominant namespace is detected (e.g., a
     * mixed-version schema), the built-in {@code cim:} default is kept unchanged.</p>
     */
    public SparqlValidationApi(SchemaIndex schemaIndex) {
        this(schemaIndex, DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, schemaIndex));
    }

    /**
     * Constructs the API with a custom default prefix map.
     *
     * <p>Pass {@link DefaultPrefixes#BUILT_IN} to use the standard set, pass
     * {@code Map.of()} to disable automatic prefix injection entirely, or pass a
     * custom map to replace the built-in defaults with workspace-specific prefixes.</p>
     */
    public SparqlValidationApi(SchemaIndex schemaIndex, Map<String, String> defaultPrefixes) {
        this.validator = new SparqlQueryValidator(Objects.requireNonNull(schemaIndex, "schemaIndex"));
        this.shaclAnalyzer = new ShaclShapeAnalyzer(schemaIndex);
        this.defaultPrefixes = Map.copyOf(Objects.requireNonNull(defaultPrefixes, "defaultPrefixes"));
    }

    public SchemaIndex schemaIndex() {
        return validator.schemaIndex();
    }

    // ---- validateSparql overloads ----------------------------------------------------------

    /**
     * Validates a SPARQL statement against all known schema profiles.
     *
     * <p>The input is auto-detected: if it parses as a SPARQL query (SELECT / CONSTRUCT /
     * ASK / DESCRIBE) it is validated as a query; otherwise it is attempted as a SPARQL Update
     * request (INSERT DATA, DELETE WHERE, INSERT/DELETE … WHERE, CREATE GRAPH, DROP GRAPH, or
     * multiple such operations separated by {@code ;}).  If neither parse succeeds, the query
     * parse error is returned as a {@code SYNTAX_ERROR} annotation.</p>
     */
    public SparqlValidationResult validateSparql(String input) {
        return validateAutoDetect(Objects.requireNonNull(input, "input"),
                new ValidationScope.AllProfilesScope());
    }

    /** Validates against an explicit list of profiles; see {@link #validateSparql(String)}. */
    public SparqlValidationResult validateSparql(String input, Collection<VersionIri> profiles) {
        return validateAutoDetect(Objects.requireNonNull(input, "input"),
                new ValidationScope.ProfileListScope(profiles));
    }

    /** Validates with per-graph profile mapping; see {@link #validateSparql(String)}. */
    public SparqlValidationResult validateSparql(
            String input, Map<Node, Collection<VersionIri>> namedGraphsToProfiles) {
        return validateAutoDetect(Objects.requireNonNull(input, "input"),
                new ValidationScope.NamedGraphProfileScope(namedGraphsToProfiles));
    }

    private SparqlValidationResult validateAutoDetect(String input, ValidationScope scope) {
        DefaultPrefixes.InjectionResult inj = DefaultPrefixes.inject(input, defaultPrefixes);
        SparqlValidationResult raw = validateAutoDetectRaw(inj.text(), scope);
        return inj.injectedLineCount() > 0
                ? adjustLineNumbers(raw, input, inj.injectedLineCount())
                : raw;
    }

    private SparqlValidationResult validateAutoDetectRaw(String input, ValidationScope scope) {
        // Try query first; fall back to update if the query parse fails. Parse exactly once here,
        // using the same base URI the analyzer uses, and hand the parsed Query to the validator so
        // the text is not parsed a second time and relative-IRI resolution is identical to the
        // validation step.
        try {
            Query query = QueryFactory.create(input, SparqlQueryAnalyzer.RELATIVE_IRI_BASE);
            return validator.validate(query, input, scope);
        } catch (QueryException ignored) {
            SparqlValidationResult updateResult = validator.validateUpdate(input, scope);
            boolean updateAlsoFailed = updateResult.annotations().stream()
                    .anyMatch(a -> a.code() == SparqlValidationCode.SYNTAX_ERROR);
            if (!updateAlsoFailed) return updateResult;

            // Both parsers failed. Try splitting on ';' separators (multi-query file).
            List<QuerySegment> segments = splitQuerySegments(input);
            if (segments.size() > 1) {
                return validateQuerySegments(segments, input, scope);
            }
            // Single segment that didn't parse — return the query error (more informative).
            return validator.validate(input, scope);
        }
    }

    /**
     * Shifts all annotation line numbers (and embedded line references in messages) by
     * {@code -subtractLines} so they point into {@code originalInput} rather than into
     * the prefix-injected augmented text.  The result's {@link SparqlValidationResult#query()}
     * is also replaced with {@code originalInput}.
     */
    private static SparqlValidationResult adjustLineNumbers(
            SparqlValidationResult raw, String originalInput, int subtractLines) {
        var adjusted = new ArrayList<SparqlValidationAnnotation>(raw.annotations().size());
        for (var a : raw.annotations()) {
            Integer newLine = a.line() != null ? Math.max(1, a.line() - subtractLines) : null;
            String  newMsg  = DefaultPrefixes.adjustMessageLines(a.message(), subtractLines);
            adjusted.add(new SparqlValidationAnnotation(
                    a.severity(), newLine, a.column(),
                    newMsg, a.code(), a.term(),
                    a.selectedProfiles(), a.foundInOtherProfiles(), a.graph()));
        }
        return new SparqlValidationResult(originalInput, raw.queryPlan(), List.copyOf(adjusted));
    }

    /** One ';'-separated SPARQL query within a multi-query file. */
    private record QuerySegment(String text, int firstContentLine0) {}

    /**
     * Splits a multi-query file (queries separated by bare {@code ;} lines or {@code };} lines)
     * into individual query segments. PREFIX declarations from the start of the file are
     * prepended to every segment. Returns the unsplit input as a single segment when no
     * separator is found.
     */
    private static List<QuerySegment> splitQuerySegments(String input) {
        String[] inputLines = input.split("\n", -1);
        List<String> prefixLines = new ArrayList<>();
        List<QuerySegment> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        // 0-based original line where the current segment's own (non-prefix) content begins.
        int firstContentLine0 = 0;

        for (int i = 0; i < inputLines.length; i++) {
            String line = inputLines[i];
            String trimmed = line.trim();

            if (trimmed.toLowerCase().startsWith("prefix ")) {
                prefixLines.add(line);
                current.add(line);
                firstContentLine0 = i + 1;
            } else if (trimmed.equals(";")) {
                if (hasNonPrefixContent(current)) {
                    segments.add(new QuerySegment(String.join("\n", current), firstContentLine0));
                }
                current = new ArrayList<>(prefixLines);
                firstContentLine0 = i + 1;
            } else if (trimmed.equals("};")) {
                // '}' closes the query; ';' is the separator — keep '}' in this segment.
                current.add(line.substring(0, line.lastIndexOf(';')));
                segments.add(new QuerySegment(String.join("\n", current), firstContentLine0));
                current = new ArrayList<>(prefixLines);
                firstContentLine0 = i + 1;
            } else {
                current.add(line);
            }
        }
        if (hasNonPrefixContent(current)) {
            segments.add(new QuerySegment(String.join("\n", current), firstContentLine0));
        }
        return segments;
    }

    private static boolean hasNonPrefixContent(List<String> lines) {
        return lines.stream().anyMatch(
                l -> !l.trim().isEmpty() && !l.trim().toLowerCase().startsWith("prefix "));
    }

    /**
     * Validates each segment independently and merges the annotations, adjusting line numbers
     * so they point into the original (multi-query) file rather than the individual segment.
     * Falls back to a single whole-file validation if any segment fails to parse as a query.
     */
    private SparqlValidationResult validateQuerySegments(
            List<QuerySegment> segments, String original, ValidationScope scope) {
        int prefixLineCount = (int) Arrays.stream(original.split("\n", -1))
                .filter(l -> l.trim().toLowerCase().startsWith("prefix "))
                .count();

        var allAnnotations = new ArrayList<SparqlValidationAnnotation>();
        for (QuerySegment seg : segments) {
            try {
                QueryFactory.create(seg.text());
            } catch (QueryException e) {
                return validator.validate(original, scope);
            }
            // lineOffset: how many lines to add to convert a segment line number (1-based)
            // to the original file line number. Lines within the PREFIX block need no
            // adjustment (they're the same at the top of the original), but in practice
            // schema-validation annotations always land in the query body, not on PREFIX lines.
            int lineOffset = seg.firstContentLine0() - prefixLineCount;
            SparqlValidationResult r = validator.validate(seg.text(), scope);
            for (SparqlValidationAnnotation a : r.annotations()) {
                if (a.line() != null && lineOffset != 0) {
                    allAnnotations.add(new SparqlValidationAnnotation(
                            a.severity(), a.line() + lineOffset, a.column(),
                            a.message(), a.code(), a.term(),
                            a.selectedProfiles(), a.foundInOtherProfiles(), a.graph()));
                } else {
                    allAnnotations.add(a);
                }
            }
        }
        return new SparqlValidationResult(original, null, allAnnotations);
    }

    // ---- getProfileDependencies (query) ----------------------------------------------------

    public Collection<VersionIri> getProfileDependencies(String query) throws InvalidQueryException {
        return profileDeps(analyze(query), new ValidationScope.AllProfilesScope());
    }

    public Collection<VersionIri> getProfileDependencies(String query, Collection<VersionIri> profiles)
            throws InvalidQueryException {
        return profileDeps(analyze(query), new ValidationScope.ProfileListScope(profiles));
    }

    public Collection<VersionIri> getProfileDependencies(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            throws InvalidQueryException {
        return profileDeps(analyze(query), new ValidationScope.NamedGraphProfileScope(namedGraphsToProfiles));
    }

    // ---- getProfileDependencies (update) ---------------------------------------------------

    public Collection<VersionIri> getUpdateProfileDependencies(String updateText)
            throws InvalidQueryException {
        return updateProfileDeps(analyzeUpdate(updateText), new ValidationScope.AllProfilesScope());
    }

    public Collection<VersionIri> getUpdateProfileDependencies(
            String updateText, Collection<VersionIri> profiles) throws InvalidQueryException {
        return updateProfileDeps(analyzeUpdate(updateText), new ValidationScope.ProfileListScope(profiles));
    }

    // ---- getGraphDependencies --------------------------------------------------------------

    public Collection<Node> getGraphDependencies(String query) throws InvalidQueryException {
        return graphDeps(analyze(query).graphs());
    }

    /**
     * Returns the named graphs referenced in the query that appear as keys in
     * {@code namedGraphsToProfiles}. Use this to find which of your known graphs a query
     * depends on, so you can re-execute it when one of those graphs is updated.
     */
    public Collection<Node> getGraphDependencies(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            throws InvalidQueryException {
        var refs = analyze(query).graphs();
        var out = new LinkedHashSet<Node>();
        for (GraphReference gr : refs) {
            if (namedGraphsToProfiles.containsKey(gr.graph())) out.add(gr.graph());
        }
        return out;
    }

    public Collection<Node> getUpdateGraphDependencies(String updateText) throws InvalidQueryException {
        return graphDeps(analyzeUpdate(updateText).graphs());
    }

    // ---- getPropertyDependencies -----------------------------------------------------------

    public Collection<Node> getPropertyDependencies(String query) throws InvalidQueryException {
        return propertyDeps(analyze(query).properties());
    }

    /** Properties used in the query that exist in at least one of the given profiles. */
    public Collection<Node> getPropertyDependencies(String query, Collection<VersionIri> profiles)
            throws InvalidQueryException {
        return propertyDepsScoped(analyze(query), new ValidationScope.ProfileListScope(profiles));
    }

    /**
     * Properties used in the query that exist in the profiles mapped to their enclosing graph.
     * Properties in default-graph context are validated against the union of all configured
     * profiles in the map.
     */
    public Collection<Node> getPropertyDependencies(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            throws InvalidQueryException {
        return propertyDepsScoped(analyze(query),
                new ValidationScope.NamedGraphProfileScope(namedGraphsToProfiles));
    }

    public Collection<Node> getUpdatePropertyDependencies(String updateText)
            throws InvalidQueryException {
        return propertyDeps(analyzeUpdate(updateText).properties());
    }

    // ---- getClassDependencies --------------------------------------------------------------

    public Collection<Node> getClassDependencies(String query) throws InvalidQueryException {
        return classDeps(analyze(query).classes());
    }

    /** Classes used in the query that exist in at least one of the given profiles. */
    public Collection<Node> getClassDependencies(String query, Collection<VersionIri> profiles)
            throws InvalidQueryException {
        return classDepsScoped(analyze(query), new ValidationScope.ProfileListScope(profiles));
    }

    /**
     * Classes used in the query that exist in the profiles mapped to their enclosing graph.
     * Classes in default-graph context are validated against the union of all configured
     * profiles in the map.
     */
    public Collection<Node> getClassDependencies(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            throws InvalidQueryException {
        return classDepsScoped(analyze(query),
                new ValidationScope.NamedGraphProfileScope(namedGraphsToProfiles));
    }

    public Collection<Node> getUpdateClassDependencies(String updateText) throws InvalidQueryException {
        return classDeps(analyzeUpdate(updateText).classes());
    }

    // ---- internals -------------------------------------------------------------------------

    private SparqlQueryAnalysis analyze(String query) throws InvalidQueryException {
        String augmented = DefaultPrefixes.inject(
                Objects.requireNonNull(query, "query"), defaultPrefixes).text();
        return validator.analyze(augmented);
    }

    private SparqlUpdateAnalysis analyzeUpdate(String updateText) throws InvalidQueryException {
        String augmented = DefaultPrefixes.inject(
                Objects.requireNonNull(updateText, "updateText"), defaultPrefixes).text();
        return validator.analyzeUpdate(augmented);
    }

    private static Collection<Node> propertyDeps(List<PropertyReference> props) {
        var out = new LinkedHashSet<Node>();
        props.forEach(p -> out.add(p.propertyNode()));
        return out;
    }

    private Collection<Node> propertyDepsScoped(SparqlQueryAnalysis a, ValidationScope scope) {
        var out = new LinkedHashSet<Node>();
        for (PropertyReference p : a.properties()) {
            Collection<VersionIri> selected = validator.scopeProfiles(scope, p.graph());
            if (validator.schemaIndex().propertyExists(p.propertyNode(), selected)) {
                out.add(p.propertyNode());
            }
        }
        return out;
    }

    private static Collection<Node> classDeps(List<ClassReference> classes) {
        var out = new LinkedHashSet<Node>();
        classes.forEach(c -> out.add(c.classNode()));
        return out;
    }

    private Collection<Node> classDepsScoped(SparqlQueryAnalysis a, ValidationScope scope) {
        var out = new LinkedHashSet<Node>();
        for (ClassReference c : a.classes()) {
            Collection<VersionIri> selected = validator.scopeProfiles(scope, c.graph());
            if (validator.schemaIndex().classExists(c.classNode(), selected)) {
                out.add(c.classNode());
            }
        }
        return out;
    }

    private static Collection<Node> graphDeps(List<GraphReference> refs) {
        var out = new LinkedHashSet<Node>();
        for (GraphReference gr : refs) out.add(gr.graph());
        return out;
    }

    /**
     * Profiles needed by the query: every profile that declares at least one class or property
     * used by the query (restricted to the given scope).
     */
    private Collection<VersionIri> profileDeps(SparqlQueryAnalysis a, ValidationScope scope) {
        return collectProfileDeps(a.classes(), a.properties(), scope);
    }

    private Collection<VersionIri> updateProfileDeps(SparqlUpdateAnalysis a, ValidationScope scope) {
        return collectProfileDeps(a.classes(), a.properties(), scope);
    }

    private Collection<VersionIri> collectProfileDeps(
            List<ClassReference> classes, List<PropertyReference> properties, ValidationScope scope) {
        var out = new LinkedHashSet<VersionIri>();
        for (var c : classes) {
            Collection<VersionIri> selected = validator.scopeProfiles(scope, c.graph());
            for (VersionIri v : validator.schemaIndex().findClass(c.classNode())) {
                if (selected.contains(v)) out.add(v);
            }
        }
        for (var p : properties) {
            Collection<VersionIri> selected = validator.scopeProfiles(scope, p.graph());
            for (VersionIri v : validator.schemaIndex().findProperty(p.propertyNode())) {
                if (selected.contains(v)) out.add(v);
            }
        }
        return out;
    }

    // ========================================================================================
    // SHACL validation
    // ========================================================================================

    /**
     * Infers the profile scope most relevant to {@code shapesGraph} by examining which CIM terms
     * it references in structural shape positions ({@code sh:targetClass}, {@code sh:class},
     * {@code sh:path}) and in embedded SPARQL fragments ({@code sh:select}, {@code sh:ask},
     * {@code sh:construct}).
     *
     * <p>For each recognised CIM IRI the method looks up the declaring profiles in the schema
     * index and unions the results. This produces a tight scope that matches the profiles the
     * shapes file was actually written against — without any manual configuration.</p>
     *
     * <p>Falls back to all registered profiles when no recognisable CIM terms are found (e.g.
     * an empty graph or a shapes graph whose terms are all unknown), preserving the permissive
     * all-profiles behavior as a safe default.</p>
     */
    public Collection<VersionIri> inferProfileScope(Graph shapesGraph) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");
        Collection<VersionIri> deps = getShaclProfileDependencies(shapesGraph);
        return deps.isEmpty() ? schemaIndex().getAllProfiles() : deps;
    }

    /**
     * Validates {@code shapesGraph} after automatically inferring the profile scope from the
     * CIM terms it references. Equivalent to calling
     * {@link #inferProfileScope(Graph)} then {@link #validateShacl(Graph, Collection)}.
     *
     * <p>This is the recommended no-configuration overload for CGMES shapes files. For shapes
     * that span multiple known profiles, all relevant profiles are included in the scope
     * automatically. Fall-back to all-profiles applies when no recognisable CIM terms are
     * found.</p>
     */
    public ShaclValidationResult validateShacl(Graph shapesGraph) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");
        // Note: inferProfileScope internally calls getShaclProfileDependencies, which extracts
        // SPARQL fragments. validateShacl(Graph, Collection) then extracts them again for
        // validation. The double extraction is intentional for simplicity; the cost is small
        // relative to validation itself.
        return validateShacl(shapesGraph, inferProfileScope(shapesGraph));
    }

    /**
     * Validate every SPARQL fragment embedded in {@code shapesGraph} against the supplied
     * profile list. ENTSO-E shapes don't use named graphs, so a single profile-list scope
     * applies to every fragment found in the shapes graph.
     */
    public ShaclValidationResult validateShacl(Graph shapesGraph, Collection<VersionIri> profiles) {
        return validateShacl(shapesGraph, new ValidationScope.ProfileListScope(profiles));
    }

    private ShaclValidationResult validateShacl(Graph shapesGraph, ValidationScope scope) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");

        // Shape-structure analysis: sh:targetClass, sh:class, sh:path.
        Collection<VersionIri> scopeProfiles = validator.scopeProfiles(scope, null);
        var shapeAnnotations = shaclAnalyzer.analyze(shapesGraph, scopeProfiles);

        // Embedded-SPARQL analysis: sh:select, sh:ask, sh:construct.
        // For each query, pass $this → sh:targetClass as a subject-type hint so that
        // domain/range checks fire correctly even when $this has no explicit rdf:type in the query.
        var embeddedResults = new ArrayList<ShaclEmbeddedQueryResult>();
        for (EmbeddedSparql q : shaclExtractor.extract(shapesGraph)) {
            Map<Node, Set<Node>> hints = q.targetClasses().isEmpty()
                    ? Map.of()
                    : Map.of(org.apache.jena.sparql.core.Var.alloc("this"),
                             q.targetClasses());
            var r = validator.validate(renderedQueryWithPath(q), scope, hints);
            r = suppressImpliedType(r);
            embeddedResults.add(new ShaclEmbeddedQueryResult(q, r));
        }

        return new ShaclValidationResult(shapeAnnotations, embeddedResults);
    }

    /**
     * Removes {@link SparqlValidationCode#QUERY_IMPLIED_TYPE} annotations from an embedded-SPARQL
     * result before it is stored in the SHACL validation report.
     *
     * <p>In a plain SPARQL editor, {@code QUERY_IMPLIED_TYPE} (INFO) is useful: it tells the
     * author that variable {@code ?x} carries an implicit type due to a property's
     * {@code rdfs:domain}. Inside a SHACL embedded constraint, however, the variables are
     * transient bindings, not entities the query author is expected to annotate with
     * {@code rdf:type}. Emitting the annotation here would be noise that masks real
     * issues.</p>
     */
    private static SparqlValidationResult suppressImpliedType(SparqlValidationResult r) {
        var filtered = r.annotations().stream()
                .filter(a -> a.code() != SparqlValidationCode.QUERY_IMPLIED_TYPE)
                .toList();
        return filtered.size() == r.annotations().size()
                ? r
                : new SparqlValidationResult(r.query(), r.queryPlan(), filtered);
    }

    /**
     * Returns the rendered query for {@code q} with the SHACL {@code $PATH} / {@code ?PATH}
     * variable substituted by the first simple-URI {@code sh:path} collected from the enclosing
     * property shape.
     *
     * <p>SHACL embedded constraints frequently use {@code $this $PATH ?value} where
     * {@code $PATH} is a runtime placeholder that a SHACL processor replaces with the
     * enclosing property shape's {@code sh:path}. For static analysis we substitute the
     * known URI directly so the validator can check the concrete property rather than
     * emitting an {@code UNSUPPORTED_DYNAMIC_PROPERTY} warning.</p>
     *
     * <p>If no suitable {@code sh:path} is available, or if the query does not reference
     * {@code $PATH} / {@code ?PATH}, the unmodified rendered query is returned.</p>
     */
    private static String renderedQueryWithPath(EmbeddedSparql q) {
        String rendered = q.renderedQuery();
        if (q.shPaths().isEmpty()) return rendered;
        if (!rendered.contains("$PATH") && !rendered.contains("?PATH")) return rendered;
        Node pathNode = q.shPaths().iterator().next();
        if (!pathNode.isURI()) return rendered;
        // Substitute only the whole-token $PATH / ?PATH placeholder — a variable such as
        // $PATHOLOGY or ?PATH2 must be left untouched (the negative look-ahead enforces this).
        // Matcher.quoteReplacement guards against a '$' or '\' inside the IRI being treated as
        // a regex back-reference in the replacement string.
        String uri = "<" + pathNode.getURI() + ">";
        return SH_PATH_VARIABLE.matcher(rendered).replaceAll(Matcher.quoteReplacement(uri));
    }

    /** Matches the SHACL {@code $PATH} / {@code ?PATH} placeholder as a complete variable token. */
    private static final Pattern SH_PATH_VARIABLE = Pattern.compile("[$?]PATH(?![A-Za-z0-9_])");

    /**
     * Aggregate class IRIs used by all SPARQL fragments embedded in the shapes graph.
     * Unparseable fragments are skipped silently — use {@link #validateShacl(Graph)} for
     * diagnostics.
     */
    public Collection<Node> getShaclClassDependencies(Graph shapesGraph) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");
        // Shape-structure terms (sh:targetClass, sh:class) plus embedded SPARQL references.
        var out = new LinkedHashSet<>(shaclAnalyzer.extractClassDependencies(shapesGraph));
        for (EmbeddedSparql q : shaclExtractor.extract(shapesGraph)) {
            try {
                var a = validator.analyze(renderedQueryWithPath(q));
                a.classes().forEach(c -> out.add(c.classNode()));
            } catch (InvalidQueryException ignored) {
                /* skip unparseable fragment */
            }
        }
        return out;
    }

    /** Aggregate property IRIs used by all SPARQL fragments embedded in the shapes graph. */
    public Collection<Node> getShaclPropertyDependencies(Graph shapesGraph) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");
        // Shape-structure terms (sh:path) plus embedded SPARQL references.
        var out = new LinkedHashSet<>(shaclAnalyzer.extractPropertyDependencies(shapesGraph));
        for (EmbeddedSparql q : shaclExtractor.extract(shapesGraph)) {
            try {
                var a = validator.analyze(renderedQueryWithPath(q));
                a.properties().forEach(p -> out.add(p.propertyNode()));
            } catch (InvalidQueryException ignored) {
                /* skip unparseable fragment */
            }
        }
        return out;
    }

    /**
     * Profiles needed by the shapes graph: every profile that declares at least one class or
     * property used by an embedded SPARQL fragment. Considers <em>every</em> known profile,
     * regardless of which ones the caller intends to validate against — this is the
     * "what does this shape need?" question, not the "is this shape valid against X?" one.
     */
    public Collection<VersionIri> getShaclProfileDependencies(Graph shapesGraph) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");
        return shaclProfileDeps(shapesGraph, null);
    }

    /**
     * Profiles needed by the shapes graph, restricted to the supplied profile list. Useful when
     * checking whether a shape only needs a subset of profiles.
     */
    public Collection<VersionIri> getShaclProfileDependencies(
            Graph shapesGraph, Collection<VersionIri> profiles) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");
        return shaclProfileDeps(shapesGraph, profiles);
    }

    private Collection<VersionIri> shaclProfileDeps(Graph shapesGraph, Collection<VersionIri> restrict) {
        var out = new LinkedHashSet<VersionIri>();

        // Shape-structure terms: sh:targetClass, sh:class, sh:path.
        for (Node cls : shaclAnalyzer.extractClassDependencies(shapesGraph)) {
            for (VersionIri v : validator.schemaIndex().findClass(cls)) {
                if (restrict == null || restrict.contains(v)) out.add(v);
            }
        }
        for (Node prop : shaclAnalyzer.extractPropertyDependencies(shapesGraph)) {
            for (VersionIri v : validator.schemaIndex().findProperty(prop)) {
                if (restrict == null || restrict.contains(v)) out.add(v);
            }
        }

        // Embedded SPARQL terms: sh:select, sh:ask, sh:construct.
        for (EmbeddedSparql q : shaclExtractor.extract(shapesGraph)) {
            try {
                var a = validator.analyze(renderedQueryWithPath(q));
                for (var c : a.classes()) {
                    for (VersionIri v : validator.schemaIndex().findClass(c.classNode())) {
                        if (restrict == null || restrict.contains(v)) out.add(v);
                    }
                }
                for (var p : a.properties()) {
                    for (VersionIri v : validator.schemaIndex().findProperty(p.propertyNode())) {
                        if (restrict == null || restrict.contains(v)) out.add(v);
                    }
                }
            } catch (InvalidQueryException ignored) {
                /* skip unparseable fragment */
            }
        }
        return out;
    }

    /** Expose the extractor for callers that want to introspect SHACL fragments themselves. */
    public List<EmbeddedSparql> extractShaclSparql(Graph shapesGraph) {
        Objects.requireNonNull(shapesGraph, "shapesGraph");
        return shaclExtractor.extract(shapesGraph);
    }

    /**
     * Builds a per-graph profile scope map from a raw {@code namedGraphs} config entry.
     *
     * <p>Relative keys (containing no {@code :}) are resolved against
     * {@link SparqlQueryAnalyzer#RELATIVE_IRI_BASE} so that short names like {@code "EQ"} in the
     * config match {@code <EQ>} in SPARQL queries.  Unknown or empty mappings are reported via
     * {@code warn}.</p>
     *
     * @param namedGraphs raw map from graph key → list of version IRI strings (from config)
     * @param index       schema index used to verify that each profile URI is known
     * @param warn        receives a formatted warning message for each unknown or empty mapping
     * @return an immutable, ordered graph→profile map; empty when {@code namedGraphs} is null or empty
     */
    public static Map<Node, Collection<VersionIri>> buildNamedGraphScope(
            Map<String, List<String>> namedGraphs,
            SchemaIndex index,
            Consumer<String> warn) {

        if (namedGraphs == null || namedGraphs.isEmpty()) return Map.of();

        var map = new LinkedHashMap<Node, Collection<VersionIri>>();
        for (var entry : namedGraphs.entrySet()) {
            String key = entry.getKey();
            Node graphNode = key.contains(":")
                    ? NodeFactory.createURI(key)
                    : NodeFactory.createURI(SparqlQueryAnalyzer.RELATIVE_IRI_BASE + key);

            var versionIris = new ArrayList<VersionIri>();
            for (String profileUri : entry.getValue()) {
                VersionIri vIri = VersionIri.of(profileUri);
                if (index.getAllProfiles().contains(vIri)) {
                    versionIris.add(vIri);
                } else {
                    warn.accept("namedGraph '" + key + "' references unknown profile '"
                            + profileUri + "' — profile will be skipped.");
                }
            }
            if (!versionIris.isEmpty()) {
                map.put(graphNode, versionIris);
            } else {
                warn.accept("namedGraph '" + key
                        + "' has no known profiles — graph will be excluded from scope.");
            }
        }
        return Map.copyOf(map);
    }
}
