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

package de.soptim.opencgmes.cimreader.core.validation;

import de.soptim.opencgmes.cimreader.core.source.ModelSource;
import de.soptim.opencgmes.cimxml.graph.CimModelHeader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Node;

public class CimValidator {

  private final List<ValidationIssue> issues = new ArrayList<>();

  public void validateHeader(CimModelHeader header) {
    if (header.getModel() == null) {
      issues.add(new MissingHeaderFieldIssue(header, "model"));
    }
    if (header.getVersion() == null) {
      issues.add(new MissingHeaderFieldIssue(header, "version"));
    }
    if (header.getModelingAuthoritySet() == null) {
      issues.add(new MissingHeaderFieldIssue(header, "modelingAuthoritySet"));
    }
    if (header.getProfiles() == null || header.getProfiles().isEmpty()) {
      issues.add(new MissingHeaderFieldIssue(header, "profiles"));
    }
    if (header.getScenarioTime() == null) {
      issues.add(new MissingHeaderFieldIssue(header, "scenarioTime"));
    }
    if (header.isDifferenceModel() && (header.getSupersedes() == null || header.getSupersedes()
        .isEmpty())) {
      issues.add(new MissingHeaderFieldIssue(header, "supersedes"));
    }
  }

  public void validateHeaders(Collection<ModelSource> sources) {
    for (var source : sources) {
      validateHeader(source.header());
    }
  }

  public void validateDependencies(Collection<ModelSource> sources) {
    for (var source : sources) {
      source.header().getDependentOn().forEach(dependency -> {
        if (sources.stream().noneMatch(s -> s.header().getModel().equals(dependency))) {
          issues.add(new MissingDependencyIssue(source.header(), dependency.toString()));
        }
      });
    }
  }

  public void validateCycles(Collection<ModelSource> sources) {
    Map<Node, ModelSource> byModelId = new HashMap<>();
    sources.forEach(source -> byModelId.put(source.header().getModel(), source));
    Map<ModelSource, DFSColor> colours = new HashMap<>();
    Map<ModelSource, ModelSource> previous = new HashMap<>();
    sources.forEach(source -> colours.put(source, DFSColor.WHITE));
    for (var source : sources) {
      if (colours.get(source).equals(DFSColor.WHITE)) {
        visitDFS(source, byModelId, colours, previous);
      }
    }
  }

  private void visitDFS(ModelSource source, Map<Node, ModelSource> byModelId,
      Map<ModelSource, DFSColor> colours, Map<ModelSource, ModelSource> previous) {
    colours.put(source, DFSColor.GRAY);
    if (source.header().isFullModel()) {
      colours.put(source, DFSColor.BLACK);
      return;
    }
    for (var superseded : source.header().getSupersedes()) {
      if (!byModelId.containsKey(superseded)) {
        issues.add(new MissingDependencyIssue(source.header(), superseded.toString()));
        continue;
      }
      var supersededSource = byModelId.get(superseded);
      if (colours.get(supersededSource).equals(DFSColor.WHITE)) {
        previous.put(supersededSource, source);
        visitDFS(supersededSource, byModelId, colours, previous);
      }
      if (colours.get(supersededSource).equals(DFSColor.GRAY)) {
        List<ModelSource> cycle = new ArrayList<>();
        ModelSource current = source;
        cycle.add(current);
        while (!current.equals(supersededSource) && previous.containsKey(current)) {
          current = previous.get(current);
          cycle.add(current);
        }
        Collections.reverse(cycle);
        issues.add(new CyclicDependencyIssue(cycle));
      }
    }
    colours.put(source, DFSColor.BLACK);
  }

  public void validate(Collection<ModelSource> sources) {
    validateHeaders(sources);
    validateDependencies(sources);
    validateCycles(sources);
  }

  public List<ValidationIssue> getIssues() {
    return issues;
  }

  public boolean hasErrors() {
    return !issues.isEmpty();
  }

  private enum DFSColor {
    WHITE, GRAY, BLACK
  }

}
