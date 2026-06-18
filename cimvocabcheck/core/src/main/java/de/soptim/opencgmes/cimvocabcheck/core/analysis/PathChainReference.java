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

package de.soptim.opencgmes.cimvocabcheck.core.analysis;

import java.util.List;
import java.util.Objects;
import org.apache.jena.graph.Node;

/**
 * A sequence of <em>concrete forward property IRIs</em> extracted from a SPARQL property path of
 * the form {@code p1/p2/p3}.
 *
 * <p>Only "simple" sequence paths are surfaced here — any non-{@code P_Link} component (inverse,
 * alt, mod, neg) inside the path causes the chain to be dropped, because chain compatibility across
 * those operators is more nuanced than the current chain check commits to.
 *
 * @param segments property IRIs in path order; always at least 2 elements
 * @param graph enclosing {@code GRAPH <g>} node, or {@code null} for default-graph
 */
public record PathChainReference(List<Node> segments, Node graph) {

  /** Canonical constructor; requires at least two path segments. */
  public PathChainReference {
    Objects.requireNonNull(segments, "segments");
    if (segments.size() < 2) {
      throw new IllegalArgumentException("chain must have at least 2 segments");
    }
    segments = List.copyOf(segments);
  }
}
