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

package de.soptim.opencgmes.sparql.validation.analysis;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;

import java.util.List;

/**
 * One triple pattern encountered in the query, with the named-graph context it appeared in
 * and a scope chain that encodes the full conjunctive-scope ancestry of the triple.
 *
 * <h3>Scope chain semantics</h3>
 * <p>The scope chain is the sequence of scope-group IDs from the root clause (always {@code 0})
 * down to the innermost scope in which the triple appears. Examples:</p>
 * <ul>
 *   <li>{@code [0]} — the triple is in the root conjunctive clause.</li>
 *   <li>{@code [0, 1]} — inside the first UNION branch (or top-level OPTIONAL body).</li>
 *   <li>{@code [0, 1, 3]} — inside an OPTIONAL body that is itself inside UNION branch 1.</li>
 * </ul>
 * <p>Two triples belong to the same scope when their chains are identical. A triple in scope
 * {@code [0, 1, 3]} can see type assertions from scopes {@code 0}, {@code 1}, and {@code 3}
 * (its full ancestry), but NOT from scope {@code 2} (a sibling UNION branch).</p>
 *
 * @param triple      the Jena {@link Triple} (variables, URIs, blank nodes, literals)
 * @param graph       enclosing {@code GRAPH <g>} node, or {@code null} for default-graph
 * @param scopeChain  immutable ancestor path; first element is always {@code 0} (root)
 */
public record TriplePatternReference(Triple triple, Node graph, List<Integer> scopeChain) {

    /** Convenience constructor for root-scope triples (scopeChain = {@code [0]}). */
    public TriplePatternReference(Triple triple, Node graph) {
        this(triple, graph, List.of(0));
    }
}
