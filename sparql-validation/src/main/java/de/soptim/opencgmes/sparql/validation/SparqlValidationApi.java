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
import de.soptim.opencgmes.sparql.validation.analysis.SparqlUpdateAnalysis;
import de.soptim.opencgmes.sparql.validation.schema.SchemaIndex;
import de.soptim.opencgmes.sparql.validation.schema.ValidationScope;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import de.soptim.opencgmes.sparql.validation.shacl.EmbeddedSparql;
import de.soptim.opencgmes.sparql.validation.shacl.ShaclEmbeddedQueryResult;
import de.soptim.opencgmes.sparql.validation.shacl.ShaclShapeAnalyzer;
import de.soptim.opencgmes.sparql.validation.shacl.ShaclSparqlExtractor;
import de.soptim.opencgmes.sparql.validation.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    public SparqlValidationApi(SchemaIndex schemaIndex) {
        this.validator = new SparqlQueryValidator(Objects.requireNonNull(schemaIndex, "schemaIndex"));
        this.shaclAnalyzer = new ShaclShapeAnalyzer(schemaIndex);
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
        // Try query first; fall back to update if the query parse fails.
        try {
            QueryFactory.create(input);         // lightweight probe — does not retain the Query
            return validator.validate(input, scope);
        } catch (QueryException ignored) {
            return validator.validateUpdate(input, scope);
        }
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
     * Returns named graphs referenced in the query. The {@code profiles} argument has no
     * filtering effect — graph references cannot be filtered by a profile list alone (no
     * graph→profile mapping is available). Use
     * {@link #getGraphDependencies(String, Map)} when you need to restrict to a known set
     * of graphs.
     */
    public Collection<Node> getGraphDependencies(String query, Collection<VersionIri> profiles)
            throws InvalidQueryException {
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
        return validator.analyze(Objects.requireNonNull(query, "query"));
    }

    private SparqlUpdateAnalysis analyzeUpdate(String updateText) throws InvalidQueryException {
        return validator.analyzeUpdate(Objects.requireNonNull(updateText, "updateText"));
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
        var out = new LinkedHashSet<VersionIri>();
        for (var c : a.classes()) {
            Collection<VersionIri> selected = validator.scopeProfiles(scope, c.graph());
            for (VersionIri v : validator.schemaIndex().findClass(c.classNode())) {
                if (selected.contains(v)) out.add(v);
            }
        }
        for (var p : a.properties()) {
            Collection<VersionIri> selected = validator.scopeProfiles(scope, p.graph());
            for (VersionIri v : validator.schemaIndex().findProperty(p.propertyNode())) {
                if (selected.contains(v)) out.add(v);
            }
        }
        return out;
    }

    private Collection<VersionIri> updateProfileDeps(SparqlUpdateAnalysis a, ValidationScope scope) {
        var out = new LinkedHashSet<VersionIri>();
        for (var c : a.classes()) {
            Collection<VersionIri> selected = validator.scopeProfiles(scope, c.graph());
            for (VersionIri v : validator.schemaIndex().findClass(c.classNode())) {
                if (selected.contains(v)) out.add(v);
            }
        }
        for (var p : a.properties()) {
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
     * Validate every SPARQL fragment embedded in {@code shapesGraph} against the entire schema
     * index. Convenience for {@code validateShacl(shapesGraph, schemaIndex().getAllProfiles())}.
     */
    public ShaclValidationResult validateShacl(Graph shapesGraph) {
        return validateShacl(shapesGraph, new ValidationScope.AllProfilesScope());
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
            var r = validator.validate(q.renderedQuery(), scope, hints);
            embeddedResults.add(new ShaclEmbeddedQueryResult(q, r));
        }

        return new ShaclValidationResult(shapeAnnotations, embeddedResults);
    }

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
                var a = validator.analyze(q.renderedQuery());
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
                var a = validator.analyze(q.renderedQuery());
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
                var a = validator.analyze(q.renderedQuery());
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
}
