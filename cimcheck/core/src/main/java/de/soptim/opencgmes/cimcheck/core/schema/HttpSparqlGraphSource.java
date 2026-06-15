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

import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link SparqlGraphSource} backed by a remote SPARQL 1.1 query endpoint (e.g. an Apache Jena
 * Fuseki server hosting a CGMES dataset).
 *
 * <p>The given endpoint is tried first — so a dataset literally named {@code update} (queried
 * directly) is honoured rather than blindly rewritten. Only if it answers a query with
 * {@code 405 Method Not Allowed} — the response a Fuseki <b>update</b> endpoint
 * ({@code .../dataset/update}) gives to a query — does it fall back to the sibling query endpoint
 * ({@code .../dataset/query}). The working endpoint is resolved once and reused for every query.</p>
 */
public final class HttpSparqlGraphSource implements SparqlGraphSource {

    private static final Logger LOG = LoggerFactory.getLogger(HttpSparqlGraphSource.class);

    /** Named graphs that define a schema (a class or an ontology), excluding pure instance data. */
    private static final String SCHEMA_GRAPHS_QUERY = """
            SELECT DISTINCT ?g WHERE {
              GRAPH ?g {
                { ?s a <http://www.w3.org/2000/01/rdf-schema#Class> }
                UNION
                { ?s a <http://www.w3.org/2002/07/owl#Ontology> }
              }
            }""";

    private static final String ALL_GRAPHS_QUERY =
            "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }";

    private static final String CONSTRUCT_GRAPH =
            "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }";

    private static final String SAMPLE_TERMS_QUERY = """
            SELECT DISTINCT ?term WHERE {
              GRAPH ?g {
                { ?s ?term ?o }
                UNION
                { ?x a ?term }
              }
            }""";

    private final String endpoint;
    private final Duration timeout;
    private String resolvedEndpoint; // memoized after the first successful query

    public HttpSparqlGraphSource(String endpoint, Duration timeout) {
        this.endpoint = endpoint;
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
     * Returns the working query endpoint, resolving the Fuseki {@code update}→{@code query} sibling
     * fallback once on first use. A lightweight {@code ASK {}} probe decides which URL accepts
     * queries; the result is memoized.
     */
    private String endpoint() {
        if (resolvedEndpoint != null) return resolvedEndpoint;
        try {
            try (QueryExecution qe = service(endpoint, "ASK {}")) {
                qe.execAsk();
            }
            resolvedEndpoint = endpoint;
        } catch (HttpException e) {
            String sibling = queryEndpointSibling(endpoint);
            if (e.getStatusCode() == HttpURLConnection.HTTP_BAD_METHOD && sibling != null) {
                LOG.info("Endpoint {} rejected a query with 405; falling back to its query sibling {}",
                        endpoint, sibling);
                resolvedEndpoint = sibling;
            } else {
                throw e;
            }
        }
        return resolvedEndpoint;
    }

    /**
     * Returns the Fuseki sibling <b>query</b> endpoint for an <b>update</b> endpoint
     * ({@code .../dataset/update} → {@code .../dataset/query}), or {@code null} when the URL does
     * not end in an {@code /update} segment and so has no conventional query sibling to try.
     */
    public static String queryEndpointSibling(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String trimmed = endpoint;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/update")) {
            return trimmed.substring(0, trimmed.length() - "/update".length()) + "/query";
        }
        return null;
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
