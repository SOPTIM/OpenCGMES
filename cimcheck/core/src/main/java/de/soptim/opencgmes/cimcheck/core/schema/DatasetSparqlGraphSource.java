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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;

/**
 * A {@link SparqlGraphSource} backed by an in-memory Jena {@link Dataset}.
 *
 * <p>Used in tests and whenever a CGMES dataset is already available in-process, so endpoint
 * auto-detection can be exercised without an HTTP round-trip. The query semantics mirror those of
 * {@link HttpSparqlGraphSource} so both implementations classify graphs identically.
 */
public final class DatasetSparqlGraphSource implements SparqlGraphSource {

  private static final String SCHEMA_GRAPHS_QUERY =
      """
      SELECT DISTINCT ?g WHERE {
        GRAPH ?g {
          { ?s a <http://www.w3.org/2000/01/rdf-schema#Class> }
          UNION
          { ?s a <http://www.w3.org/2002/07/owl#Ontology> }
        }
      }\
      """;

  private static final String ALL_GRAPHS_QUERY =
      "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }";

  private static final String SAMPLE_TERMS_QUERY =
      """
      SELECT DISTINCT ?term WHERE {
        GRAPH ?g {
          { ?s ?term ?o }
          UNION
          { ?x a ?term }
        }
      }\
      """;

  private final Dataset dataset;

  /** Creates a source backed by the given in-memory {@code dataset}. */
  public DatasetSparqlGraphSource(Dataset dataset) {
    this.dataset = Objects.requireNonNull(dataset, "dataset");
  }

  @Override
  public List<String> listSchemaGraphs() {
    return selectUris(SCHEMA_GRAPHS_QUERY, "g");
  }

  @Override
  public List<String> listNonEmptyGraphs() {
    return selectUris(ALL_GRAPHS_QUERY, "g");
  }

  @Override
  public Graph fetchGraph(String graphName) {
    return dataset.getNamedModel(graphName).getGraph();
  }

  @Override
  public List<Node> sampleTerms(String graphName, int limit) {
    ParameterizedSparqlString pss = new ParameterizedSparqlString();
    pss.setCommandText(SAMPLE_TERMS_QUERY + "\nLIMIT " + Math.max(1, limit));
    pss.setIri("g", graphName);
    var terms = new ArrayList<Node>();
    try (QueryExecution qe = QueryExecution.dataset(dataset).query(pss.toString()).build()) {
      ResultSet rs = qe.execSelect();
      while (rs.hasNext()) {
        RDFNode t = rs.next().get("term");
        if (t != null && t.isURIResource()) {
          terms.add(t.asNode());
        }
      }
    }
    return terms;
  }

  private List<String> selectUris(String query, String var) {
    var names = new ArrayList<String>();
    try (QueryExecution qe = QueryExecution.dataset(dataset).query(query).build()) {
      ResultSet rs = qe.execSelect();
      while (rs.hasNext()) {
        QuerySolution sol = rs.next();
        RDFNode n = sol.get(var);
        if (n != null && n.isURIResource()) {
          names.add(n.asResource().getURI());
        }
      }
    }
    return names;
  }
}
