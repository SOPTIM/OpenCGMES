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

package de.soptim.opencgmes.cimvocabcheck.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Deserialized form of the {@code "cimvocabcheck"} section of {@code opencgmes.jsonc}, shared by
 * the CLI, the LSP server and any other OpenCGMES tooling.
 *
 * <p>All fields are optional. When none of {@code schemas}, {@code schemasDirectory} or {@code
 * rdfArchitect} is given, no schema is loaded and inputs are checked syntax-only — there is no
 * bundled default schema (the LSP additionally honours a {@code # [endpoint=...]} directive in a
 * document).
 *
 * <p>Example {@code opencgmes.jsonc}:
 *
 * <pre>{@code
 * {
 *   "cimvocabcheck": {
 *     "schemasDirectory": "schemas",
 *     "namedGraphs": {
 *       "urn:uuid:eq-network": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Use either {@code schemasDirectory} (auto-discovers all {@code .rdf}/{@code .ttl}/{@code .owl}
 * files) or an explicit {@code schemas} list, not both.
 *
 * <p>{@code rdfArchitect} takes the schema from a running <a
 * href="https://github.com/SOPTIM/RDFArchitect">RDFArchitect</a> instead of from files — the value
 * is a link copied out of that application, e.g. {@code "http://localhost:3000/?snapshot=<token>"}.
 * It takes precedence over the file-based settings.
 *
 * <p>{@code cimNamespaces} declares custom {@code cim} namespaces so schema files/endpoint graphs
 * using them can be parsed, mapping each namespace URI to the built-in profile "shape" that matches
 * its ontology conventions ({@code cim16}: {@code cims:isFixed}-based, as in CGMES 2.4.15; {@code
 * cim17}/{@code cim18}: {@code owl:versionIRI}/{@code dcat:keyword}-based, as in CGMES 3.0+). See
 * {@link CimProfileShapes} and {@link ConfigLoader#load(java.nio.file.Path)}, which registers these
 * mappings with {@code CimNamespaceFactoryRegistry} on load.
 */
public record CimvocabcheckConfig(
    @JsonProperty("schemasDirectory") String schemasDirectory,
    @JsonProperty("schemas") List<String> schemas,
    @JsonProperty("rdfArchitect") String rdfArchitect,
    @JsonProperty("namedGraphs") Map<String, List<String>> namedGraphs,
    @JsonProperty("strictness") String strictness,
    @JsonProperty("prefixes") Map<String, String> prefixes,
    @JsonProperty("standardVocabulary") String standardVocabulary,
    @JsonProperty("cimNamespaces") Map<String, String> cimNamespaces) {

  /**
   * Canonical constructor; defaults the {@code schemas}, {@code namedGraphs}, and {@code
   * cimNamespaces} collections.
   */
  public CimvocabcheckConfig {
    if (schemas == null) {
      schemas = List.of();
    }
    if (namedGraphs == null) {
      namedGraphs = Map.of();
    }
    if (cimNamespaces == null) {
      cimNamespaces = Map.of();
    }
    // prefixes: null means "use built-in defaults", empty map means "no defaults"
  }

  /** An empty config: no schemas, no overrides — i.e. syntax-only validation. */
  public static CimvocabcheckConfig empty() {
    return new CimvocabcheckConfig(null, null, null, null, null, null, null, null);
  }

  /**
   * Returns whether {@link #namedGraphs()} is non-null and non-empty.
   *
   * @return {@code true} iff {@link #namedGraphs()} is non-null and non-empty.
   */
  public boolean hasNamedGraphs() {
    return namedGraphs != null && !namedGraphs.isEmpty();
  }

  /** Returns whether {@link #cimNamespaces()} is non-null and non-empty. */
  public boolean hasCimNamespaces() {
    return cimNamespaces != null && !cimNamespaces.isEmpty();
  }

  /**
   * Resolves {@link #standardVocabulary()} to a boolean: whether terms in the closed standard
   * vocabularies ({@code rdf}/{@code rdfs}/{@code owl}/{@code sh}) are checked for typos. Returns
   * {@code true} (check) unless explicitly set to {@code "ignore"} (case-insensitive); unknown
   * values fall back to checking enabled.
   */
  public boolean checkStandardVocabulary() {
    return standardVocabulary == null || !standardVocabulary.trim().equalsIgnoreCase("ignore");
  }
}
