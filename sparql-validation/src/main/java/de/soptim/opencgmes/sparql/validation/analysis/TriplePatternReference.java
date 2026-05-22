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

/**
 * One triple pattern encountered in the query, with the named-graph context it appeared in
 * and a conjunctive scope group that tracks which alternatives the triple belongs to.
 *
 * <p>The {@code scopeGroup} is 0 for the root conjunctive clause and increments each time the
 * algebra walker enters a new disjunctive branch ({@code UNION}) or optional body
 * ({@code OPTIONAL} / {@code LeftJoin}). Two triples in the same scope group are always
 * evaluated together; triples in different scope groups are alternatives or may-match
 * extensions, so type assertions from one group do not inform domain checks in another.</p>
 *
 * @param triple      the Jena {@link Triple} (variables, URIs, blank nodes, literals)
 * @param graph       enclosing {@code GRAPH <g>} node, or {@code null} for default-graph
 * @param scopeGroup  conjunctive scope identifier; 0 = root, &gt;0 = disjunctive/optional branch
 */
public record TriplePatternReference(Triple triple, Node graph, int scopeGroup) {

    /** Convenience constructor for root-scope triples (scopeGroup = 0). */
    public TriplePatternReference(Triple triple, Node graph) {
        this(triple, graph, 0);
    }
}
