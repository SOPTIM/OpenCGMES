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

package de.soptim.opencgmes.cimcheck.core.shacl;

import de.soptim.opencgmes.cimcheck.core.SparqlQueryValidator;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationSeverity;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.schema.SchemaIndex;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.vocabulary.RDF;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Static analysis of SHACL shape structure against a CIM schema.
 *
 * <p>Validates the shape-level predicates that reference CIM terms directly — without touching
 * any embedded SPARQL (which is the job of {@link ShaclSparqlExtractor} +
 * {@link SparqlQueryValidator}):</p>
 *
 * <ul>
 *   <li>{@code sh:targetClass} — the focus-node class must exist in the schema.</li>
 *   <li>{@code sh:class} — the required value class must exist in the schema.</li>
 *   <li>{@code sh:path} — every URI segment of a property path (simple, sequence, inverse,
 *       alternative, zero/one/more) must be a known CIM property.</li>
 * </ul>
 *
 * <p>{@code sh:datatype} is intentionally not checked here — datatype validation is already
 * covered by the embedded-SPARQL path and by the semantic range checks.</p>
 *
 * <p>The analyzer is stateless and thread-safe.</p>
 */
public final class ShaclShapeAnalyzer {

    private static final Node RDF_FIRST = RDF.first.asNode();
    private static final Node RDF_REST  = RDF.rest.asNode();
    private static final Node RDF_NIL   = RDF.nil.asNode();

    private static final List<Node> REPEAT_PATH_PREDICATES = List.of(
            Shacl.ZERO_OR_MORE_PATH,
            Shacl.ONE_OR_MORE_PATH,
            Shacl.ZERO_OR_ONE_PATH);

    private final SchemaIndex schemaIndex;

    public ShaclShapeAnalyzer(SchemaIndex schemaIndex) {
        this.schemaIndex = schemaIndex;
    }

    /**
     * Analyses {@code shapesGraph} and returns one annotation per structural problem found:
     * unknown CIM terms, nodeKind/range mismatches, and cardinality contradictions.
     */
    public List<SparqlValidationAnnotation> analyze(Graph shapesGraph, Collection<VersionIri> scope) {
        var out = new ArrayList<SparqlValidationAnnotation>();

        checkClassReferences(shapesGraph, Shacl.TARGET_CLASS, "sh:targetClass", scope, out);
        checkClassReferences(shapesGraph, Shacl.CLASS, "sh:class", scope, out);
        checkPropertyShapes(shapesGraph, scope, out);

        return List.copyOf(out);
    }

    // ---- Shape-structure dependency extraction (no scope / no validation) -----------------

    /**
     * Returns all class URI nodes referenced in shape-structural positions
     * ({@code sh:targetClass}, {@code sh:class}) — regardless of whether they exist in any
     * profile. Use this for dependency tracking without validation.
     */
    public Set<Node> extractClassDependencies(Graph shapesGraph) {
        var out = new LinkedHashSet<Node>();
        forEachObject(shapesGraph, Shacl.TARGET_CLASS, n -> { if (n.isURI()) out.add(n); });
        forEachObject(shapesGraph, Shacl.CLASS,        n -> { if (n.isURI()) out.add(n); });
        return out;
    }

    /**
     * Returns all property URI nodes referenced in {@code sh:path} expressions — regardless
     * of whether they exist in any profile. Use this for dependency tracking without validation.
     */
    public Set<Node> extractPropertyDependencies(Graph shapesGraph) {
        var out = new LinkedHashSet<Node>();
        forEachObject(shapesGraph, Shacl.PATH, pathNode -> {
            var props = new ArrayList<Node>();
            extractPropertyUris(shapesGraph, pathNode, props);
            out.addAll(props);
        });
        return out;
    }

    // ---- sh:targetClass / sh:class ---------------------------------------------------------

    private void checkClassReferences(
            Graph g, Node predicate, String predicateLabel,
            Collection<VersionIri> scope,
            List<SparqlValidationAnnotation> out) {

        forEachObject(g, predicate, cls -> {
            if (!cls.isURI()) return;
            if (schemaIndex.classExists(cls, scope)) return;
            List<VersionIri> elsewhere = schemaIndex.findClass(cls);
            out.add(classAnnotation(cls, predicateLabel, scope, elsewhere));
        });
    }

    // ---- per-property-shape checks (sh:path, sh:nodeKind, sh:minCount/sh:maxCount) ----------

