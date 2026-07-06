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

import de.soptim.opencgmes.cimvocabcheck.core.SchemaLoadCode;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Node;

/**
 * The schema and named-graph mapping auto-detected from a SPARQL endpoint hosting a CGMES dataset.
 *
 * <p>When the endpoint exposes no CIM schema graphs (or none of them is a recognisable CIM
 * profile), {@link #hasSchema()} is {@code false} and {@link #index()} is {@code null} — callers
 * should warn the user and fall back to schema-independent syntax checking rather than silently
 * validating against nothing. {@link #schemaGraphNames()} still distinguishes the two cases: empty
 * means the endpoint genuinely exposed no schema-like graphs, non-empty (with {@link
 * #unresolvedReason()} set) means schema graphs were found but none resolved to a registered CIM
 * profile — most likely an unrecognized {@code cim} namespace that needs registering (see {@code
 * cimNamespaces} in {@code opencgmes.jsonc}).
 *
 * @param index the schema index built from the endpoint's schema graphs, or {@code null} when no
 *     schema could be resolved
 * @param namedGraphScope graph → profile(s), ready for {@code
 *     SparqlValidationApi.validateSparql(query, scope)}. Holds each classified instance graph
 *     mapped to its detected profile(s), plus each schema graph mapped to all profiles (so queries
 *     that navigate the RDFS schema directly validate permissively instead of being reported as
 *     {@code GRAPH_NOT_CONFIGURED})
 * @param schemaGraphNames the named graphs identified as holding the schema
 * @param unmatchedGraphs instance graphs whose terms matched no known profile
 * @param unresolvedReason when {@link #hasSchema()} is {@code false} and {@code schemaGraphNames}
 *     is non-empty, the reason none of them resolved to a CIM profile; {@code null} otherwise
 * @param unresolvedCode the {@link SchemaLoadCode} classifying {@link #unresolvedReason()}, or
 *     {@code null} when there is no unresolved reason
 */
public record EndpointSchema(
    RdfsSchemaIndex index,
    Map<Node, Collection<VersionIri>> namedGraphScope,
    List<String> schemaGraphNames,
    List<Node> unmatchedGraphs,
    String unresolvedReason,
    SchemaLoadCode unresolvedCode) {

  /** Canonical constructor; defensively copies the scope map and graph lists. */
  public EndpointSchema {
    namedGraphScope = Map.copyOf(namedGraphScope);
    schemaGraphNames = List.copyOf(schemaGraphNames);
    unmatchedGraphs = List.copyOf(unmatchedGraphs);
  }

  /** Whether a usable schema was resolved from the endpoint. */
  public boolean hasSchema() {
    return index != null;
  }

  /**
   * Number of instance graphs auto-mapped to a profile, i.e. the scope entries that are not schema
   * graphs. (Schema graphs are also present in {@link #namedGraphScope()} but mapped to all
   * profiles.)
   */
  public int instanceGraphsMapped() {
    return namedGraphScope.size() - schemaGraphNames.size();
  }

  /**
   * An {@link EndpointSchema} carrying no schema, either because the endpoint exposed no
   * schema-like graphs at all ({@code schemaGraphNames} empty), or because it exposed some but none
   * resolved to a registered CIM profile (in which case {@code reason}/{@code code} explain why).
   */
  public static EndpointSchema noSchema(
      List<String> schemaGraphNames, String reason, SchemaLoadCode code) {
    return new EndpointSchema(null, Map.of(), schemaGraphNames, List.of(), reason, code);
  }

  /** An {@link EndpointSchema} carrying no schema, with no specific reason recorded. */
  public static EndpointSchema noSchema(List<String> schemaGraphNames) {
    return noSchema(schemaGraphNames, null, null);
  }
}
