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

package de.soptim.opencgmes.cimcheck.core.shacl;

import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationSeverity;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated outcome of validating a SHACL shapes graph against a CIM schema.
 *
 * <p>Contains two complementary result sets:</p>
 * <ul>
 *   <li>{@link #shapeAnnotations} — errors found in the shape <em>structure</em> itself
 *       ({@code sh:targetClass}, {@code sh:class}, {@code sh:path}), produced by
 *       {@link ShaclShapeAnalyzer}.</li>
 *   <li>{@link #embeddedResults} — one entry per SPARQL fragment embedded in the shapes graph
 *       ({@code sh:select}, {@code sh:ask}, {@code sh:construct}), produced by
 *       {@link ShaclSparqlExtractor} + the normal SPARQL validator.</li>
 * </ul>
 */
public record ShaclValidationResult(
        List<SparqlValidationAnnotation> shapeAnnotations,
        List<ShaclEmbeddedQueryResult> embeddedResults) {

    public ShaclValidationResult {
        Objects.requireNonNull(shapeAnnotations, "shapeAnnotations");
        Objects.requireNonNull(embeddedResults, "embeddedResults");
        shapeAnnotations = List.copyOf(shapeAnnotations);
        embeddedResults  = List.copyOf(embeddedResults);
    }

    /** @return {@code true} iff neither shape structure nor any embedded query produced an ERROR. */
    public boolean isValid() {
        for (var a : shapeAnnotations) {
            if (a.severity() == SparqlValidationSeverity.ERROR) return false;
        }
        for (var r : embeddedResults) {
            for (var a : r.result().annotations()) {
                if (a.severity() == SparqlValidationSeverity.ERROR) return false;
            }
        }
        return true;
    }

    /** @return total annotation count across shape structure and all embedded queries. */
    public int totalAnnotations() {
        int n = shapeAnnotations.size();
        for (var r : embeddedResults) n += r.result().annotations().size();
        return n;
    }
}
