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

/** Only FullModels are supported: rejects DifferenceModels and files without md:FullModel. */
public final class FullModelOnlyRule implements ValidationRule {

  @Override
  public List<Problem> check(ConversionContext context) {
    final var problems = new ArrayList<Problem>();
    for (var model : context.models()) {
      if (model.dataset().isDifferenceModel()) {
        problems.add(
            new Problem(
                model.displayPath(),
                "is a DifferenceModel; only FullModels are supported",
                "Use the complete (FullModel) export of the IGM."));
      } else if (!model.dataset().isFullModel()) {
        problems.add(
            new Problem(
                model.displayPath(),
                "has no md:FullModel header; files without header and NC-style headers"
                    + " (dcat:Dataset) are not supported",
                "Only put CGMES FullModel files into the model folder."));
      }
    }
    return problems;
  }
}
