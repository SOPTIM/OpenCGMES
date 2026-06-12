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

package de.soptim.opencgmes.cimcheck.lsp.schema;

import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.graph.Graph;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
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
 * Fetches CGMES profile graphs from a remote SPARQL 1.1 endpoint that hosts the schema (e.g. an
 * Apache Jena Fuseki server with the RDFS profiles loaded), one Jena {@link Graph} per profile
 * named graph.
 *
 * <p>The CGMES schema is assumed to live in <b>per-profile named graphs</b>. Enumeration is
 * restricted to graphs that declare an {@code rdfs:Class} or an {@code owl:Ontology} so that
 * large instance-data graphs are not downloaded; {@code CimProfile.wrap} (applied downstream by
 * {@code CgmesSchemaLoader.indexFromGraphs}) is the final arbiter of what is actually a profile.</p>
 */
public final class EndpointGraphFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(EndpointGraphFetcher.class);

    private EndpointGraphFetcher() {}

    /** Named graphs that define a schema (a class or an ontology), excluding pure instance data. */
    private static final String SCHEMA_GRAPHS_QUERY = """
            SELECT DISTINCT ?g WHERE {
              GRAPH ?g {
                { ?s a <http://www.w3.org/2000/01/rdf-schema#Class> }
                UNION
                { ?s a <http://www.w3.org/2002/07/owl#Ontology> }
              }
            }""";

    private static final String CONSTRUCT_GRAPH =
            "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }";

    /**
     * Enumerates the schema named graphs at {@code endpoint} and downloads each as a graph.
     *
     * @param endpoint the SPARQL 1.1 query endpoint URL
     * @param timeout  per-query timeout (applied to enumeration and each CONSTRUCT)
     * @return one graph per schema named graph; empty graphs are omitted
     */
    public static List<Graph> fetchProfileGraphs(String endpoint, Duration timeout) {
        Enumeration enumeration = enumerateSchemaGraphs(endpoint, timeout);
        String queryEndpoint = enumeration.endpoint();
        List<String> graphNames = enumeration.graphNames();
        LOG.info("Endpoint {} exposes {} schema graph(s)", queryEndpoint, graphNames.size());
        List<Graph> graphs = new ArrayList<>(graphNames.size());
        for (String name : graphNames) {
            Model model = constructGraph(queryEndpoint, name, timeout);
            if (!model.isEmpty()) {
                graphs.add(model.getGraph());
            }
        }
        return graphs;
    }

    /** The schema graph names enumerated at an endpoint, paired with the endpoint that answered. */
    private record Enumeration(String endpoint, List<String> graphNames) {}

    /**
     * Enumerates the schema named graphs, determining which endpoint URL actually accepts read
     * queries in the process.
     *
     * <p>The given {@code endpoint} is tried first — so a dataset literally named {@code update}
     * (queried directly) is honoured rather than blindly rewritten. Only if it answers the
     * enumeration query with {@code 405 Method Not Allowed} — the response a Fuseki <b>update</b>
     * endpoint ({@code .../dataset/update}) gives to a query — do we fall back to its sibling query
     * endpoint ({@code .../dataset/query}). When no sibling can be derived, the original 405 is
     * propagated so the failure is reported faithfully.</p>
     */
    private static Enumeration enumerateSchemaGraphs(String endpoint, Duration timeout) {
        try {
            return new Enumeration(endpoint, listSchemaGraphs(endpoint, timeout));
        } catch (HttpException e) {
            String sibling = queryEndpointSibling(endpoint);
            if (e.getStatusCode() == HttpURLConnection.HTTP_BAD_METHOD && sibling != null) {
                LOG.info("Endpoint {} rejected a query with 405; falling back to its query sibling {}",
                        endpoint, sibling);
                return new Enumeration(sibling, listSchemaGraphs(sibling, timeout));
            }
            throw e;
        }
    }

    /**
     * Returns the Fuseki sibling <b>query</b> endpoint for an <b>update</b> endpoint
     * ({@code .../dataset/update} → {@code .../dataset/query}), or {@code null} when the URL does
     * not end in an {@code /update} segment and so has no conventional query sibling to try.
     */
    static String queryEndpointSibling(String endpoint) {
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

    private static List<String> listSchemaGraphs(String endpoint, Duration timeout) {
        List<String> names = new ArrayList<>();
        try (QueryExecution qe = service(endpoint, SCHEMA_GRAPHS_QUERY, timeout)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                RDFNode g = sol.get("g");
                if (g != null && g.isURIResource()) {
                    names.add(g.asResource().getURI());
                }
            }
        }
        return names;
    }

    private static Model constructGraph(String endpoint, String graphName, Duration timeout) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(CONSTRUCT_GRAPH);
        pss.setIri("g", graphName);  // escapes the IRI safely
        try (QueryExecution qe = service(endpoint, pss.toString(), timeout)) {
            return qe.execConstruct();
        }
    }

    private static QueryExecution service(String endpoint, String query, Duration timeout) {
        return QueryExecutionHTTP.service(endpoint)
                .query(query)
                .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }
}
