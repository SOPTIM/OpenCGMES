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

package de.soptim.opencgmes.sparql.validation.semantic;

import de.soptim.opencgmes.sparql.validation.analysis.TriplePatternReference;
import org.apache.jena.graph.Node;
import org.apache.jena.vocabulary.RDF;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a map of <em>explicitly declared</em> {@code rdf:type}s per subject inside a query.
 *
 * <p>The key is either a SPARQL variable {@link Node} or a constant URI {@link Node} subject.
 * The value is the set of class URIs the query asserts that subject to be.</p>
 *
 * <p>Only triples whose predicate is {@code rdf:type} and whose object is a URI are used.
 * Variable type assertions like {@code ?s a ?cls} are ignored.</p>
 */
public final class SubjectTypeInference {

    private static final Node RDF_TYPE = RDF.type.asNode();

    private SubjectTypeInference() {}

    /**
     * Flat inference — collects every {@code rdf:type} triple regardless of scope.
     * Used only for deciding whether a subject-type hint should be injected (SHACL {@code $this}).
     *
     * @return immutable map subject → set of declared class URIs (never {@code null})
     */
    public static Map<Node, Set<Node>> infer(List<TriplePatternReference> triples) {
        var out = new LinkedHashMap<Node, Set<Node>>();
        for (TriplePatternReference t : triples) {
            Node p = t.triple().getPredicate();
            if (!RDF_TYPE.equals(p)) continue;
            Node o = t.triple().getObject();
            if (!o.isURI()) continue;
            out.computeIfAbsent(t.triple().getSubject(), k -> new LinkedHashSet<>()).add(o);
        }
        var immutable = new LinkedHashMap<Node, Set<Node>>(out.size());
        out.forEach((k, v) -> immutable.put(k, Set.copyOf(v)));
        return Map.copyOf(immutable);
    }

    /**
     * Scope-aware inference for domain/range checks.
     *
     * <p>Returns a two-level map: {@code scopeGroup → (subject → classes)}. Scope group 0 is the
     * root conjunctive clause; all other values identify UNION branches or OPTIONAL bodies.
     * Use {@link #typesFor} to resolve the effective type set for a specific triple.</p>
     */
    public static Map<Integer, Map<Node, Set<Node>>> inferScoped(List<TriplePatternReference> triples) {
        var out = new LinkedHashMap<Integer, Map<Node, Set<Node>>>();
        for (TriplePatternReference t : triples) {
            Node p = t.triple().getPredicate();
            if (!RDF_TYPE.equals(p)) continue;
            Node o = t.triple().getObject();
            if (!o.isURI()) continue;
            out.computeIfAbsent(t.scopeGroup(), k -> new LinkedHashMap<>())
               .computeIfAbsent(t.triple().getSubject(), k -> new LinkedHashSet<>())
               .add(o);
        }
        var immutable = new LinkedHashMap<Integer, Map<Node, Set<Node>>>(out.size());
        out.forEach((scope, typeMap) -> {
            var imm = new LinkedHashMap<Node, Set<Node>>(typeMap.size());
            typeMap.forEach((subj, types) -> imm.put(subj, Set.copyOf(types)));
            immutable.put(scope, Map.copyOf(imm));
        });
        return Map.copyOf(immutable);
    }

    /**
     * Returns the types visible for {@code subject} when checking a triple in {@code scopeGroup}.
     *
     * <p>Types from root scope 0 always apply (they are conjunctive with every alternative).
     * Types from the current {@code scopeGroup} also apply. Types from any other scope group do
     * not — they belong to sibling UNION branches or optional bodies that are evaluated
     * independently.</p>
     */
    public static Set<Node> typesFor(Node subject, int scopeGroup,
                                     Map<Integer, Map<Node, Set<Node>>> scopedTypes) {
        var root  = scopedTypes.getOrDefault(0, Map.of()).getOrDefault(subject, Set.of());
        if (scopeGroup == 0 || scopedTypes.getOrDefault(scopeGroup, Map.of()).isEmpty()) {
            return root;
        }
        var local = scopedTypes.getOrDefault(scopeGroup, Map.of()).getOrDefault(subject, Set.of());
        if (local.isEmpty()) return root;
        if (root.isEmpty())  return local;
        var merged = new LinkedHashSet<Node>(root);
        merged.addAll(local);
        return Set.copyOf(merged);
    }
}
