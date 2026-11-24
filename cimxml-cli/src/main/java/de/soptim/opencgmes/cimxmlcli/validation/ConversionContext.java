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

package de.soptim.opencgmes.cimxmlcli.validation;

import de.soptim.opencgmes.cimxmlcli.model.ParsedModel;
import de.soptim.opencgmes.cimxmlcli.schema.LoadedSchema;
import java.util.List;

/**
 * Everything read from the input folders.
 *
 * @param schemas the loaded RDFS profiles, in the order they were found
 * @param models the parsed CIMXML models, in the order they were found
 * @param warnings issues that do not prevent the conversion, e.g. ignored duplicate profiles
 */
public record ConversionContext(
    List<LoadedSchema> schemas, List<ParsedModel> models, List<Problem> warnings) {

  /** Creates a context. */
  public ConversionContext {
    schemas = List.copyOf(schemas);
    models = List.copyOf(models);
    warnings = List.copyOf(warnings);
  }

  /**
   * Returns the FullModels with at least one matching profile keyword. A model that declares
   * several profiles belongs to the graph of each of them.
   */
  public List<ParsedModel> convertibleModels() {
    return models.stream()
        .filter(m -> m.dataset().isFullModel() && !m.keywords().isEmpty())
        .toList();
  }
}
