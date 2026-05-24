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

package de.soptim.opencgmes.cimcheck.lsp.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Deserialized form of {@code .cgmes/validation.json}.
 *
 * <p>Example:</p>
 * <pre>{@code
 * {
 *   "schemasDirectory": ".cgmes/schemas",
 *   "namedGraphs": {
 *     "urn:uuid:eq-network": "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"
 *   }
 * }
 * }</pre>
 */
public record LspConfig(
        @JsonProperty("schemasDirectory") String schemasDirectory,
        @JsonProperty("schemas")          List<String> schemas,
        @JsonProperty("namedGraphs")      Map<String, String> namedGraphs,
        @JsonProperty("strictness")       String strictness
) {
    public LspConfig {
        if (schemas     == null) schemas     = List.of();
        if (namedGraphs == null) namedGraphs = Map.of();
    }

    public boolean hasNamedGraphs() {
        return !namedGraphs.isEmpty();
    }
}
