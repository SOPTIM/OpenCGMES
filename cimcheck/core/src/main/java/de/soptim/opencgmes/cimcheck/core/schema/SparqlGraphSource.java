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

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;

import java.util.List;

/**
 * A queryable source of named graphs — a SPARQL 1.1 endpoint hosting a CGMES dataset, or an
 * in-memory Jena dataset. It exposes only the four operations CIMcheck needs to load a schema
 * from an endpoint and auto-detect which instance graph belongs to which profile:
 *
 * <ul>
 *   <li>{@link #listSchemaGraphs()} — named graphs that declare a class or ontology (the schema);</li>
 *   <li>{@link #listNonEmptyGraphs()} — every named graph that holds at least one triple;</li>
 *   <li>{@link #fetchGraph(String)} — download one named graph in full (used for schema graphs);</li>
 *   <li>{@link #sampleTerms(String, int)} — a bounded sample of the predicates and {@code rdf:type}
 *       objects in a graph, used to classify it without downloading its instance data.</li>
 * </ul>
 *
 * <p>Implementations are single-threaded per instance and should be used inside a
 * try-with-resources block so any underlying HTTP resources are released.</p>
 */
public interface SparqlGraphSource extends AutoCloseable {

    /**
     * Named graphs that define a schema (contain an {@code rdfs:Class} or an {@code owl:Ontology}),
     * excluding pure instance-data graphs.
     */
    List<String> listSchemaGraphs();

    /** Every named graph that holds at least one triple. */
    List<String> listNonEmptyGraphs();

    /** Downloads the full contents of one named graph. */
    Graph fetchGraph(String graphName);

    /**
     * A bounded sample of the distinct URI terms used in {@code graphName}: every predicate plus
     * every {@code rdf:type} object. Returned terms are URI nodes only (blank nodes and literals
     * are skipped). At most {@code limit} terms are returned — enough to classify the graph by the
     * schema profile its terms belong to, without scanning the whole graph.
     */
    List<Node> sampleTerms(String graphName, int limit);

    @Override
    default void close() {}
}
