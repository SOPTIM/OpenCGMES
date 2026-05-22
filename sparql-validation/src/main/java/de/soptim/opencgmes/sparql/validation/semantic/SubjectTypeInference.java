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
     * <p>Returns a two-level map keyed by the <em>leaf scope ID</em> (the last element of
     * {@link TriplePatternReference#scopeChain()}). Use {@link #typesFor} to resolve the
     * effective type set for a specific triple — that method walks the full scope chain and
     * merges types from all ancestor scopes, ensuring that a type declared in a required
     * parent clause is visible inside a nested OPTIONAL body.</p>
     */
    public static Map<Integer, Map<Node, Set<Node>>> inferScoped(List<TriplePatternReference> triples) {
        var out = new LinkedHashMap<Integer, Map<Node, Set<Node>>>();
        for (TriplePatternReference t : triples) {
            Node p = t.triple().getPredicate();
            if (!RDF_TYPE.equals(p)) continue;
            Node o = t.triple().getObject();
            if (!o.isURI()) continue;
            int leaf = t.scopeChain().getLast();
            out.computeIfAbsent(leaf, k -> new LinkedHashMap<>())
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
     * Returns the types visible for {@code subject} when checking a triple whose scope chain
     * is {@code scopeChain}.
     *
     * <p>The chain encodes the full ancestry from the root clause ({@code 0}) to the innermost
     * scope. A type declared in scope {@code S} is visible to a triple in scope chain
     * {@code [0, S, ...]} because {@code S} appears in the chain. Types in sibling scopes
     * (not in the chain) are excluded — they belong to independent UNION alternatives.</p>
     */
    public static Set<Node> typesFor(Node subject, List<Integer> scopeChain,
                                     Map<Integer, Map<Node, Set<Node>>> scopedTypes) {
        var result = new LinkedHashSet<Node>();
        for (int scope : scopeChain) {
            var scopeTypes = scopedTypes.getOrDefault(scope, Map.of()).getOrDefault(subject, Set.of());
            result.addAll(scopeTypes);
        }
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }
}
