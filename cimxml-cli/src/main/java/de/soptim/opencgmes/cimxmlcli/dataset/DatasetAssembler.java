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

package de.soptim.opencgmes.cimxmlcli.dataset;

import de.soptim.opencgmes.cimxml.sparql.core.LinkedCimDatasetGraph;
import de.soptim.opencgmes.cimxmlcli.validation.ConversionContext;
import java.util.HashSet;

/**
 * Assembles the validated input into one dataset with the graphs {@code cgmes:schema:<keyword>},
 * {@code cgmes:header:<keyword>} and {@code cgmes:model:<keyword>}. The graphs are linked, not
 * copied.
 *
 * <p>A model that declares several profiles, like a CGMES 3.0 EQ file that also declares Operation
 * and ShortCircuit, is added under each of their keywords. SPARQL queries can then use the graph of
 * each profile, whether the data came in one file or in several.
 */
public final class DatasetAssembler {

  /** Header profiles have no keyword of their own; their graph is {@code cgmes:schema:FH}. */
  public static final String HEADER_KEYWORD = "FH";

  /** Assembles the dataset; {@code context} must have passed validation. */
  public LinkedCimDatasetGraph assemble(ConversionContext context) {
    final var dataset = new LinkedCimDatasetGraph();
    final var cimNamespaces = new HashSet<String>();
    for (var model : context.convertibleModels()) {
      for (var keyword : model.keywords()) {
        dataset.addGraph(GraphNames.header(keyword), model.dataset().getModelHeader());
        dataset.addGraph(GraphNames.model(keyword), model.dataset().getBody());
      }
      dataset.prefixes().putAll(model.dataset().prefixes());
      cimNamespaces.add(model.cimNamespace());
      // Only the referenced profiles, not the whole schema folder
      for (var profile : model.profiles()) {
        dataset.addGraph(GraphNames.schema(profile.getDcatKeyword()), profile);
        dataset.prefixes().putAll(profile.getPrefixMapping());
      }
    }
    // The header profile matching the CIM version of the models
    for (var schema : context.schemas()) {
      final var profile = schema.profile();
      if (profile.isHeaderProfile() && cimNamespaces.contains(profile.getCimNamespace())) {
        dataset.addGraph(GraphNames.schema(HEADER_KEYWORD), profile);
        dataset.prefixes().putAll(profile.getPrefixMapping());
      }
    }
    return dataset;
  }
}
