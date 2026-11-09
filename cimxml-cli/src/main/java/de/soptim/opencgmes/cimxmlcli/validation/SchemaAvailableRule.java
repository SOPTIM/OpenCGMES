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

import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Node;

/** Every FullModel must reference RDFS profiles that are present in the schema folder. */
public final class SchemaAvailableRule implements ValidationRule {

  @Override
  public List<Problem> check(ConversionContext context) {
    final var problems = new ArrayList<Problem>();
    for (var model : context.models()) {
      if (!model.dataset().isFullModel() || !model.profiles().isEmpty()) {
        continue;
      }
      if (model.headerProfiles().isEmpty()) {
        problems.add(
            Problem.of(model.displayPath(), "the md:FullModel header lists no md:Model.profile"));
      } else {
        problems.add(
            new Problem(
                model.displayPath(),
                "no RDFS profile in the schema folder matches md:Model.profile "
                    + model.headerProfiles().stream().map(Node::toString).sorted().toList(),
                "Add the RDFS file(s) of this profile to the schema folder."));
      }
    }
    return problems;
  }
}
