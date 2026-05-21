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

import de.soptim.opencgmes.sparql.validation.analysis.GraphReference;
import de.soptim.opencgmes.sparql.validation.analysis.SparqlQueryAnalysis;
import de.soptim.opencgmes.sparql.validation.schema.SchemaIndex;
import de.soptim.opencgmes.sparql.validation.schema.ValidationScope;
import org.apache.jena.graph.Node;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

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

    public SparqlValidationApi(SchemaIndex schemaIndex) {
        this.validator = new SparqlQueryValidator(Objects.requireNonNull(schemaIndex, "schemaIndex"));
    }

    public SchemaIndex schemaIndex() {
        return validator.schemaIndex();
    }

    // ---- validateSparql overloads ----------------------------------------------------------

    public SparqlValidationResult validateSparql(String query) {
        return validator.validate(query, new ValidationScope.AllProfilesScope());
    }

    public SparqlValidationResult validateSparql(String query, Collection<VersionIri> profiles) {
        return validator.validate(query, new ValidationScope.ProfileListScope(profiles));
    }

    public SparqlValidationResult validateSparql(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles) {
        return validator.validate(query, new ValidationScope.NamedGraphProfileScope(namedGraphsToProfiles));
    }

    // ---- getProfileDependencies ------------------------------------------------------------

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

    // ---- getGraphDependencies --------------------------------------------------------------

    public Collection<Node> getGraphDependencies(String query) throws InvalidQueryException {
        return graphDeps(analyze(query));
    }

    public Collection<Node> getGraphDependencies(String query, Collection<VersionIri> profiles)
            throws InvalidQueryException {
        return graphDeps(analyze(query));
    }

    public Collection<Node> getGraphDependencies(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            throws InvalidQueryException {
        return graphDeps(analyze(query));
    }

    // ---- getPropertyDependencies -----------------------------------------------------------

    public Collection<Node> getPropertyDependencies(String query) throws InvalidQueryException {
        return propertyDeps(analyze(query));
    }

    public Collection<Node> getPropertyDependencies(String query, Collection<VersionIri> profiles)
            throws InvalidQueryException {
        return propertyDeps(analyze(query));
    }

    public Collection<Node> getPropertyDependencies(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            throws InvalidQueryException {
        return propertyDeps(analyze(query));
    }

    // ---- getClassDependencies --------------------------------------------------------------

    public Collection<Node> getClassDependencies(String query) throws InvalidQueryException {
        return classDeps(analyze(query));
    }

    public Collection<Node> getClassDependencies(String query, Collection<VersionIri> profiles)
            throws InvalidQueryException {
        return classDeps(analyze(query));
    }

    public Collection<Node> getClassDependencies(
            String query, Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            throws InvalidQueryException {
        return classDeps(analyze(query));
    }

    // ---- internals -------------------------------------------------------------------------

    private SparqlQueryAnalysis analyze(String query) throws InvalidQueryException {
        return validator.analyze(Objects.requireNonNull(query, "query"));
    }

    private static Collection<Node> propertyDeps(SparqlQueryAnalysis a) {
        var out = new LinkedHashSet<Node>();
        a.properties().forEach(p -> out.add(p.propertyNode()));
        return out;
    }

    private static Collection<Node> classDeps(SparqlQueryAnalysis a) {
        var out = new LinkedHashSet<Node>();
        a.classes().forEach(c -> out.add(c.classNode()));
        return out;
    }

    private static Collection<Node> graphDeps(SparqlQueryAnalysis a) {
        var out = new LinkedHashSet<Node>();
        for (GraphReference gr : a.graphs()) out.add(gr.graph());
        return out;
    }

    /**
     * Profiles that are actually needed by the query: every profile that declares at least one
     * class or property used by the query.
     *
     * <p>For {@link ValidationScope.NamedGraphProfileScope} we honour the per-graph mapping —
     * a term inside {@code GRAPH <g>} only "needs" profiles that are mapped to {@code <g>}.
     * For the other two scopes the result is the subset of declared schema profiles that
     * actually contribute a class or property used by the query.</p>
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
}
