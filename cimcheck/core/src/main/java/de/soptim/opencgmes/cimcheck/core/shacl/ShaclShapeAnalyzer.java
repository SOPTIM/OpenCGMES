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

import de.soptim.opencgmes.cimcheck.core.ExemptVocabulary;
import de.soptim.opencgmes.cimcheck.core.IriFormat;
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
import java.util.function.Predicate;

/**
 * Static analysis of SHACL shape structure against a CIM schema.
 *
 * <p>Validates the shape-level predicates that reference CIM terms directly — without touching
 * any embedded SPARQL (which is the job of {@link ShaclSparqlExtractor} +
 * {@link SparqlQueryValidator}):</p>
 *
 * <ul>
 *   <li>{@code sh:targetClass} — the focus-node class must exist in the schema.</li>
 *   <li>{@code sh:class} — the required value class must exist in the schema; and must not
 *       be used on a property whose {@code rdfs:range} is a literal datatype.</li>
 *   <li>{@code sh:path} — every URI segment of a property path (simple, sequence, inverse,
 *       alternative, zero/one/more) must be a known CIM property.</li>
 *   <li>{@code sh:nodeKind} — must be compatible with the property's {@code rdfs:range}.</li>
 *   <li>{@code sh:datatype} — must not be used on a property whose {@code rdfs:range} is a
 *       class (object property).</li>
 * </ul>
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
            props.stream().filter(Predicate.not(ExemptVocabulary::isExempt)).forEach(out::add);
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
                checkPathPropertyExistence(g, pathNode, scope, out);

                // 2. Range-compatibility checks (only for simple single-URI paths)
                if (pathNode.isURI()) {
                    checkNodeKind(g, shape, pathNode, scope, out);
                    checkDatatypeVsRange(g, shape, pathNode, scope, out);
                    checkClassVsRange(g, shape, pathNode, scope, out);
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

    private void checkDatatypeVsRange(
            Graph g, Node shape, Node prop,
            Collection<VersionIri> scope, List<SparqlValidationAnnotation> out) {

        Node datatypeNode = singleObject(g, shape, Shacl.DATATYPE);
        if (datatypeNode == null || !datatypeNode.isURI()) return;

        Set<Node> ranges = schemaIndex.rangesOf(prop, scope);
        if (ranges.isEmpty()) return;

        boolean allClasses = ranges.stream().allMatch(r -> r.isURI() && !isDatatypeRange(r.getURI()));
        if (!allClasses) return; // mixed or already a datatype range — permissive

        out.add(datatypeIncompatibleAnnotation(prop, datatypeNode, scope));
    }

    private void checkClassVsRange(
            Graph g, Node shape, Node prop,
            Collection<VersionIri> scope, List<SparqlValidationAnnotation> out) {

        Node classNode = singleObject(g, shape, Shacl.CLASS);
        if (classNode == null || !classNode.isURI()) return;

        Set<Node> ranges = schemaIndex.rangesOf(prop, scope);
        if (ranges.isEmpty()) return;

        boolean allDatatypes = ranges.stream().allMatch(r -> r.isURI() && isDatatypeRange(r.getURI()));
        if (!allDatatypes) return; // mixed or class range — permissive

        out.add(classIncompatibleAnnotation(prop, classNode, scope));
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
     * Recursively walks a SHACL property path, dispatching to one of two callbacks:
     * {@code onUri} for each leaf URI node, and {@code onAlternativeGroup} once per
     * {@code sh:alternativePath} group with all leaf URIs pre-collected.
     *
     * <p>Handles all SHACL path forms: simple URI, sequence (RDF list), inverse, alternative,
     * and repetition ({@code sh:zeroOrMorePath} etc.).</p>
     */
    private static void walkPath(Graph g, Node path,
            Consumer<Node> onUri, Consumer<List<Node>> onAlternativeGroup) {
        if (path.isURI()) { onUri.accept(path); return; }
        if (!path.isBlank()) return;

        Node firstEl = singleObject(g, path, RDF_FIRST);
        if (firstEl != null) {
            walkList(g, path, el -> walkPath(g, el, onUri, onAlternativeGroup));
            return;
        }
        Node inv = singleObject(g, path, Shacl.INVERSE_PATH);
        if (inv != null) { walkPath(g, inv, onUri, onAlternativeGroup); return; }

        Node alt = singleObject(g, path, Shacl.ALTERNATIVE_PATH);
        if (alt != null) {
            var group = new ArrayList<Node>();
            walkList(g, alt, el -> walkPath(g, el, group::add, group::addAll));
            onAlternativeGroup.accept(group);
            return;
        }
        for (Node pred : REPEAT_PATH_PREDICATES) {
            Node inner = singleObject(g, path, pred);
            if (inner != null) { walkPath(g, inner, onUri, onAlternativeGroup); return; }
        }
    }

    /**
     * Recursively walks a SHACL property path and emits {@code UNKNOWN_PROPERTY} annotations
     * for any URI segment not present in the schema.
     *
     * <p>For {@code sh:alternativePath}, the alternatives are treated as a group: an unknown
     * alternative is suppressed when at least one sibling in the same group is a known property
     * <em>with the same local name</em>. This handles the multi-namespace cross-version
     * compatibility pattern (e.g. {@code cim:SvStatus.ConductingEquipment |
     * <http://iec.ch/TC57/CIM100#SvStatus.ConductingEquipment> |
     * <https://cim.ucaiug.io/ns#SvStatus.ConductingEquipment>}) without silencing genuine
     * typos whose local names differ from every known alternative.</p>
     */
    private void checkPathPropertyExistence(
            Graph g, Node path, Collection<VersionIri> scope, List<SparqlValidationAnnotation> out) {
        walkPath(g, path,
                uri -> {
                    if (!ExemptVocabulary.isExempt(uri) && !schemaIndex.propertyExists(uri, scope)) {
                        out.add(propertyAnnotation(uri, scope, schemaIndex.findProperty(uri)));
                    }
                },
                group -> checkAlternativeGroup(group, scope, out));
    }

    /**
     * Checks existence for all alternatives collected from an {@code sh:alternativePath} group.
     *
     * <p>An unknown alternative is <em>suppressed</em> when the group contains at least one
     * known property sharing the same local name — the standard cross-version compatibility
     * pattern where the same property appears under multiple CIM namespace URIs. An unknown
     * alternative whose local name does not match any known sibling is still flagged (it is
     * most likely a typo).</p>
     */
    private void checkAlternativeGroup(
            List<Node> allProps, Collection<VersionIri> scope, List<SparqlValidationAnnotation> out) {
        var known   = new ArrayList<Node>();
        var unknown = new ArrayList<Node>();
        for (Node prop : allProps) {
            if (ExemptVocabulary.isExempt(prop)) continue;
            if (schemaIndex.propertyExists(prop, scope)) {
                known.add(prop);
            } else {
                unknown.add(prop);
            }
        }
        for (Node prop : unknown) {
            String localName = localName(prop.getURI());
            boolean hasKnownSibling = known.stream()
                    .anyMatch(k -> localName(k.getURI()).equals(localName));
            if (!hasKnownSibling) {
                out.add(propertyAnnotation(prop, scope, schemaIndex.findProperty(prop)));
            }
        }
    }

    /** Returns the local name of a URI (the part after the last {@code #} or {@code /}). */
    private static String localName(String uri) {
        int sep = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
        return sep >= 0 ? uri.substring(sep + 1) : uri;
    }

    /**
     * Recursively collects all URI property nodes out of a SHACL property path expression.
     */
    private static void extractPropertyUris(Graph g, Node path, List<Node> out) {
        walkPath(g, path, out::add, out::addAll);
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
            IriFormat.appendIris(msg, elsewhere);
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
            IriFormat.appendIris(msg, elsewhere);
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
                .append(IriFormat.shortIri(nodeKindNode.getURI()))
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

    private static SparqlValidationAnnotation datatypeIncompatibleAnnotation(
            Node prop, Node datatypeNode, Collection<VersionIri> scope) {

        var msg = new StringBuilder("sh:datatype <")
                .append(IriFormat.shortIri(datatypeNode.getURI()))
                .append("> expects literal values, but rdfs:range of <")
                .append(prop.getURI()).append("> is a class (object property) in ");
        appendScopeLabel(msg, scope);
        msg.append('.');
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.WARN,
                null, null,
                msg.toString(),
                SparqlValidationCode.DATATYPE_INCOMPATIBLE_WITH_RANGE,
                prop,
                List.copyOf(scope),
                List.of(),
                null);
    }

    private static SparqlValidationAnnotation classIncompatibleAnnotation(
            Node prop, Node classNode, Collection<VersionIri> scope) {

        var msg = new StringBuilder("sh:class <")
                .append(IriFormat.shortIri(classNode.getURI()))
                .append("> expects IRI values, but rdfs:range of <")
                .append(prop.getURI()).append("> is a literal datatype (datatype property) in ");
        appendScopeLabel(msg, scope);
        msg.append('.');
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.WARN,
                null, null,
                msg.toString(),
                SparqlValidationCode.CLASS_INCOMPATIBLE_WITH_RANGE,
                prop,
                List.copyOf(scope),
                List.of(),
                null);
    }

    private static void appendScopeLabel(StringBuilder msg, Collection<VersionIri> scope) {
        if (scope.isEmpty()) {
            msg.append("selected schema/profile scope (empty)");
        } else {
            msg.append("selected profile").append(scope.size() == 1 ? " " : "s ");
            IriFormat.appendIris(msg, scope);
        }
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
