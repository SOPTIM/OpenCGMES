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

import de.soptim.opencgmes.cimxml.graph.CimProfile16;
import de.soptim.opencgmes.cimxml.graph.CimProfile17;
import de.soptim.opencgmes.cimxml.graph.CimProfile18;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * All FullModels must use the same CIM version (namespace of the {@code cim} prefix). The schema
 * folder may contain profiles of several versions; only the models are checked. Files that are not
 * FullModels are already reported by {@link FullModelOnlyRule}.
 */
public final class SingleCimVersionRule implements ValidationRule {

  private static final Map<String, String> VERSION_NAMES =
      Map.of(
          CimProfile16.CIM_NAMESPACE, "CGMES 2.4.15",
          CimProfile17.CIM_NAMESPACE, "CGMES 3.0",
          CimProfile18.CIM_NAMESPACE, "CIM 18");

  @Override
  public List<Problem> check(ConversionContext context) {
    final var pathsByNamespace = new TreeMap<String, List<String>>();
    for (var model : context.models()) {
      if (model.dataset().isFullModel() && model.cimNamespace() != null) {
        pathsByNamespace
            .computeIfAbsent(model.cimNamespace(), k -> new ArrayList<>())
            .add(model.displayPath());
      }
    }
    if (pathsByNamespace.size() <= 1) {
      return List.of();
    }
    final var versions =
        pathsByNamespace.entrySet().stream()
            .map(e -> describe(e.getKey()) + " " + e.getValue())
            .collect(Collectors.joining(", "));
    return List.of(
        new Problem(
            "model folder",
            "the models use different CIM versions: " + versions,
            "Convert one CGMES version per run."));
  }

  private static String describe(String namespace) {
    final var name = VERSION_NAMES.get(namespace);
    return name == null ? namespace : name + " (" + namespace + ")";
  }
}
