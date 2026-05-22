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
import java.util.List;
import java.util.Map;
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

    /** Extract every embedded SPARQL query from {@code shapesGraph}. */
    public List<EmbeddedSparql> extract(Graph shapesGraph) {
        Map<Node, Map<String, String>> prefixesByOwner = collectPrefixDeclarations(shapesGraph);

        var out = new ArrayList<EmbeddedSparql>();
        collectQueries(shapesGraph, Shacl.SELECT,    EmbeddedSparql.Kind.SELECT,    prefixesByOwner, out);
        collectQueries(shapesGraph, Shacl.ASK,       EmbeddedSparql.Kind.ASK,       prefixesByOwner, out);
        collectQueries(shapesGraph, Shacl.CONSTRUCT, EmbeddedSparql.Kind.CONSTRUCT, prefixesByOwner, out);
        return List.copyOf(out);
    }

    private static void collectQueries(
            Graph g, Node predicate, EmbeddedSparql.Kind kind,
            Map<Node, Map<String, String>> prefixesByOwner,
            List<EmbeddedSparql> out) {

        Iterator<Triple> it = g.find(Node.ANY, predicate, Node.ANY);
        try {
            while (it.hasNext()) {
                Triple t = it.next();
                Node o = t.getObject();
                if (!o.isLiteral()) continue; // SHACL says the query is a literal string
                String queryText = o.getLiteralLexicalForm();

                Map<String, String> prefixes = resolvePrefixes(g, t.getSubject(), prefixesByOwner);
                out.add(new EmbeddedSparql(t.getSubject(), predicate, kind, queryText, prefixes));
            }
        } finally {
            // Graph.find iterators don't always require close(), but be safe.
            if (it instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) { /* nothing */ }
            }
        }
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
