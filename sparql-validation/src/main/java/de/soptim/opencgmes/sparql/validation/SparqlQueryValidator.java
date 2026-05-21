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
import de.soptim.opencgmes.sparql.validation.analysis.PropertyReference;
import de.soptim.opencgmes.sparql.validation.analysis.SparqlQueryAnalysis;
import de.soptim.opencgmes.sparql.validation.analysis.SparqlQueryAnalyzer;
import de.soptim.opencgmes.sparql.validation.explain.QueryPlanFormatter;
import de.soptim.opencgmes.sparql.validation.schema.SchemaIndex;
import de.soptim.opencgmes.sparql.validation.schema.ValidationScope;
import de.soptim.opencgmes.sparql.validation.semantic.SemanticChecks;
import org.apache.jena.graph.Node;

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

    /** Runs analysis + validation against {@code scope}. */
    public SparqlValidationResult validate(String query, ValidationScope scope) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(scope, "scope");
        try {
            SparqlQueryAnalysis a = analyzer.analyze(query);
            String plan = QueryPlanFormatter.format(a.query(), a.algebra());
            List<SparqlValidationAnnotation> ann = validate(a, scope, query);
            return new SparqlValidationResult(query, plan, ann);
        } catch (InvalidQueryException e) {
            return new SparqlValidationResult(query, null, List.of(syntaxAnnotation(e)));
        }
    }

    /** Returns the underlying analysis; throws if the query is unparseable. */
    public SparqlQueryAnalysis analyze(String query) throws InvalidQueryException {
        return analyzer.analyze(query);
    }

    // ---- internal validation ---------------------------------------------------------------

    private List<SparqlValidationAnnotation> validate(
            SparqlQueryAnalysis a, ValidationScope scope, String original) {

        var annotations = new ArrayList<SparqlValidationAnnotation>();

        // 1. Graphs used by the query that are not configured (NamedGraphProfileScope only).
        if (scope instanceof ValidationScope.NamedGraphProfileScope ngs) {
            var configured = ngs.namedGraphsToProfiles().keySet();
            var seen = new LinkedHashSet<Node>();
            for (GraphReference gr : a.graphs()) {
                if (!seen.add(gr.graph())) continue;
                if (!configured.contains(gr.graph())) {
                    annotations.add(new SparqlValidationAnnotation(
                            SparqlValidationSeverity.WARN,
                            null, null,
                            "Graph <" + gr.graph().getURI() + "> is used by the query but no "
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
        if (a.dynamicPredicate()) {
            annotations.add(new SparqlValidationAnnotation(
                    SparqlValidationSeverity.WARN, null, null,
                    "Query contains triple(s) with a variable predicate; "
                            + "static property validation is skipped for those.",
                    SparqlValidationCode.UNSUPPORTED_DYNAMIC_PROPERTY,
                    null, scopeProfiles(scope, null), List.of(), null));
        }

        org.apache.jena.shared.PrefixMapping prefixes = a.query().getPrefixMapping();

        // 3. Classes.
        for (ClassReference c : a.classes()) {
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
        for (PropertyReference p : a.properties()) {
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

        // 5. Phase 3 — domain/range/datatype/path-chain semantics.
        annotations.addAll(SemanticChecks.run(
                a, schemaIndex, g -> scopeProfiles(scope, g), original, prefixes));

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
                if (graph != null) {
                    var hit = ngs.namedGraphsToProfiles().get(graph);
                    if (hit != null) yield hit;
                    // graph was used but not configured — no schema scope; nothing exists.
                    yield List.of();
                }
                // Default-graph context: union of all profiles in the map.
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
            msg.append(v.iri());
            first = false;
        }
        msg.append(']');
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
