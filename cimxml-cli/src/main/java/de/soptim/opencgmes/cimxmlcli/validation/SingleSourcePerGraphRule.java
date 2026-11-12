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

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxmlcli.dataset.GraphNames;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every graph of the dataset must come from exactly one file: one CIMXML file per {@code
 * cgmes:model:<keyword>} graph (the IGM check) and one RDFS file per referenced {@code
 * cgmes:schema:<keyword>} graph. Otherwise, one file would silently overwrite the other. A CIMXML
 * file that declares several profiles counts for the model graph of each of them.
 */
public final class SingleSourcePerGraphRule implements ValidationRule {

  @Override
  public List<Problem> check(ConversionContext context) {
    final var problems = new ArrayList<Problem>();
    checkModelGraphs(context, problems);
    checkSchemaGraphs(context, problems);
    return problems;
  }

  private static void checkModelGraphs(ConversionContext context, List<Problem> problems) {
    final var pathsByKeyword = new TreeMap<String, List<String>>();
    for (var model : context.convertibleModels()) {
      for (var keyword : model.keywords()) {
        pathsByKeyword.computeIfAbsent(keyword, k -> new ArrayList<>()).add(model.displayPath());
      }
    }
    pathsByKeyword.forEach(
        (keyword, paths) -> {
          if (paths.size() > 1) {
            problems.add(
                new Problem(
                    GraphNames.MODEL_PREFIX + keyword,
                    paths.size() + " CIMXML files map to the same graph: " + paths,
                    "Only one IGM per run is supported (one file per profile); remove all but"
                        + " one of them."));
          }
        });
  }

  private static void checkSchemaGraphs(ConversionContext context, List<Problem> problems) {
    // By identity: the registry returns the loaded instances
    final var pathByProfile = new IdentityHashMap<CimProfile, String>();
    for (var schema : context.schemas()) {
      pathByProfile.put(schema.profile(), schema.displayPath());
    }
    final var pathsByKeyword = new TreeMap<String, Set<String>>();
    for (var model : context.convertibleModels()) {
      for (var profile : model.profiles()) {
        pathsByKeyword
            .computeIfAbsent(profile.getDcatKeyword(), k -> new LinkedHashSet<>())
            .add(pathByProfile.getOrDefault(profile, "<unknown>"));
      }
    }
    pathsByKeyword.forEach(
        (keyword, paths) -> {
          if (paths.size() > 1) {
            problems.add(
                new Problem(
                    GraphNames.SCHEMA_PREFIX + keyword,
                    "the models reference "
                        + paths.size()
                        + " RDFS files for the same graph: "
                        + List.copyOf(paths),
                    "Use model files that reference one version of each profile."));
          }
        });
  }
}
