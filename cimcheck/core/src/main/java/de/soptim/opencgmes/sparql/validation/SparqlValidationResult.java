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

package de.soptim.opencgmes.sparql.validation;

import java.util.List;
import java.util.Objects;

/**
 * Result of a {@link SparqlValidationApi#validateSparql(String)} call.
 *
 * <p>Designed for direct JSON serialization (e.g. via Jackson or {@code java.net.http} clients)
 * with the shape documented in the module README.</p>
 *
 * @param query        the original query string as supplied by the caller
 * @param queryPlan    formatted SPARQL algebra plan in Jena SSE syntax, or {@code null} when the
 *                     query could not be parsed at all
 * @param annotations  ordered list of findings; empty list means "no problems"
 */
public record SparqlValidationResult(
        String query,
        String queryPlan,
        List<SparqlValidationAnnotation> annotations
) {

    public SparqlValidationResult {
        Objects.requireNonNull(query, "query");
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** @return {@code true} iff no annotation has {@link SparqlValidationSeverity#ERROR}. */
    public boolean isValid() {
        return annotations.stream().noneMatch(a -> a.severity() == SparqlValidationSeverity.ERROR);
    }

    /**
     * Returns {@code true} iff the annotation list, after applying {@code level},
     * contains no {@link SparqlValidationSeverity#ERROR} entries.
     *
     * <p>Equivalent to {@code level.apply(annotations()).stream().noneMatch(ERROR)}.</p>
     */
    public boolean isValid(StrictnessLevel level) {
        return level.apply(annotations).stream()
                .noneMatch(a -> a.severity() == SparqlValidationSeverity.ERROR);
    }
}