    /**
     * Combined loop over every property shape (subject of {@code sh:path}).
     * Runs three checks per shape: property existence, nodeKind/range compatibility, cardinality.
     */
    private void checkPropertyShapes(
            Graph g, Collection<VersionIri> scope, List<SparqlValidationAnnotation> out) {

        var it = g.find(Node.ANY, Shacl.PATH, Node.ANY);
        try {
            while (it.hasNext()) {
                Triple t = it.next();
                Node shape    = t.getSubject();
                Node pathNode = t.getObject();

                // 1. Unknown property in path
                var props = new ArrayList<Node>();
                extractPropertyUris(g, pathNode, props);
                for (Node prop : props) {
                    if (schemaIndex.propertyExists(prop, scope)) continue;
                    out.add(propertyAnnotation(prop, scope, schemaIndex.findProperty(prop)));
                }

                // 2. sh:nodeKind vs rdfs:range (only for simple single-URI paths)
                if (pathNode.isURI()) {
                    checkNodeKind(g, shape, pathNode, scope, out);
                }

                // 3. sh:minCount / sh:maxCount contradiction
                checkCardinality(g, shape, pathNode, out);
            }
        } finally {
            if (it instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) {}
        }
    }

    private void checkNodeKind(
            Graph g, Node shape, Node prop,
            Collection<VersionIri> scope, List<SparqlValidationAnnotation> out) {

        Node nodeKindNode = singleObject(g, shape, Shacl.NODE_KIND);
        if (nodeKindNode == null || !nodeKindNode.isURI()) return;

        Set<Node> ranges = schemaIndex.rangesOf(prop, scope);
        if (ranges.isEmpty()) return; // schema is silent — permissive

        boolean allDatatypes = ranges.stream().allMatch(r -> r.isURI() && isDatatypeRange(r.getURI()));
        boolean allClasses   = ranges.stream().allMatch(r -> r.isURI() && !isDatatypeRange(r.getURI()));
        if (!allDatatypes && !allClasses) return; // mixed — skip

        String nk = nodeKindNode.getURI();
        boolean requiresNonLiteral = Shacl.IRI.getURI().equals(nk)
                || Shacl.BLANK_NODE.getURI().equals(nk)
                || Shacl.BLANK_NODE_OR_IRI.getURI().equals(nk);
        boolean requiresLiteral = Shacl.LITERAL.getURI().equals(nk);

        if (allDatatypes && requiresNonLiteral) {
            out.add(nodeKindAnnotation(prop, nodeKindNode,
                    "a literal (datatype property)", "a non-literal", scope));
        } else if (allClasses && requiresLiteral) {
            out.add(nodeKindAnnotation(prop, nodeKindNode,
                    "an IRI or blank node (object property)", "a literal", scope));
        }
    }

    private static void checkCardinality(
            Graph g, Node shape, Node pathNode, List<SparqlValidationAnnotation> out) {

        Node minNode = singleObject(g, shape, Shacl.MIN_COUNT);
        Node maxNode = singleObject(g, shape, Shacl.MAX_COUNT);
        if (minNode == null || maxNode == null) return;

        OptionalInt min = parseLiteralInt(minNode);
        OptionalInt max = parseLiteralInt(maxNode);
        if (min.isEmpty() || max.isEmpty()) return;

        if (min.getAsInt() > max.getAsInt()) {
            Node term = pathNode.isURI() ? pathNode : null;
            out.add(cardinalityAnnotation(min.getAsInt(), max.getAsInt(), term));
        }
    }

