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

package de.soptim.opencgmes.cimcheck.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Deserialized form of the {@code "cimcheck"} section of {@code opencgmes.json}, shared by the CLI,
 * the LSP server and any other OpenCGMES tooling.
 *
 * <p>All fields are optional. When neither {@code schemas} nor {@code schemasDirectory} is given,
 * no schema is loaded and inputs are checked syntax-only — there is no bundled default schema (the
 * LSP additionally honours a {@code # [endpoint=...]} directive in a document).</p>
 *
 * <p>Example {@code opencgmes.json}:</p>
 * <pre>{@code
 * {
 *   "cimcheck": {
 *     "schemasDirectory": "schemas",
 *     "namedGraphs": {
 *       "urn:uuid:eq-network": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Use either {@code schemasDirectory} (auto-discovers all {@code .rdf}/{@code .ttl}/{@code .owl}
 * files) or an explicit {@code schemas} list, not both.</p>
 */
public record CimcheckConfig(
        @JsonProperty("schemasDirectory")   String schemasDirectory,
        @JsonProperty("schemas")            List<String> schemas,
        @JsonProperty("namedGraphs")        Map<String, List<String>> namedGraphs,
        @JsonProperty("strictness")         String strictness,
        @JsonProperty("prefixes")           Map<String, String> prefixes,
        @JsonProperty("standardVocabulary") String standardVocabulary
) {

    public CimcheckConfig {
        if (schemas     == null) schemas     = List.of();
        if (namedGraphs == null) namedGraphs = Map.of();
        // prefixes: null means "use built-in defaults", empty map means "no defaults"
    }

    /** An empty config: no schemas, no overrides — i.e. syntax-only validation. */
    public static CimcheckConfig empty() {
        return new CimcheckConfig(null, null, null, null, null, null);
    }

    /** @return {@code true} iff {@link #namedGraphs()} is non-null and non-empty. */
    public boolean hasNamedGraphs() {
        return namedGraphs != null && !namedGraphs.isEmpty();
    }

    /**
     * Resolves {@link #standardVocabulary()} to a boolean: whether terms in the closed standard
     * vocabularies ({@code rdf}/{@code rdfs}/{@code owl}/{@code sh}) are checked for typos.
     * Returns {@code true} (check) unless explicitly set to {@code "ignore"} (case-insensitive);
     * unknown values fall back to checking enabled.
     */
    public boolean checkStandardVocabulary() {
        return standardVocabulary == null || !standardVocabulary.trim().equalsIgnoreCase("ignore");
    }
}
