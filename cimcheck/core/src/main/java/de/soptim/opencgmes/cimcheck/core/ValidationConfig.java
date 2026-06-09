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

package de.soptim.opencgmes.cimcheck.core;

import java.util.List;
import java.util.Map;

/**
 * Common interface for {@code .cgmes/validation.json} config records shared by the CLI and
 * LSP modules.
 *
 * <p>Both {@code CliConfig} and {@code LspConfig} implement this interface. Having the shared
 * contract here ensures that adding a new config field requires updating both records, so
 * they cannot silently diverge.</p>
 */
public interface ValidationConfig {

    /** Path to a directory containing schema files, or {@code null}. */
    String schemasDirectory();

    /** Explicit list of schema file paths (alternative to {@link #schemasDirectory()}). */
    List<String> schemas();

    /** Per-graph profile scope: graph-key → list of version IRI strings. */
    Map<String, List<String>> namedGraphs();

    /** Validation strictness level string ({@code "permissive"}, {@code "default"}, etc.). */
    String strictness();

    /** Custom prefix map, or {@code null} to use built-in defaults. */
    Map<String, String> prefixes();

    /** @return {@code true} iff {@link #namedGraphs()} is non-null and non-empty. */
    default boolean hasNamedGraphs() {
        Map<String, List<String>> ng = namedGraphs();
        return ng != null && !ng.isEmpty();
    }
}
