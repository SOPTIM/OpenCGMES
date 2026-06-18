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

import de.soptim.opencgmes.cimcheck.core.SparqlValidationResult;
import java.util.Objects;

/**
 * Result for a single SPARQL fragment extracted from a SHACL shapes graph.
 *
 * @param embedded the fragment as it was discovered (container, kind, prefixes, raw text)
 * @param result the {@link SparqlValidationResult} produced by running the query through the normal
 *     SPARQL validator after SHACL prefixes were prepended
 */
public record ShaclEmbeddedQueryResult(EmbeddedSparql embedded, SparqlValidationResult result) {

  /** Canonical constructor; validates the required fields. */
  public ShaclEmbeddedQueryResult {
    Objects.requireNonNull(embedded, "embedded");
    Objects.requireNonNull(result, "result");
  }
}