    private static OptionalInt parseLiteralInt(Node n) {
        if (!n.isLiteral()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(n.getLiteralLexicalForm()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static boolean isDatatypeRange(String iri) {
        return iri.startsWith("http://www.w3.org/2001/XMLSchema#")
                || iri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString");
    }

    /**
     * Recursively collects all URI property nodes out of a SHACL property path expression.
     *
     * <ul>
     *   <li>URI node → added directly.</li>
     *   <li>RDF list (sequence path) → each element is expanded recursively.</li>
     *   <li>Blank node with {@code sh:inversePath} → inner path expanded.</li>
     *   <li>Blank node with {@code sh:alternativePath} → list elements expanded.</li>
     *   <li>Blank node with repetition operators ({@code sh:zeroOrMorePath} etc.) → expanded.</li>
     * </ul>
     */
    private static void extractPropertyUris(Graph g, Node path, List<Node> out) {
        if (path.isURI()) {
            out.add(path);
            return;
        }
        if (!path.isBlank()) return;

        // RDF list (sequence path): distinguished by having rdf:first.
        Node firstEl = singleObject(g, path, RDF_FIRST);
        if (firstEl != null) {
            walkList(g, path, el -> extractPropertyUris(g, el, out));
            return;
        }

        // sh:inversePath
        Node inv = singleObject(g, path, Shacl.INVERSE_PATH);
        if (inv != null) {
            extractPropertyUris(g, inv, out);
            return;
        }

        // sh:alternativePath (its value is an RDF list)
        Node alt = singleObject(g, path, Shacl.ALTERNATIVE_PATH);
        if (alt != null) {
            walkList(g, alt, el -> extractPropertyUris(g, el, out));
            return;
        }

        // sh:zeroOrMorePath / sh:oneOrMorePath / sh:zeroOrOnePath
        for (Node pred : REPEAT_PATH_PREDICATES) {
            Node inner = singleObject(g, path, pred);
            if (inner != null) {
                extractPropertyUris(g, inner, out);
                return;
            }
        }
    }

    /** Walks an RDF list, calling {@code consumer} for each {@code rdf:first} value. */
    private static void walkList(Graph g, Node list, Consumer<Node> consumer) {
        Node cur = list;
        var visited = new HashSet<Node>();
        while (cur != null && cur.isBlank() && visited.add(cur)) {
            Node first = singleObject(g, cur, RDF_FIRST);
            if (first != null) consumer.accept(first);
            Node rest = singleObject(g, cur, RDF_REST);
            if (rest == null || RDF_NIL.equals(rest)) break;
            cur = rest.isBlank() ? rest : null;
        }
    }

    // ---- annotation builders ---------------------------------------------------------------

    private static SparqlValidationAnnotation classAnnotation(
            Node cls, String predicateLabel,
            Collection<VersionIri> scope, List<VersionIri> elsewhere) {

        var msg = new StringBuilder("Shape ")
                .append(predicateLabel).append(": class <").append(cls.getURI())
                .append("> does not exist in ");
        appendScopeLabel(msg, scope);
        msg.append('.');
        if (!elsewhere.isEmpty()) {
            msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
            appendIris(msg, elsewhere);
            msg.append('.');
        }
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.ERROR,
                null, null,
                msg.toString(),
                SparqlValidationCode.UNKNOWN_CLASS,
                cls,
                List.copyOf(scope),
                List.copyOf(elsewhere),
                null);
    }

    private static SparqlValidationAnnotation propertyAnnotation(
            Node prop, Collection<VersionIri> scope, List<VersionIri> elsewhere) {

        var msg = new StringBuilder("Shape sh:path: property <").append(prop.getURI())
                .append("> does not exist in ");
        appendScopeLabel(msg, scope);
        msg.append('.');
        if (!elsewhere.isEmpty()) {
            msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
            appendIris(msg, elsewhere);
            msg.append('.');
        }
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.ERROR,
                null, null,
                msg.toString(),
                SparqlValidationCode.UNKNOWN_PROPERTY,
                prop,
                List.copyOf(scope),
                List.copyOf(elsewhere),
                null);
    }

    private static SparqlValidationAnnotation nodeKindAnnotation(
            Node prop, Node nodeKindNode,
            String actualKind, String declaredKind, Collection<VersionIri> scope) {

        var msg = new StringBuilder("sh:nodeKind <")
                .append(shortIri(nodeKindNode.getURI()))
                .append("> declares value must be ").append(declaredKind)
                .append(", but rdfs:range of <").append(prop.getURI()).append("> is ")
                .append(actualKind).append(" in ");
        appendScopeLabel(msg, scope);
        msg.append('.');
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.WARN,
                null, null,
                msg.toString(),
                SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE,
                prop,
                List.copyOf(scope),
                List.of(),
                null);
    }

    private static SparqlValidationAnnotation cardinalityAnnotation(
            int min, int max, Node term) {

        String msg = "sh:minCount " + min + " exceeds sh:maxCount " + max
                + ": property shape can never be satisfied.";
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.ERROR,
                null, null,
                msg,
                SparqlValidationCode.INVALID_CARDINALITY,
                term,
                List.of(),
                List.of(),
                null);
    }

    private static void appendScopeLabel(StringBuilder msg, Collection<VersionIri> scope) {
        if (scope.isEmpty()) {
            msg.append("selected schema/profile scope (empty)");
        } else {
            msg.append("selected profile").append(scope.size() == 1 ? " " : "s ");
            appendIris(msg, scope);
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

    // ---- graph helpers ---------------------------------------------------------------------

    /** Calls {@code consumer} for every object of {@code (ANY, predicate, ?o)} in the graph. */
    private static void forEachObject(Graph g, Node predicate, Consumer<Node> consumer) {
        var it = g.find(Node.ANY, predicate, Node.ANY);
        try {
            while (it.hasNext()) consumer.accept(it.next().getObject());
        } finally {
            if (it instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) {}
        }
    }

    /** Returns the single object of {@code (subject, predicate, ?o)}, or {@code null}. */
    private static Node singleObject(Graph g, Node subject, Node predicate) {
        var it = g.find(subject, predicate, Node.ANY);
        try {
            return it.hasNext() ? it.next().getObject() : null;
        } finally {
            if (it instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) {}
        }
    }
}
