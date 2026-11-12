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

package de.soptim.opencgmes.cimxmlcli.model;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.jena.graph.Node;

/**
 * A parsed CIMXML source.
 *
 * @param displayPath path of the source, see {@link
 *     de.soptim.opencgmes.cimxmlcli.source.CimSource#displayPath()}
 * @param dataset the parsed model with header and body
 * @param headerProfiles the {@code md:Model.profile} IRIs of the header (empty without header)
 * @param profiles the registered RDFS profiles matching {@code headerProfiles} (empty if none or
 *     not all of them match)
 * @param cimNamespace the namespace bound to the {@code cim} prefix, or {@code null}
 * @param warnings the parser warnings of this source
 */
public record ParsedModel(
    String displayPath,
    CimDatasetGraph dataset,
    Set<Node> headerProfiles,
    Set<CimProfile> profiles,
    String cimNamespace,
    WarningSummary warnings) {

  /** Creates a parsed model. */
  public ParsedModel {
    headerProfiles = Set.copyOf(headerProfiles);
    profiles = Set.copyOf(profiles);
  }

  /** Returns the distinct profile keywords of the matching profiles, sorted. */
  public List<String> keywords() {
    return profiles.stream()
        .map(CimProfile::getDcatKeyword)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
  }
}
