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
import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Op;

/**
 * Result of a static {@link SparqlQueryAnalyzer} pass over a SPARQL query.
 *
 * <p>The collections are de-duplicated value lists in source order. A {@code dynamicPredicate} flag
 * of {@code true} means at least one triple in the query has a variable predicate, which cannot be
 * validated statically.
 */
public record SparqlQueryAnalysis(
    Query query,
    Op algebra,
    List<TriplePatternReference> triples,
    List<ClassReference> classes,
    List<PropertyReference> properties,
    List<GraphReference> graphs,
    List<PathChainReference> pathChains,
    boolean dynamicPredicate,
    boolean dynamicClass) {

  /** Canonical constructor; validates required fields and defensively copies the lists. */
  public SparqlQueryAnalysis {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(algebra, "algebra");
    triples = List.copyOf(triples);
    classes = List.copyOf(classes);
    properties = List.copyOf(properties);
    graphs = List.copyOf(graphs);
    pathChains = pathChains == null ? List.of() : List.copyOf(pathChains);
  }
}
