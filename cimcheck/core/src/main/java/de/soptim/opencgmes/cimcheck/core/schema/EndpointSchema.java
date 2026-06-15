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
import org.apache.jena.graph.Node;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The schema and named-graph mapping auto-detected from a SPARQL endpoint hosting a CGMES dataset.
 *
 * <p>When the endpoint exposes no CIM schema graphs (or none of them is a recognisable CIM
 * profile), {@link #hasSchema()} is {@code false} and {@link #index()} is {@code null} — callers
 * should warn the user and fall back to schema-independent syntax checking rather than silently
 * validating against nothing.</p>
 *
 * @param index            the schema index built from the endpoint's schema graphs, or {@code null}
 *                         when no schema could be resolved
 * @param namedGraphScope  instance graph → detected profile(s), ready for
 *                         {@code SparqlValidationApi.validateSparql(query, scope)}; empty when no
 *                         instance graph could be classified
 * @param schemaGraphNames the named graphs identified as holding the schema
 * @param unmatchedGraphs  instance graphs whose terms matched no known profile
 */
public record EndpointSchema(
        RdfsSchemaIndex index,
        Map<Node, Collection<VersionIri>> namedGraphScope,
        List<String> schemaGraphNames,
        List<Node> unmatchedGraphs) {

    public EndpointSchema {
        namedGraphScope = Map.copyOf(namedGraphScope);
        schemaGraphNames = List.copyOf(schemaGraphNames);
        unmatchedGraphs = List.copyOf(unmatchedGraphs);
    }

    /** Whether a usable schema was resolved from the endpoint. */
    public boolean hasSchema() {
        return index != null;
    }

    /** An {@link EndpointSchema} carrying no schema (endpoint exposed no CIM profiles). */
    public static EndpointSchema noSchema(List<String> schemaGraphNames) {
        return new EndpointSchema(null, Map.of(), schemaGraphNames, List.of());
    }
}
