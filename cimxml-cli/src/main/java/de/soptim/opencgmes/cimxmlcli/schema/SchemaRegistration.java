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

package de.soptim.opencgmes.cimxmlcli.schema;

import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.util.List;

/**
 * The result of registering the loaded RDFS profiles.
 *
 * @param registered the profiles that were registered, in the order they were found
 * @param problems profiles that could not be registered
 * @param warnings profiles that were ignored in favour of an equivalent one
 */
public record SchemaRegistration(
    List<LoadedSchema> registered, List<Problem> problems, List<Problem> warnings) {

  /** Creates a registration result. */
  public SchemaRegistration {
    registered = List.copyOf(registered);
    problems = List.copyOf(problems);
    warnings = List.copyOf(warnings);
  }
}
