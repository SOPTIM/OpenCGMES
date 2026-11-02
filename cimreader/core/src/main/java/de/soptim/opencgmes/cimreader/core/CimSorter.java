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

package de.soptim.opencgmes.cimreader.core;

import de.soptim.opencgmes.cimreader.core.source.ModelSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CimSorter {

  private CimSorter() {
  }

  public static List<List<ModelSource>> groupAndSort(Iterable<ModelSource> modelSources) {
    Map<ModelSourceKey, List<ModelSource>> m = new HashMap<>();
    for (var modelSource : modelSources) {
      m.computeIfAbsent(
              new ModelSourceKey(getModelAuthoritySet(modelSource), getProfiles(modelSource),
                  getVersion(modelSource)), _ -> new ArrayList<>())
          .add(modelSource);
    }
    List<List<ModelSource>> result = new ArrayList<>(m.size());
    for (var entry : m.entrySet()) {
      List<ModelSource> sortedList = entry.getValue();
      sortedList.sort(Comparator.comparing(CimSorter::getScenarioTime));
      result.add(sortedList);
    }
    return result;
  }

  private static String getModelAuthoritySet(ModelSource modelSource) {
    return modelSource.header().getModelingAuthoritySet() == null ? null
        : modelSource.header().getModelingAuthoritySet().getLiteralLexicalForm().trim();
  }

  private static int getVersion(ModelSource modelSource) {
    return modelSource.header().getVersion() == null ? -1
        : Integer.parseInt(modelSource.header().getVersion()
            .getLiteralLexicalForm().trim());
  }

  private static Instant getScenarioTime(ModelSource modelSource) {
    return modelSource.header().getScenarioTime() == null ? Instant.MIN
        : Instant.parse(modelSource.header().getScenarioTime().getLiteralLexicalForm().trim());
  }

  private static String getProfiles(ModelSource modelSource) {
    if (modelSource.header().getProfiles() == null || modelSource.header().getProfiles()
        .isEmpty()) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    for (var profile : modelSource.header().getProfiles()) {
      builder.append(profile.getLiteralLexicalForm().trim()).append(",");
    }
    return builder.substring(0, builder.length() - 1);
  }

  private record ModelSourceKey(String modelAuthoritySet, String profiles, int version) {

  }

}
