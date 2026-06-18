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

package de.soptim.opencgmes.cimcheck.core.explain;

/**
 * The static "explain" of a SPARQL query: its normalized text plus the algebra plan, both as Jena
 * compiles it and as Jena's static optimizer rewrites it. This is the CIMcheck equivalent of {@code
 * arq.qparse --print=query,op,opt} — no data is executed.
 *
 * <p>When the input is not a query (for example a SPARQL Update, which has no algebra plan) or
 * could not be parsed, {@link #algebra()} and {@link #optimizedAlgebra()} are {@code null} and
 * {@link #note()} carries a human-readable explanation. Use {@link #ofMessage} for that case.
 *
 * @param queryText the normalized query text (or the original input when it did not parse)
 * @param algebra the compiled algebra in Jena SSE syntax, or {@code null}
 * @param optimizedAlgebra the optimized algebra in Jena SSE syntax, or {@code null}
 * @param note a free-form note shown when there is no algebra to display, or {@code null}
 */
public record QueryExplanation(
    String queryText, String algebra, String optimizedAlgebra, String note) {

  /** A full explanation with both algebra forms. */
  public static QueryExplanation of(String queryText, String algebra, String optimizedAlgebra) {
    return new QueryExplanation(queryText, algebra, optimizedAlgebra, null);
  }

  /** An explanation with no algebra (e.g. an Update request, or a parse failure). */
  public static QueryExplanation ofMessage(String queryText, String note) {
    return new QueryExplanation(queryText, null, null, note);
  }

  /** {@code true} when an algebra plan is present. */
  public boolean hasPlan() {
    return algebra != null;
  }

  /**
   * Renders the explanation as a sectioned, {@code arq}-like text block suitable for display in a
   * read-only editor tab or printed to a console.
   */
  public String render() {
    var sb = new StringBuilder();
    sb.append("# Query\n");
    sb.append(queryText == null ? "" : queryText.stripTrailing()).append('\n');
    if (note != null && !note.isBlank()) {
      sb.append('\n').append("# Note\n").append(note.stripTrailing()).append('\n');
    }
    if (algebra != null) {
      sb.append('\n').append("# Algebra\n").append(algebra.stripTrailing()).append('\n');
    }
    if (optimizedAlgebra != null) {
      sb.append('\n')
          .append("# Algebra (optimized)\n")
          .append(optimizedAlgebra.stripTrailing())
          .append('\n');
    }
    return sb.toString();
  }
}
