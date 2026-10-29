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

import de.soptim.opencgmes.cimreader.core.source.HeaderSource;
import de.soptim.opencgmes.cimxml.graph.CimModelHeader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CimSorter {

  public static List<List<HeaderSource>> sort(List<HeaderSource> headerSources) {
    Map<String, Map<String, Map<Integer, List<HeaderSource>>>> m = new TreeMap<>();
    for (var headerSource : headerSources) {
      m.computeIfAbsent(getModelAuthoritySet(headerSource.header()), _ -> new TreeMap<>())
          .computeIfAbsent(getProfiles(headerSource.header()), _ -> new TreeMap<>())
          .computeIfAbsent(getVersion(headerSource.header()), _ -> new ArrayList<>())
          .add(headerSource);
    }
    List<List<HeaderSource>> result = new LinkedList<>();
    for (var masEntry : m.entrySet()) {
      for (var profileEntry : masEntry.getValue().entrySet()) {
        for (var versionEntry : profileEntry.getValue().entrySet()) {
          List<HeaderSource> sortedList = versionEntry.getValue();
          sortedList.sort(Comparator.comparing(h -> getScenarioTime(h.header())));
          result.add(sortedList);
        }
      }
    }

    return result;
  }

  private static String getModelAuthoritySet(CimModelHeader header) {
    return header.getModelingAuthoritySet() == null ? null : header.getModelingAuthoritySet().getLiteralLexicalForm().trim();
  }

  private static int getVersion(CimModelHeader header) {
    return header.getVersion() == null ? -1 : Integer.parseInt(header.getVersion()
        .getLiteralLexicalForm().trim());
  }

  private static Instant getScenarioTime(CimModelHeader header) {
    return header.getScenarioTime() == null ? Instant.MIN : Instant.parse(header.getScenarioTime().getLiteralLexicalForm().trim());
  }

  private static String getProfiles(CimModelHeader header) {
    if (header.getProfiles() == null || header.getProfiles().isEmpty()) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    for (var profile : header.getProfiles()) {
      builder.append(profile.getLiteralLexicalForm().trim()).append(",");
    }
    return builder.substring(0, builder.length() - 1);
  }



}
