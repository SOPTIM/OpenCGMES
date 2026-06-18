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

import de.soptim.opencgmes.cimcheck.core.CgmesSchemaLoader;
import de.soptim.opencgmes.cimcheck.core.CgmesSchemaLoader.SchemaLoadException;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a CGMES schema directly from a SPARQL endpoint (or any {@link SparqlGraphSource}) and
 * auto-detects which instance named graph holds which profile.
 *
 * <p>This is the headless, library-level entry point for endpoint validation — usable from a CI
 * pipeline, the CLI, or the language server without any of them re-implementing the fetch +
 * classify dance. The flow is:
 *
 * <ol>
 *   <li>enumerate the schema graphs (those declaring a class or ontology) and download each;
 *   <li>build a {@link RdfsSchemaIndex} from them;
 *   <li>classify every other (instance) named graph against that index via {@link
 *       NamedGraphProfileResolver}.
 * </ol>
 *
 * <p>An endpoint that is reachable but exposes no CIM schema graphs yields {@link
 * EndpointSchema#noSchema(List)} rather than an exception, so callers can warn and fall back to
 * syntax-only checking. Genuine I/O failures (unreachable endpoint, HTTP errors) propagate.
 */
public final class EndpointSchemaLoader {

  private static final Logger LOG = LoggerFactory.getLogger(EndpointSchemaLoader.class);

  private EndpointSchemaLoader() {}

  /**
   * Loads the schema from a remote SPARQL 1.1 query endpoint.
   *
   * @param endpoint the endpoint URL (a Fuseki {@code .../update} URL is tolerated — its {@code
   *     .../query} sibling is used automatically)
   * @param timeout per-query timeout
   */
  public static EndpointSchema loadFromEndpoint(String endpoint, Duration timeout) {
    Objects.requireNonNull(endpoint, "endpoint");
    try (SparqlGraphSource source = new HttpSparqlGraphSource(endpoint, timeout)) {
      return load(source);
    } catch (Exception e) {
      // SparqlGraphSource.close() is declared to throw; HTTP sources don't, so re-wrap defensively.
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException("Error closing endpoint source: " + e.getMessage(), e);
    }
  }

  /**
   * Loads the schema from an arbitrary graph source (used in tests and for in-process datasets).
   */
  public static EndpointSchema load(SparqlGraphSource source) {
    Objects.requireNonNull(source, "source");

    List<String> schemaGraphNames = source.listSchemaGraphs();
    LOG.info("Endpoint exposes {} schema graph(s)", schemaGraphNames.size());

    var graphs = new ArrayList<Graph>(schemaGraphNames.size());
    for (String name : schemaGraphNames) {
      Graph g = source.fetchGraph(name);
      if (!g.isEmpty()) {
        graphs.add(g);
      }
    }

    RdfsSchemaIndex index;
    try {
      index = CgmesSchemaLoader.indexFromGraphs(graphs);
    } catch (SchemaLoadException e) {
      LOG.warn("No CIM schema could be built from the endpoint's graphs: {}", e.getMessage());
      return EndpointSchema.noSchema(schemaGraphNames);
    }

    NamedGraphProfileResolver.Result detected =
        NamedGraphProfileResolver.resolve(source, index, schemaGraphNames);

    var scope = new LinkedHashMap<Node, Collection<VersionIri>>(detected.scope());
    List<VersionIri> allProfiles = index.getAllProfiles();
    for (String schemaGraph : schemaGraphNames) {
      scope.putIfAbsent(NodeFactory.createURI(schemaGraph), allProfiles);
    }

    LOG.info(
        "Auto-mapped {} instance graph(s) to profiles ({} unmatched); "
            + "{} schema graph(s) mapped to all profiles",
        detected.scope().size(),
        detected.unmatched().size(),
        schemaGraphNames.size());
    return new EndpointSchema(index, scope, schemaGraphNames, detected.unmatched());
  }
}
