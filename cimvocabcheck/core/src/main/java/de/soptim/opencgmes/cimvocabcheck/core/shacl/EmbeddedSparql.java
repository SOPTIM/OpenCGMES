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

package de.soptim.opencgmes.cimvocabcheck.core.shacl;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.apache.jena.graph.Node;

/**
 * One SPARQL query embedded inside a SHACL shapes graph.
 *
 * @param container the RDF node bearing the {@code sh:select}/{@code sh:ask}/{@code sh:construct}
 *     triple (typically a blank node attached to a shape via {@code sh:target}, {@code sh:sparql},
 *     etc.)
 * @param queryPredicate one of {@link Shacl#SELECT}, {@link Shacl#ASK} or {@link Shacl#CONSTRUCT}
 * @param kind high-level form of the query
 * @param rawQuery the query string as it appears in the shapes graph, <em>without</em>
 *     SHACL-declared prefixes prepended
 * @param prefixes prefix declarations resolved from {@code sh:prefixes}, possibly empty
 * @param targetClasses the {@code sh:targetClass} values of the enclosing shape(s), if resolvable;
 *     empty when the context is ambiguous or absent (e.g. a standalone {@code
 *     sh:SPARQLAskValidator} not yet linked to a shape)
 * @param shPaths simple URI {@code sh:path} values from enclosing {@code sh:PropertyShape} nodes
 *     that link to this container via {@code sh:sparql}; empty when no enclosing property shape has
 *     a simple URI path (complex paths, sequence paths, inverse paths, etc. are not collected
 *     here). Used to substitute the SHACL {@code $PATH} variable before static analysis.
 */
public record EmbeddedSparql(
    Node container,
    Node queryPredicate,
    Kind kind,
    String rawQuery,
    Map<String, String> prefixes,
    Set<Node> targetClasses,
    Set<Node> shPaths) {

  /** Canonical constructor; validates the required fields. */
  public EmbeddedSparql {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(queryPredicate, "queryPredicate");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(rawQuery, "rawQuery");
    prefixes = prefixes == null ? Map.of() : Map.copyOf(new TreeMap<>(prefixes));
    targetClasses = targetClasses == null ? Set.of() : Set.copyOf(targetClasses);
    shPaths = shPaths == null ? Set.of() : Set.copyOf(shPaths);
  }

  /** Returns the {@link #rawQuery() raw query} with all resolved {@link #prefixes} prepended. */
  public String renderedQuery() {
    if (prefixes.isEmpty()) {
      return rawQuery;
    }
    var sb = new StringBuilder(rawQuery.length() + 80 * prefixes.size());
    for (var e : prefixes.entrySet()) {
      sb.append("PREFIX ").append(e.getKey()).append(": <").append(e.getValue()).append(">\n");
    }
    sb.append(rawQuery);
    return sb.toString();
  }

  /** High-level SPARQL form carried by the {@link #queryPredicate}. */
  public enum Kind {
    SELECT,
    ASK,
    CONSTRUCT
  }
}
