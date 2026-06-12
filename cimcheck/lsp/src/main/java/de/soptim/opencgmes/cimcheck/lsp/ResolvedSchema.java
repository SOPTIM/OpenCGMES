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

package de.soptim.opencgmes.cimcheck.lsp;

import de.soptim.opencgmes.cimcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import org.apache.jena.graph.Node;

import java.util.Collection;
import java.util.Map;

/**
 * The schema a single document is validated against, bundled with the strictness and
 * named-graph scope that should be applied to its annotations.
 *
 * <p>The default workspace schema (from {@code .cgmes/validation.json}) and schemas loaded from
 * a SPARQL Notebook {@code # [endpoint=...]} directive are both represented uniformly here so the
 * validation path does not care where the schema came from.</p>
 */
record ResolvedSchema(
        SparqlValidationApi api,
        StrictnessLevel strictness,
        Map<Node, Collection<VersionIri>> namedGraphScope) {
}
