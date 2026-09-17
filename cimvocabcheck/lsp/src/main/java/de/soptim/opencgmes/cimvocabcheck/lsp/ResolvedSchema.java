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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import de.soptim.opencgmes.cimvocabcheck.core.RuleSeverities;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Node;

/**
 * The schema a single document is validated against, bundled with the strictness and named-graph
 * scope that should be applied to its annotations.
 *
 * <p>The workspace schema (from {@code opencgmes.jsonc}) and schemas loaded from a SPARQL Notebook
 * {@code # [endpoint=...]} directive are both represented uniformly here so the validation path
 * does not care where the schema came from.
 *
 * @param rules the config's per-check severity overrides; applied before {@code strictness}
 * @param definitionIndex go-to-definition source index, or {@code null} when the schema has no
 *     backing source file to navigate to — a remote SPARQL endpoint, whose terms are instead
 *     resolved via {@link EndpointDefinitionPeek}. Non-null for the workspace schema and for a
 *     local-file {@code # [endpoint=...]} schema, both of which have a real file to jump to.
 */
record ResolvedSchema(
    SparqlValidationApi api,
    StrictnessLevel strictness,
    RuleSeverities rules,
    Map<Node, Collection<VersionIri>> namedGraphScope,
    DefinitionIndex definitionIndex) {

  /** Canonical constructor; defaults {@code rules} to {@link RuleSeverities#NONE}. */
  ResolvedSchema {
    rules = rules == null ? RuleSeverities.NONE : rules;
  }

  /**
   * Applies this document's severity policy — per-check overrides first, then the strictness level
   * — to raw validation findings.
   */
  List<SparqlValidationAnnotation> effective(List<SparqlValidationAnnotation> annotations) {
    return rules.apply(annotations, strictness);
  }
}
