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

package de.soptim.opencgmes.sparql.validation.shacl;

import de.soptim.opencgmes.sparql.validation.SparqlValidationSeverity;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated outcome of running the SPARQL validator on every embedded query found in a SHACL
 * shapes graph.
 *
 * @param embeddedResults  one entry per discovered SPARQL fragment, in extraction order
 */
public record ShaclValidationResult(List<ShaclEmbeddedQueryResult> embeddedResults) {

    public ShaclValidationResult {
        Objects.requireNonNull(embeddedResults, "embeddedResults");
        embeddedResults = List.copyOf(embeddedResults);
    }

    /** @return {@code true} iff no embedded query produced an {@code ERROR} annotation. */
    public boolean isValid() {
        for (var r : embeddedResults) {
            for (var a : r.result().annotations()) {
                if (a.severity() == SparqlValidationSeverity.ERROR) return false;
            }
        }
        return true;
    }

    /** @return total annotation count across all embedded queries. */
    public int totalAnnotations() {
        int n = 0;
        for (var r : embeddedResults) n += r.result().annotations().size();
        return n;
    }
}
