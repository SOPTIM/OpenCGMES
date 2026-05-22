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

package de.soptim.opencgmes.sparql.validation.shacl;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Extracts SPARQL queries embedded in a SHACL shapes graph.
 *
 * <p>The extractor scans the graph for the three SHACL predicates that carry a SPARQL string —
 * {@link Shacl#SELECT}, {@link Shacl#ASK} and {@link Shacl#CONSTRUCT} — and resolves the
 * {@code sh:prefixes → sh:declare → (sh:prefix, sh:namespace)} chain into a prefix map per
 * query.</p>
 *
 * <p>Prefix resolution is best-effort: any {@code sh:declare} on a node reachable from a
 * {@code sh:prefixes} pointer is collected. If multiple declarations use the same prefix, the
 * last one found wins (this matches Jena's SHACL implementation).</p>
 *
 * <p>The extractor is stateless and thread-safe.</p>
 */
public final class ShaclSparqlExtractor {

    /**
     * SHACL predicates that link an owning shape to an inner container node (which holds
     * the embedded SPARQL). Used when resolving {@code sh:targetClass} for a container.
     */
    private static final List<Node> LINK_PREDICATES =
            List.of(Shacl.SPARQL, Shacl.TARGET, Shacl.VALIDATOR, Shacl.RULE);

    /** Extract every embedded SPARQL query from {@code shapesGraph}. */
    public List<EmbeddedSparql> extract(Graph shapesGraph) {
        Map<Node, Map<String, String>> prefixesByOwner = collectPrefixDeclarations(shapesGraph);
        Map<Node, Set<Node>> shapeToTargetClasses = collectShapeTargetClasses(shapesGraph);

        var out = new ArrayList<EmbeddedSparql>();
        collectQueries(shapesGraph, Shacl.SELECT,    EmbeddedSparql.Kind.SELECT,    prefixesByOwner, shapeToTargetClasses, out);
        collectQueries(shapesGraph, Shacl.ASK,       EmbeddedSparql.Kind.ASK,       prefixesByOwner, shapeToTargetClasses, out);
        collectQueries(shapesGraph, Shacl.CONSTRUCT, EmbeddedSparql.Kind.CONSTRUCT, prefixesByOwner, shapeToTargetClasses, out);
        return List.copyOf(out);
    }

    private static void collectQueries(
            Graph g, Node predicate, EmbeddedSparql.Kind kind,
            Map<Node, Map<String, String>> prefixesByOwner,
            Map<Node, Set<Node>> shapeToTargetClasses,
            List<EmbeddedSparql> out) {

        Iterator<Triple> it = g.find(Node.ANY, predicate, Node.ANY);
        try {
            while (it.hasNext()) {
                Triple t = it.next();
                Node o = t.getObject();
                if (!o.isLiteral()) continue; // SHACL says the query is a literal string
                String queryText = o.getLiteralLexicalForm();
                Node container = t.getSubject();

                Map<String, String> prefixes = resolvePrefixes(g, container, prefixesByOwner);
                Set<Node> targetClasses = resolveTargetClasses(g, container, shapeToTargetClasses);
                out.add(new EmbeddedSparql(container, predicate, kind, queryText, prefixes, targetClasses));
            }
        } finally {
            if (it instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) { /* nothing */ }
            }
        }
    }

    /**
     * For a given container node (the node that carries {@code sh:select/ask/construct}),
     * resolves the {@code sh:targetClass} values from the enclosing shape(s).
     *
     * <p>Resolution walks two hops backward from the container:</p>
     * <ol>
     *   <li>Direct link: {@code ?shape sh:sparql/sh:target/sh:validator/sh:rule ?container}
     *       — the shape's {@code sh:targetClass} applies directly.</li>
     *   <li>Via property shape: {@code ?shape sh:property ?propShape},
     *       {@code ?propShape sh:sparql/... ?container}
     *       — the owning shape's {@code sh:targetClass} is inherited.</li>
     *   <li>Via validator reference: {@code ?shape sh:validator ?container}
     *       when {@code ?container} is itself a named validator node.</li>
     * </ol>
     */
    private static Set<Node> resolveTargetClasses(
            Graph g, Node container, Map<Node, Set<Node>> shapeToTargetClasses) {
        var result = new LinkedHashSet<Node>();
        for (Node linkPred : LINK_PREDICATES) {
            Iterator<Triple> it = g.find(Node.ANY, linkPred, container);
            try {
                while (it.hasNext()) {
                    Node parent = it.next().getSubject();
                    // parent may be a shape directly
                    addClassesFor(parent, shapeToTargetClasses, result);
                    // parent may be a property shape — check its owning shapes
                    addGrandparentClasses(g, parent, Shacl.PROPERTY, shapeToTargetClasses, result);
                    // parent may be an intermediate node referenced via sh:validator
                    addGrandparentClasses(g, parent, Shacl.VALIDATOR, shapeToTargetClasses, result);
                }
            } finally {
                if (it instanceof AutoCloseable c) {
                    try { c.close(); } catch (Exception ignored) {}
                }
            }
        }
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    private static void addGrandparentClasses(
            Graph g, Node child, Node linkPred,
            Map<Node, Set<Node>> shapeToTargetClasses, Set<Node> result) {
        Iterator<Triple> it = g.find(Node.ANY, linkPred, child);
        try {
            while (it.hasNext()) addClassesFor(it.next().getSubject(), shapeToTargetClasses, result);
        } finally {
            if (it instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void addClassesFor(
            Node shape, Map<Node, Set<Node>> shapeToTargetClasses, Set<Node> result) {
        var classes = shapeToTargetClasses.get(shape);
        if (classes != null) result.addAll(classes);
    }

    /** Builds a map from every shape node to its {@code sh:targetClass} values. */
    private static Map<Node, Set<Node>> collectShapeTargetClasses(Graph g) {
        var out = new LinkedHashMap<Node, Set<Node>>();
        Iterator<Triple> it = g.find(Node.ANY, Shacl.TARGET_CLASS, Node.ANY);
        try {
            while (it.hasNext()) {
                Triple t = it.next();
                if (!t.getObject().isURI()) continue;
                out.computeIfAbsent(t.getSubject(), k -> new LinkedHashSet<>()).add(t.getObject());
            }
        } finally {
            if (it instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    /**
     * Resolve all {@code sh:prefixes} pointers reachable from {@code container} into a single
     * merged prefix map. Multiple pointers are merged; later declarations overwrite earlier.
     */
    private static Map<String, String> resolvePrefixes(
            Graph g, Node container, Map<Node, Map<String, String>> prefixesByOwner) {

        var merged = new TreeMap<String, String>();
        Iterator<Triple> it = g.find(container, Shacl.PREFIXES, Node.ANY);
        try {
            while (it.hasNext()) {
                Node owner = it.next().getObject();
                Map<String, String> m = prefixesByOwner.get(owner);
                if (m != null) merged.putAll(m);
            }
        } finally {
            if (it instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) { /* nothing */ }
            }
        }
        return merged;
    }

    /**
     * For every node {@code O} that has at least one {@code sh:declare} child in the graph, build
     * a prefix → namespace map by reading {@code sh:prefix} / {@code sh:namespace} on each
     * declared blank node.
     */
    private static Map<Node, Map<String, String>> collectPrefixDeclarations(Graph g) {
        var byOwner = new LinkedHashMap<Node, Map<String, String>>();
        Iterator<Triple> it = g.find(Node.ANY, Shacl.DECLARE, Node.ANY);
        try {
            while (it.hasNext()) {
                Triple t = it.next();
                Node owner = t.getSubject();
                Node decl  = t.getObject();
                String prefix = literalOf(g, decl, Shacl.PREFIX);
                String namespace = literalOf(g, decl, Shacl.NAMESPACE);
                if (prefix == null || namespace == null) continue;
                byOwner.computeIfAbsent(owner, k -> new TreeMap<>()).put(prefix, namespace);
            }
        } finally {
            if (it instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) { /* nothing */ }
            }
        }
        return byOwner;
    }

    private static String literalOf(Graph g, Node subject, Node predicate) {
        Iterator<Triple> it = g.find(subject, predicate, Node.ANY);
        try {
            if (!it.hasNext()) return null;
            Node o = it.next().getObject();
            return o.isLiteral() ? o.getLiteralLexicalForm() : null;
        } finally {
            if (it instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) { /* nothing */ }
            }
        }
    }
}
