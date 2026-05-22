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

package de.soptim.opencgmes.sparql.validation;

import de.soptim.opencgmes.sparql.validation.analysis.ClassReference;
import de.soptim.opencgmes.sparql.validation.analysis.GraphReference;
import de.soptim.opencgmes.sparql.validation.analysis.PathChainReference;
import de.soptim.opencgmes.sparql.validation.analysis.PropertyReference;
import de.soptim.opencgmes.sparql.validation.analysis.SparqlQueryAnalysis;
import de.soptim.opencgmes.sparql.validation.analysis.SparqlQueryAnalyzer;
import de.soptim.opencgmes.sparql.validation.analysis.SparqlUpdateAnalysis;
import de.soptim.opencgmes.sparql.validation.analysis.TriplePatternReference;
import de.soptim.opencgmes.sparql.validation.explain.QueryPlanFormatter;
import de.soptim.opencgmes.sparql.validation.schema.SchemaIndex;
import de.soptim.opencgmes.sparql.validation.schema.ValidationScope;
import de.soptim.opencgmes.sparql.validation.semantic.SemanticChecks;
import de.soptim.opencgmes.sparql.validation.semantic.SubjectTypeInference;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Static SPARQL validator: combines a {@link SparqlQueryAnalyzer} with a {@link SchemaIndex} to
 * produce a {@link SparqlValidationResult}.
 *
 * <p>The class is the engine behind {@link SparqlValidationApi}; tests and other modules may
 * use it directly to inspect intermediate analyses or to plug in a custom schema index.</p>
 */
public final class SparqlQueryValidator {

    private final SchemaIndex schemaIndex;
    private final SparqlQueryAnalyzer analyzer = new SparqlQueryAnalyzer();

    public SparqlQueryValidator(SchemaIndex schemaIndex) {
        this.schemaIndex = Objects.requireNonNull(schemaIndex, "schemaIndex");
    }

    public SchemaIndex schemaIndex() {
        return schemaIndex;
    }

    private static final Node RDF_TYPE = RDF.type.asNode();

    /** Runs analysis + validation against {@code scope}. */
    public SparqlValidationResult validate(String query, ValidationScope scope) {
        return validate(query, scope, Map.of());
    }

