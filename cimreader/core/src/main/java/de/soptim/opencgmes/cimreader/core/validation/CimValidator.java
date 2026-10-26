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

import de.soptim.opencgmes.cimreader.core.source.HeaderSource;
import de.soptim.opencgmes.cimxml.graph.CimModelHeader;
import java.util.ArrayList;
import java.util.List;

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
  }

  public void validateHeaders(List<HeaderSource> headers) {
    for (var source : headers) {
      validateHeader(source.header());
    }
  }

  public void validateDependencies(List<HeaderSource> sources) {
    for (var source : sources) {
      source.header().getDependentOn().forEach(dependency -> {
        if (sources.stream().noneMatch(s -> s.header().getModel().equals(dependency))) {
          issues.add(new MissingDependencyIssue(source, dependency.toString()));
        }
      });
    }
  }

  public List<ValidationIssue> getIssues() {
    return issues;
  }

  public boolean hasErrors() {
    return !issues.isEmpty();
  }

}
