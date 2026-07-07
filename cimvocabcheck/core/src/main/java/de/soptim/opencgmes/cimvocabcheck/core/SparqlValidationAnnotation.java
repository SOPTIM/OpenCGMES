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

package de.soptim.opencgmes.cimvocabcheck.core;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.apache.jena.graph.Node;

/**
 * One finding produced by {@link SparqlValidationApi#validateSparql(String)}.
 *
 * <p>Annotations are structured data, not just formatted strings — the rendered {@link #message()}
 * is convenient for log output, while the {@link #code()}, {@link #term()}, {@link #graph()},
 * {@link #selectedProfiles()} and {@link #foundInOtherProfiles()} fields are meant to be consumed
 * programmatically (e.g. by a query editor highlighting a token).
 *
 * @param severity error / warn / info
 * @param line 1-based line in the original query string, or {@code null}
 * @param column 1-based column in the original query string, or {@code null}
 * @param message human-readable rendering of the finding
 * @param code stable enum identifier of the rule that triggered
 * @param term the offending RDF term (class IRI, property IRI, …) or {@code null}
 * @param selectedProfiles profiles that were in scope at the offending location (may be empty)
 * @param foundInOtherProfiles profiles in which the term <em>does</em> exist, hint for the user
 * @param graph named graph context, or {@code null} for default-graph context
 * @param locationHint a second node — typically the enclosing shape/subject — used to disambiguate
 *     which occurrence of {@link #term()} in the source text this annotation refers to, when the
 *     same term is referenced more than once (e.g. the same unknown class used by two different
 *     shapes). {@code null} when no such hint is available or applicable; {@link
 *     SourceLocator#locateWithHint} falls back to the first occurrence of {@link #term()} in that
 *     case.
 */
public record SparqlValidationAnnotation(
    SparqlValidationSeverity severity,
    Integer line,
    Integer column,
    String message,
    SparqlValidationCode code,
    Node term,
    Collection<VersionIri> selectedProfiles,
    Collection<VersionIri> foundInOtherProfiles,
    Node graph,
    Node locationHint) {

  /** Canonical constructor; validates required fields and copies the profile collections. */
  public SparqlValidationAnnotation {
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(code, "code");
    selectedProfiles = selectedProfiles == null ? List.of() : List.copyOf(selectedProfiles);
    foundInOtherProfiles =
        foundInOtherProfiles == null ? List.of() : List.copyOf(foundInOtherProfiles);
  }

  /**
   * Convenience constructor for callers that have no {@link #locationHint()} to offer (the vast
   * majority — it only matters for shape-structure annotations that may be emitted more than once
   * for the same term). Equivalent to passing {@code null} as the location hint.
   */
  public SparqlValidationAnnotation(
      SparqlValidationSeverity severity,
      Integer line,
      Integer column,
      String message,
      SparqlValidationCode code,
      Node term,
      Collection<VersionIri> selectedProfiles,
      Collection<VersionIri> foundInOtherProfiles,
      Node graph) {
    this(
        severity,
        line,
        column,
        message,
        code,
        term,
        selectedProfiles,
        foundInOtherProfiles,
        graph,
        null);
  }

  /** Returns a copy of this annotation with a different severity. */
  public SparqlValidationAnnotation withSeverity(SparqlValidationSeverity newSeverity) {
    return new SparqlValidationAnnotation(
        newSeverity,
        line,
        column,
        message,
        code,
        term,
        selectedProfiles,
        foundInOtherProfiles,
        graph,
        locationHint);
  }

  /**
   * Returns a copy of this annotation with a different line and column (both may be {@code null}).
   */
  public SparqlValidationAnnotation withPosition(Integer newLine, Integer newColumn) {
    return new SparqlValidationAnnotation(
        severity,
        newLine,
        newColumn,
        message,
        code,
        term,
        selectedProfiles,
        foundInOtherProfiles,
        graph,
        locationHint);
  }

  /** Returns a copy of this annotation with a different message. */
  public SparqlValidationAnnotation withMessage(String newMessage) {
    return new SparqlValidationAnnotation(
        severity,
        line,
        column,
        newMessage,
        code,
        term,
        selectedProfiles,
        foundInOtherProfiles,
        graph,
        locationHint);
  }
}
