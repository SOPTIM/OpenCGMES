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

package de.soptim.opencgmes.cimvocabcheck.core.schema;

import java.util.Optional;
import org.apache.jena.graph.Node;

/**
 * A CIM attribute/association multiplicity — the {@code min..max} cardinality carried by a CIM RDFS
 * property via {@code cims:multiplicity}.
 *
 * <p>The value is expressed in the {@code cims:} namespace as an individual whose local name is
 * {@code M:<lower>[..<upper>]}, e.g. {@code M:1..1} (exactly one), {@code M:0..1} (optional),
 * {@code M:0..n} (any number), {@code M:1..n} (at least one), or the shorthand {@code M:1} (exactly
 * one). An upper bound of {@code n} means unbounded.
 *
 * @param min the lower bound (&gt;= 0)
 * @param max the upper bound, or {@code null} for unbounded ({@code n})
 */
public record Multiplicity(int min, Integer max) {

  private static final String M_PREFIX = "M:";

  /**
   * Parses the {@code cims:multiplicity} value IRI {@code valueIri} into a {@link Multiplicity}, or
   * returns empty when it is not a recognised {@code M:...} form.
   */
  public static Optional<Multiplicity> parse(Node valueIri) {
    if (valueIri == null || !valueIri.isURI()) {
      return Optional.empty();
    }
    String uri = valueIri.getURI();
    int sep = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
    String local = sep >= 0 ? uri.substring(sep + 1) : uri;
    if (!local.startsWith(M_PREFIX)) {
      return Optional.empty();
    }
    String body = local.substring(M_PREFIX.length()).trim();
    try {
      int dots = body.indexOf("..");
      if (dots < 0) {
        int exact = Integer.parseInt(body);
        return Optional.of(new Multiplicity(exact, exact));
      }
      int lower = Integer.parseInt(body.substring(0, dots).trim());
      String upper = body.substring(dots + 2).trim();
      Integer max = upper.equalsIgnoreCase("n") ? null : Integer.parseInt(upper);
      return Optional.of(new Multiplicity(lower, max));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** Returns whether the upper bound is unbounded ({@code n}). */
  public boolean unbounded() {
    return max == null;
  }

  /** Human-readable {@code min..max} form, using {@code n} for an unbounded upper bound. */
  public String display() {
    return min + ".." + (max == null ? "n" : Integer.toString(max));
  }
}
