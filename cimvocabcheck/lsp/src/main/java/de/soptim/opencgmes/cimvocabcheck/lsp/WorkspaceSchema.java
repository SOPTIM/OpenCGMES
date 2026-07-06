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

import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import java.util.Collection;
import java.util.Map;
import org.apache.jena.graph.Node;

/**
 * The fully-resolved validation context for one {@code opencgmes.jsonc} config source (or the
 * bundled default). One of these is built per discovered config and reused for every document whose
 * nearest config is that file.
 *
 * <p>Unlike {@link ResolvedSchema} (which also covers per-document {@code # [endpoint=...]}
 * overrides — themselves also carrying a {@link DefinitionIndex} when backed by a local file), this
 * additionally bundles the {@code checkStandardVocabulary} flag from the config.
 *
 * @param api the validation API built from the config's schemas
 * @param level the strictness level from the config
 * @param definitionIndex the source-navigation index
 * @param namedGraphScope the per-graph profile scope from {@code namedGraphs}
 * @param checkStandardVocab whether standard-vocabulary typo checking is enabled
 */
record WorkspaceSchema(
    SparqlValidationApi api,
    StrictnessLevel level,
    DefinitionIndex definitionIndex,
    Map<Node, Collection<VersionIri>> namedGraphScope,
    boolean checkStandardVocab) {

  /** Adapts this workspace schema to a {@link ResolvedSchema} for the document-validation path. */
  ResolvedSchema toResolvedSchema() {
    return new ResolvedSchema(api, level, namedGraphScope, definitionIndex);
  }
}
