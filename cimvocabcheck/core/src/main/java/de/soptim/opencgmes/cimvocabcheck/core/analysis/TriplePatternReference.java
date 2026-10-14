/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimvocabcheck.core.analysis;

import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;

/**
 * One triple pattern encountered in the query, with the named-graph context it appeared in and a
 * scope chain that encodes the full conjunctive-scope ancestry of the triple.
 *
 * <h3>Scope chain semantics</h3>
 *
 * <p>The scope chain is the sequence of scope-group IDs from the root clause (always {@code 0})
 * down to the innermost scope in which the triple appears. Examples:
 *
 * <ul>
 *   <li>{@code [0]} — the triple is in the root conjunctive clause.
 *   <li>{@code [0, 1]} — inside the first UNION branch (or top-level OPTIONAL body).
 *   <li>{@code [0, 1, 3]} — inside an OPTIONAL body that is itself inside UNION branch 1.
 * </ul>
 *
 * <p>Two triples belong to the same scope when their chains are identical. A triple in scope {@code
 * [0, 1, 3]} can see type assertions from scopes {@code 0}, {@code 1}, and {@code 3} (its full
 * ancestry), but NOT from scope {@code 2} (a sibling UNION branch).
 *
 * @param triple the Jena {@link Triple} (variables, URIs, blank nodes, literals)
 * @param graph enclosing {@code GRAPH <g>} node, or {@code null} for default-graph
 * @param scopeChain immutable ancestor path; first element is always {@code 0} (root)
 * @param origin whether the triple matches data or produces it
 */
public record TriplePatternReference(
    Triple triple, Node graph, List<Integer> scopeChain, Origin origin) {

  /** Whether a triple is matched against the data or produced by the query. */
  public enum Origin {
    /** A pattern matched against the data: a WHERE clause, or a {@code DELETE WHERE} body. */
    PATTERN,
    /**
     * A triple produced by the query rather than matched: a {@code CONSTRUCT} template or an {@code
     * INSERT}/{@code DELETE} template. Checks that reason about how a pattern <em>matches </em> —
     * join behaviour, optionality — do not apply to these.
     */
    TEMPLATE
  }

  /** Canonical constructor; defaults a {@code null} origin to {@link Origin#PATTERN}. */
  public TriplePatternReference {
    origin = origin == null ? Origin.PATTERN : origin;
  }

  /** Convenience constructor for root-scope match patterns (scopeChain = {@code [0]}). */
  public TriplePatternReference(Triple triple, Node graph) {
    this(triple, graph, List.of(0), Origin.PATTERN);
  }

  /** Convenience constructor for match patterns in a known scope. */
  public TriplePatternReference(Triple triple, Node graph, List<Integer> scopeChain) {
    this(triple, graph, scopeChain, Origin.PATTERN);
  }

  /** Returns whether this triple is matched against the data (rather than produced by it). */
  public boolean isPattern() {
    return origin == Origin.PATTERN;
  }
}
