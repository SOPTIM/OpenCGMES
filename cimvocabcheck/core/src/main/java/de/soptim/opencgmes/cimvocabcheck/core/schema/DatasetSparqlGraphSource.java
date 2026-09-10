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

package de.soptim.opencgmes.cimvocabcheck.core.schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;

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

  /**
   * {@inheritDoc}
   *
   * <p>Walks the graph rather than querying it by name. A graph here is named by whoever filled the
   * dataset — RDFArchitect names one after the file it was imported from — and such a name is
   * routinely not writable as a SPARQL {@code IRIREF}: a space in it makes the query text
   * unparseable, which would fail the whole schema load over one graph. {@link #fetchGraph} already
   * addresses graphs directly for the same reason.
   */
  @Override
  public List<Node> sampleTerms(String graphName, int limit) {
    var terms = new LinkedHashSet<Node>();
    int max = Math.max(1, limit);
    Graph graph = fetchGraph(graphName);
    ExtendedIterator<Triple> triples = graph.find();
    try {
      while (triples.hasNext() && terms.size() < max) {
        Triple triple = triples.next();
        // The same two shapes the SPARQL form sampled: every predicate, and the object of rdf:type.
        if (triple.getPredicate().isURI()) {
          terms.add(triple.getPredicate());
        }
        if (terms.size() < max
            && RDF.type.asNode().equals(triple.getPredicate())
            && triple.getObject().isURI()) {
          terms.add(triple.getObject());
        }
      }
    } finally {
      triples.close();
    }
    return List.copyOf(terms);
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
