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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SparqlGraphSource} backed by a remote SPARQL 1.1 query endpoint (e.g. an Apache Jena
 * Fuseki server hosting a CGMES dataset).
 *
 * <p>The given endpoint is tried first — so a dataset literally named {@code update} (queried
 * directly) is honoured rather than blindly rewritten. Only if it answers a query with {@code 405
 * Method Not Allowed} — the response a Fuseki <b>update</b> endpoint ({@code .../dataset/update})
 * gives to a query — does it fall back to the sibling query endpoint ({@code .../dataset/query}).
 * The working endpoint is resolved once and reused for every query.
 */
public final class HttpSparqlGraphSource implements SparqlGraphSource {

  private static final Logger LOG = LoggerFactory.getLogger(HttpSparqlGraphSource.class);

  /** Named graphs that define a schema (a class or an ontology), excluding pure instance data. */
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

  private static final String CONSTRUCT_GRAPH =
      "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }";

  /** All triples that have a given resource as their subject, across every named graph. */
  private static final String CONSTRUCT_RESOURCE =
      "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }";

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

  /** Fuseki service operations that are not SPARQL query and so map to a {@code /query} sibling. */
  private static final Set<String> NON_QUERY_OPERATIONS =
      Set.of("update", "shacl", "data", "get", "upload");

  private final String endpoint;
  private final Duration timeout;
  private String resolvedEndpoint; // memoized after the first successful query

  /** Creates a source querying {@code endpoint} with the given request {@code timeout}. */
  public HttpSparqlGraphSource(String endpoint, Duration timeout) {
    this.endpoint = stripQuery(endpoint);
    this.timeout = timeout;
  }

  /** The endpoint URL that actually answered queries (after any Fuseki sibling fallback). */
  public String resolvedEndpoint() {
    return endpoint();
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
    ParameterizedSparqlString pss = new ParameterizedSparqlString();
    pss.setCommandText(CONSTRUCT_GRAPH);
    pss.setIri("g", graphName); // escapes the IRI safely
    try (QueryExecution qe = service(endpoint(), pss.toString())) {
      return qe.execConstruct().getGraph();
    }
  }

  /**
   * Fetches every triple that has {@code iri} as its subject, across all named graphs — the
   * resource's definition (its {@code rdf:type}, {@code rdfs:label}/{@code comment}, {@code
   * rdfs:domain}/{@code range}, {@code cims:*} annotations, …). Used to render a go-to-definition
   * "peek" for a schema term hosted on the endpoint.
   */
  public Graph fetchResource(String iri) {
    ParameterizedSparqlString pss = new ParameterizedSparqlString();
    pss.setCommandText(CONSTRUCT_RESOURCE);
    pss.setIri("s", iri); // escapes the IRI safely and binds ?s to it
    try (QueryExecution qe = service(endpoint(), pss.toString())) {
      return qe.execConstruct().getGraph();
    }
  }

  @Override
  public List<Node> sampleTerms(String graphName, int limit) {
    ParameterizedSparqlString pss = new ParameterizedSparqlString();
    pss.setCommandText(SAMPLE_TERMS_QUERY + "\nLIMIT " + Math.max(1, limit));
    pss.setIri("g", graphName);
    var terms = new ArrayList<Node>();
    try (QueryExecution qe = service(endpoint(), pss.toString())) {
      ResultSet rs = qe.execSelect();
      while (rs.hasNext()) {
        QuerySolution sol = rs.next();
        RDFNode t = sol.get("term");
        if (t != null && t.isURIResource()) {
          terms.add(t.asNode());
        }
      }
    }
    return terms;
  }

  // ---- endpoint resolution ---------------------------------------------------------------

  /**
   * Returns the working query endpoint, resolving a Fuseki operation→{@code query} sibling once on
   * first use. A lightweight {@code ASK {}} probe decides whether the given URL accepts queries;
   * the result is memoized.
   *
   * <p>The given URL is probed first — so a dataset literally named {@code shacl}/{@code update}
   * (queried directly) is honoured. If it does not answer a SPARQL query (any HTTP error — a Fuseki
   * {@code /update} endpoint replies {@code 405} to a query, a {@code /shacl} endpoint {@code 404}
   * to a GET), and the URL looks like a known non-query operation ({@code /shacl}, {@code /update},
   * {@code /data}, {@code /get}, {@code /upload}), its {@code /query} sibling is probed and used
   * instead. When no usable sibling exists, the original failure is surfaced faithfully.
   */
  private String endpoint() {
    if (resolvedEndpoint != null) {
      return resolvedEndpoint;
    }
    try {
      probe(endpoint);
      resolvedEndpoint = endpoint;
    } catch (QueryExceptionHTTP e) {
      String sibling = queryEndpointSibling(endpoint);
      if (sibling != null && !sibling.equals(endpoint)) {
        try {
          probe(sibling);
          LOG.info(
              "Endpoint {} did not answer a SPARQL query ({}); " + "using its query sibling {}",
              endpoint,
              e.getMessage(),
              sibling);
          resolvedEndpoint = sibling;
          return resolvedEndpoint;
        } catch (QueryExceptionHTTP siblingError) {
          LOG.debug("Query sibling {} also failed: {}", sibling, siblingError.getMessage());
        }
      }
      throw e; // no usable sibling — surface the original failure
    }
    return resolvedEndpoint;
  }

  /** Runs a trivial {@code ASK {}} against {@code ep} to check it accepts SPARQL queries. */
  private void probe(String ep) {
    try (QueryExecution qe = service(ep, "ASK {}")) {
      qe.execAsk();
    }
  }

  /**
   * Returns the Fuseki <b>query</b> sibling of a non-query service-operation endpoint, or {@code
   * null} when the URL is already a query endpoint or names no known operation.
   *
   * <p>Maps the last path segment {@code update}/{@code shacl}/{@code data}/{@code get}/ {@code
   * upload} to {@code query} (e.g. {@code .../test/shacl} → {@code .../test/query}). Any query
   * string is dropped first. {@code query}/{@code sparql} and a bare dataset URL (whose last
   * segment is the dataset name) return {@code null} — they are already usable for queries.
   */
  public static String queryEndpointSibling(String endpoint) {
    if (endpoint == null) {
      return null;
    }
    String trimmed = stripQuery(endpoint);
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    int slash = trimmed.lastIndexOf('/');
    if (slash < 0) {
      return null;
    }
    String lastSegment = trimmed.substring(slash + 1);
    if (NON_QUERY_OPERATIONS.contains(lastSegment)) {
      return trimmed.substring(0, slash) + "/query";
    }
    return null;
  }

  /** Strips a {@code ?query-string} from {@code url}, if present. */
  private static String stripQuery(String url) {
    if (url == null) {
      return null;
    }
    int q = url.indexOf('?');
    return q >= 0 ? url.substring(0, q) : url;
  }

  private List<String> selectUris(String query, String var) {
    var names = new ArrayList<String>();
    try (QueryExecution qe = service(endpoint(), query)) {
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

  private QueryExecution service(String ep, String query) {
    return QueryExecutionHTTP.service(ep)
        .query(query)
        .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .build();
  }
}