    /**
     * Variant that injects additional subject-type hints before the semantic checks.
     *
     * <p>Each entry {@code variable → {class1, class2, ...}} asserts that variable's type
     * for the purpose of domain/range checking. A hint for a subject that already has an
     * explicit {@code rdf:type} triple in the query is silently ignored — the query-declared
     * type takes precedence.</p>
     *
     * <p>The canonical use case is SHACL: the {@code $this} variable in an embedded SPARQL
     * constraint carries the type declared by the enclosing shape's {@code sh:targetClass}.</p>
     */
    public SparqlValidationResult validate(String query, ValidationScope scope,
                                           Map<Node, Set<Node>> subjectTypeHints) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(scope, "scope");
        try {
            SparqlQueryAnalysis a = analyzer.analyze(query);
            String plan = QueryPlanFormatter.format(a.query(), a.algebra());
            List<TriplePatternReference> triples =
                    augmentWithTypeHints(a.triples(), subjectTypeHints);
            List<SparqlValidationAnnotation> ann = validateReferences(
                    a.graphs(), a.classes(), a.properties(),
                    triples, a.pathChains(), a.dynamicPredicate(), a.dynamicClass(),
                    scope, a.query().getPrefixMapping(), query);
            return new SparqlValidationResult(query, plan, ann);
        } catch (InvalidQueryException e) {
            return new SparqlValidationResult(query, null, List.of(syntaxAnnotation(e)));
        }
    }

    /**
     * Returns the original triple list extended with synthetic {@code ?var rdf:type <cls>}
     * patterns for each hint entry whose subject variable has no already-declared type.
     * Returns the original list unchanged when no injection is needed.
     */
    private static List<TriplePatternReference> augmentWithTypeHints(
            List<TriplePatternReference> triples, Map<Node, Set<Node>> hints) {
        if (hints == null || hints.isEmpty()) return triples;
        Map<Node, Set<Node>> existing = SubjectTypeInference.infer(triples);
        var extra = new ArrayList<TriplePatternReference>();
        for (var entry : hints.entrySet()) {
            if (existing.containsKey(entry.getKey())) continue; // explicit type wins
            for (Node cls : entry.getValue()) {
                extra.add(new TriplePatternReference(
                        Triple.create(entry.getKey(), RDF_TYPE, cls), null));
            }
        }
        if (extra.isEmpty()) return triples;
        var augmented = new ArrayList<>(triples);
        augmented.addAll(extra);
        return Collections.unmodifiableList(augmented);
    }

    /**
     * Parses and validates a SPARQL Update request (one or more operations separated by {@code ;}).
     * The {@code queryPlan} in the result is {@code null} — update requests have no algebra plan.
     */
    public SparqlValidationResult validateUpdate(String updateText, ValidationScope scope) {
        Objects.requireNonNull(updateText, "updateText");
        Objects.requireNonNull(scope, "scope");
        try {
            SparqlUpdateAnalysis a = analyzer.analyzeUpdate(updateText);
            List<SparqlValidationAnnotation> ann = validateReferences(
                    a.graphs(), a.classes(), a.properties(),
                    a.triples(), a.pathChains(), a.dynamicPredicate(), a.dynamicClass(),
                    scope, a.updateRequest().getPrefixMapping(), updateText);
            return new SparqlValidationResult(updateText, null, ann);
        } catch (InvalidQueryException e) {
            return new SparqlValidationResult(updateText, null, List.of(syntaxAnnotation(e)));
        }
    }

    /** Returns the underlying analysis; throws if the query is unparseable. */
    public SparqlQueryAnalysis analyze(String query) throws InvalidQueryException {
        return analyzer.analyze(query);
    }

    /** Returns the underlying update analysis; throws if the update text is unparseable. */
    public SparqlUpdateAnalysis analyzeUpdate(String updateText) throws InvalidQueryException {
        return analyzer.analyzeUpdate(updateText);
    }

    // ---- internal validation ---------------------------------------------------------------

    /**
     * Core validation logic shared by both SPARQL query and SPARQL Update paths.
     */
    private List<SparqlValidationAnnotation> validateReferences(
            List<GraphReference> graphs,
            List<ClassReference> classes,
            List<PropertyReference> properties,
            List<TriplePatternReference> triples,
            List<PathChainReference> pathChains,
            boolean dynamicPredicate,
            boolean dynamicClass,
            ValidationScope scope,
            PrefixMapping prefixes,
            String original) {

        var annotations = new ArrayList<SparqlValidationAnnotation>();

        // 1. Graphs used but not configured (NamedGraphProfileScope only).
        if (scope instanceof ValidationScope.NamedGraphProfileScope ngs) {
            var configured = ngs.namedGraphsToProfiles().keySet();
            var seen = new LinkedHashSet<Node>();
            for (GraphReference gr : graphs) {
                if (!seen.add(gr.graph())) continue;
                if (!configured.contains(gr.graph())) {
                    annotations.add(new SparqlValidationAnnotation(
                            SparqlValidationSeverity.WARN,
                            null, null,
                            "Graph <" + gr.graph().getURI() + "> is used but no "
                                    + "schema profiles were configured for it.",
                            SparqlValidationCode.GRAPH_NOT_CONFIGURED,
                            gr.graph(),
                            List.of(),
                            List.of(),
                            gr.graph()));
                }
            }
        }

        // 2. Dynamic predicates/classes — informational warnings.
        if (dynamicPredicate) {
            annotations.add(new SparqlValidationAnnotation(
                    SparqlValidationSeverity.WARN, null, null,
                    "Query contains triple(s) with a variable predicate; "
                            + "static property validation is skipped for those.",
                    SparqlValidationCode.UNSUPPORTED_DYNAMIC_PROPERTY,
                    null, scopeProfiles(scope, null), List.of(), null));
        }
        if (dynamicClass) {
            annotations.add(new SparqlValidationAnnotation(
                    SparqlValidationSeverity.WARN, null, null,
                    "Query contains triple(s) with a variable rdf:type object; "
                            + "static class validation is skipped for those.",
                    SparqlValidationCode.UNSUPPORTED_DYNAMIC_PROPERTY,
                    null, scopeProfiles(scope, null), List.of(), null));
        }

        // 3. Classes.
        for (ClassReference c : classes) {
            Collection<VersionIri> selected = scopeProfiles(scope, c.graph());
            if (schemaIndex.classExists(c.classNode(), selected)) continue;
            List<VersionIri> elsewhere = schemaIndex.findClass(c.classNode());
            var elsewhereOutOfScope = subtract(elsewhere, selected);
            annotations.add(buildAnnotation(
                    SparqlValidationSeverity.ERROR,
                    SparqlValidationCode.UNKNOWN_CLASS,
                    c.classNode(),
                    c.graph(),
                    selected,
                    elsewhereOutOfScope,
                    original,
                    prefixes,
                    formatClassMessage(c.classNode(), c.graph(), selected, elsewhereOutOfScope)));
        }

        // 4. Properties.
        for (PropertyReference p : properties) {
            Collection<VersionIri> selected = scopeProfiles(scope, p.graph());
            if (schemaIndex.propertyExists(p.propertyNode(), selected)) continue;
            List<VersionIri> elsewhere = schemaIndex.findProperty(p.propertyNode());
            var elsewhereOutOfScope = subtract(elsewhere, selected);
            annotations.add(buildAnnotation(
                    SparqlValidationSeverity.ERROR,
                    SparqlValidationCode.UNKNOWN_PROPERTY,
                    p.propertyNode(),
                    p.graph(),
                    selected,
                    elsewhereOutOfScope,
                    original,
                    prefixes,
                    formatPropertyMessage(p.propertyNode(), p.graph(), selected, elsewhereOutOfScope)));
        }

        // 5. Semantic checks: domain/range/datatype/path-chain.
        annotations.addAll(SemanticChecks.run(
                triples, pathChains, schemaIndex, g -> scopeProfiles(scope, g), original, prefixes));

        return annotations;
    }

    // ---- scope resolution ------------------------------------------------------------------

    /**
     * Profiles in scope for a term encountered inside (or outside) a given graph context.
     *
     * <p>For {@link ValidationScope.NamedGraphProfileScope}: a non-null graph picks the profile
     * list mapped to that graph; a null graph falls back to the union of all configured profiles.</p>
     */
    Collection<VersionIri> scopeProfiles(ValidationScope scope, Node graph) {
        return switch (scope) {
            case ValidationScope.AllProfilesScope ignored -> schemaIndex.getAllProfiles();
            case ValidationScope.ProfileListScope l -> l.profiles();
            case ValidationScope.NamedGraphProfileScope ngs -> {
                if (graph != null && graph.isURI()) {
                    var hit = ngs.namedGraphsToProfiles().get(graph);
                    if (hit != null) yield hit;
                    // URI graph used but not configured — no schema scope; nothing exists.
                    yield List.of();
                }
                // Default-graph context or variable/blank graph: union of all configured profiles.
                var union = new LinkedHashSet<VersionIri>();
                for (var v : ngs.namedGraphsToProfiles().values()) union.addAll(v);
                yield List.copyOf(union);
            }
        };
    }

    // ---- message rendering -----------------------------------------------------------------

    private static String formatClassMessage(
            Node classNode, Node graph,
            Collection<VersionIri> selected, Collection<VersionIri> elsewhere) {
        var msg = new StringBuilder("Class <").append(classNode.getURI()).append("> does not exist in ");
        appendScopeLabel(msg, graph, selected);
        msg.append('.');
        if (!elsewhere.isEmpty()) {
            msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
            appendIris(msg, elsewhere);
            msg.append('.');
        }
        return msg.toString();
    }

    private static String formatPropertyMessage(
            Node propNode, Node graph,
            Collection<VersionIri> selected, Collection<VersionIri> elsewhere) {
        var msg = new StringBuilder("Property <").append(propNode.getURI()).append("> does not exist in ");
        appendScopeLabel(msg, graph, selected);
        msg.append('.');
        if (!elsewhere.isEmpty()) {
            msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
            appendIris(msg, elsewhere);
            msg.append('.');
        }
        return msg.toString();
    }

    private static void appendScopeLabel(StringBuilder msg, Node graph, Collection<VersionIri> selected) {
        if (graph != null) {
            msg.append("graph <").append(graph.getURI()).append("> / ");
        }
        if (selected.isEmpty()) {
            msg.append("selected schema/profile scope (empty)");
        } else {
            msg.append("selected profile").append(selected.size() == 1 ? " " : "s ");
            appendIris(msg, selected);
        }
    }

    private static void appendIris(StringBuilder msg, Collection<VersionIri> profiles) {
        msg.append('[');
        boolean first = true;
        for (VersionIri v : profiles) {
            if (!first) msg.append(", ");
            msg.append(shortIri(v.iri()));
            first = false;
        }
        msg.append(']');
    }

    private static String shortIri(String iri) {
        int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        if (last < 0) return iri;
        int prev = Math.max(iri.lastIndexOf('/', last - 1), iri.lastIndexOf('#', last - 1));
        return prev >= 0 ? iri.substring(prev + 1) : iri.substring(last + 1);
    }

    private static SparqlValidationAnnotation buildAnnotation(
            SparqlValidationSeverity severity,
            SparqlValidationCode code,
            Node term,
            Node graph,
            Collection<VersionIri> selected,
            Collection<VersionIri> elsewhere,
            String original,
            org.apache.jena.shared.PrefixMapping prefixes,
            String message) {
        var loc = SourceLocator.locate(original, term, prefixes);
        return new SparqlValidationAnnotation(
                severity,
                loc.line(),
                loc.column(),
                message,
                code,
                term,
                selected,
                elsewhere,
                graph);
    }

    private static List<VersionIri> subtract(List<VersionIri> a, Collection<VersionIri> b) {
        if (a.isEmpty()) return List.of();
        if (b == null || b.isEmpty()) return List.copyOf(a);
        var out = new ArrayList<VersionIri>(a.size());
        for (VersionIri v : a) if (!b.contains(v)) out.add(v);
        return Collections.unmodifiableList(out);
    }

    private static SparqlValidationAnnotation syntaxAnnotation(InvalidQueryException e) {
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.ERROR,
                e.line(), e.column(),
                e.getMessage(),
                SparqlValidationCode.SYNTAX_ERROR,
                null,
                List.of(),
                List.of(),
                null);
    }

    // Utility access used by SparqlValidationApi for dependency methods.
    Set<Node> intersect(Collection<Node> required, Map<Node, ?> registered) {
        var set = new LinkedHashSet<Node>();
        for (Node n : required) if (registered.containsKey(n)) set.add(n);
        return set;
    }
}
