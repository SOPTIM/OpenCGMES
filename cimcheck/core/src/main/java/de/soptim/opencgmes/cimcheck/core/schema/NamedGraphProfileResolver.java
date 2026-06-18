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

package de.soptim.opencgmes.cimcheck.core.schema;

import de.soptim.opencgmes.cimcheck.core.VersionIri;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * Auto-detects which CGMES profile each instance named graph holds, by matching the terms used in
 * the graph against the profiles declared in a {@link SchemaIndex}.
 *
 * <p>The schema is assumed to be hosted alongside the instance data (e.g. an Apache Jena Fuseki
 * server with both the RDFS profiles and the model loaded as named graphs). For each instance
 * graph, a bounded sample of its predicates and {@code rdf:type} objects is looked up in the index;
 * each term maps to the set of profiles that declare it. The graph is assigned to the profile that
 * best explains its terms, preferring <em>discriminating</em> terms (declared by exactly one
 * profile) — this is the "property is in profile A but not B" signal that uniquely identifies a
 * profile. A graph whose terms match no known profile is reported as unmatched.
 *
 * <p>The result is exactly the per-graph profile scope the validator already consumes ({@code GRAPH
 * <g> { ... }} terms validated against {@code <g>}'s profiles), so no manual {@code namedGraphs}
 * configuration is required. The classification only ever samples a graph to decide which profile
 * it is — it never validates the instance data itself.
 */
public final class NamedGraphProfileResolver {

  /**
   * Upper bound on the number of distinct terms sampled per graph. A CGMES profile declares on the
   * order of a few hundred properties and classes, so this is ample to classify a graph while
   * keeping the per-graph query cheap.
   */
  public static final int DEFAULT_SAMPLE_LIMIT = 400;

  private NamedGraphProfileResolver() {}

  /**
   * The detected per-graph profile scope plus the instance graphs that could not be classified.
   *
   * @param scope graph node → the single best-matching profile (a singleton list), ready to pass to
   *     {@code SparqlValidationApi.validateSparql(query, scope)}
   * @param unmatched instance graphs whose terms matched no known profile
   */
  public record Result(Map<Node, Collection<VersionIri>> scope, List<Node> unmatched) {
    /** Canonical constructor; defensively copies the scope map and unmatched list. */
    public Result {
      scope = Map.copyOf(scope);
      unmatched = List.copyOf(unmatched);
    }
  }

  /** Resolves the per-graph profile scope using the {@link #DEFAULT_SAMPLE_LIMIT}. */
  public static Result resolve(
      SparqlGraphSource source, SchemaIndex index, Collection<String> schemaGraphNames) {
    return resolve(source, index, schemaGraphNames, DEFAULT_SAMPLE_LIMIT);
  }

  /**
   * Resolves the per-graph profile scope.
   *
   * @param source the queryable dataset
   * @param index the schema index built from the dataset's schema graphs
   * @param schemaGraphNames the names of the schema graphs (excluded from classification)
   * @param sampleLimit maximum distinct terms sampled per instance graph
   */
  public static Result resolve(
      SparqlGraphSource source,
      SchemaIndex index,
      Collection<String> schemaGraphNames,
      int sampleLimit) {

    Set<String> schemaGraphs = new HashSet<>(schemaGraphNames);
    var scope = new LinkedHashMap<Node, Collection<VersionIri>>();
    var unmatched = new ArrayList<Node>();

    for (String graphName : source.listNonEmptyGraphs()) {
      if (schemaGraphs.contains(graphName)) {
        continue; // it's a schema graph, not instance data
      }
      List<Node> terms = source.sampleTerms(graphName, sampleLimit);
      VersionIri profile = classify(terms, index);
      Node graphNode = NodeFactory.createURI(graphName);
      if (profile != null) {
        scope.put(graphNode, List.of(profile));
      } else {
        unmatched.add(graphNode);
      }
    }
    return new Result(scope, unmatched);
  }

  /**
   * Picks the profile that best explains {@code terms}: the one declaring the most discriminating
   * terms (terms declared by exactly one profile), with total declared-term count as a tie-break.
   * Returns {@code null} when no term matches any profile.
   */
  private static VersionIri classify(List<Node> terms, SchemaIndex index) {
    var totalScore = new HashMap<VersionIri, Integer>();
    var discriminatingScore = new HashMap<VersionIri, Integer>();

    for (Node term : terms) {
      Set<VersionIri> declaring = new LinkedHashSet<>(index.findProperty(term));
      declaring.addAll(index.findClass(term));
      if (declaring.isEmpty()) {
        continue;
      }
      for (VersionIri v : declaring) {
        totalScore.merge(v, 1, Integer::sum);
      }
      if (declaring.size() == 1) {
        discriminatingScore.merge(declaring.iterator().next(), 1, Integer::sum);
      }
    }
    if (totalScore.isEmpty()) {
      return null;
    }
    return totalScore.keySet().stream()
        .max(
            Comparator.comparingInt((VersionIri v) -> discriminatingScore.getOrDefault(v, 0))
                .thenComparingInt(v -> totalScore.getOrDefault(v, 0)))
        .orElse(null);
  }
}
