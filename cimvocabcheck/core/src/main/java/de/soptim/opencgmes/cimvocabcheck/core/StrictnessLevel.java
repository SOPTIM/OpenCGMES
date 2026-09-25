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

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Controls which validation findings are reported and how their severities are mapped.
 *
 * <h2>Levels</h2>
 *
 * <dl>
 *   <dt>{@link #PERMISSIVE}
 *   <dd>Only structural errors: unknown classes/properties, syntax errors, and cardinality
 *       contradictions. All semantic checks, SHACL shape warnings, and informational hints are
 *       suppressed. Suited to exploratory query development against an incomplete schema.
 *   <dt>{@link #DEFAULT}
 *   <dd>All checks, original severities. Only {@code ERROR} annotations cause a validation failure.
 *       The interactive-editor baseline.
 *   <dt>{@link #STRICT}
 *   <dd>All checks; {@code WARN} annotations are promoted to {@code ERROR}. Any schema misuse
 *       (datatype mismatch, node-kind conflict, unconfigured graph) fails the build. Recommended
 *       for CI on shared query files.
 *   <dt>{@link #PEDANTIC}
 *   <dd>All checks; both {@code WARN} and {@code INFO} annotations are promoted to {@code ERROR}.
 *       The most thorough gate: fails on every implied-type hint and dynamic-predicate notice.
 * </dl>
 *
 * <p>Codes whose severity the project configured explicitly under {@code "rules"} are exempt from
 * all of the above — see {@link RuleSeverities}.
 */
public enum StrictnessLevel {
  PERMISSIVE,
  DEFAULT,
  STRICT,
  PEDANTIC;

  /**
   * Parses a case-insensitive level name. Returns {@link #DEFAULT} for {@code null} or blank.
   *
   * @throws IllegalArgumentException for unknown names
   */
  public static StrictnessLevel parse(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    return switch (value.toLowerCase(Locale.ROOT).strip()) {
      case "permissive" -> PERMISSIVE;
      case "default" -> DEFAULT;
      case "strict" -> STRICT;
      case "pedantic" -> PEDANTIC;
      default ->
          throw new IllegalArgumentException(
              "Unknown strictness level '"
                  + value
                  + "'. Valid values: permissive, default, strict, pedantic.");
    };
  }

  /**
   * Applies this level to an annotation list, returning a new immutable list with findings filtered
   * or severity-promoted as documented above. The original list is not modified.
   */
  public List<SparqlValidationAnnotation> apply(List<SparqlValidationAnnotation> annotations) {
    return apply(annotations, Set.of());
  }

  /**
   * Applies this level, leaving annotations whose code is in {@code pinned} exactly as they are —
   * neither promoted nor filtered. Used for codes whose severity the project configured explicitly
   * (see {@link RuleSeverities}), which must win over the strictness level.
   */
  public List<SparqlValidationAnnotation> apply(
      List<SparqlValidationAnnotation> annotations, Set<SparqlValidationCode> pinned) {
    return switch (this) {
      case PERMISSIVE ->
          annotations.stream()
              .filter(a -> isStructural(a.code()) || pinned.contains(a.code()))
              .toList();
      case DEFAULT -> List.copyOf(annotations);
      case STRICT ->
          annotations.stream()
              .map(
                  a ->
                      a.severity() == SparqlValidationSeverity.WARN && !pinned.contains(a.code())
                          ? a.withSeverity(SparqlValidationSeverity.ERROR)
                          : a)
              .toList();
      case PEDANTIC ->
          annotations.stream()
              .map(
                  a ->
                      a.severity() != SparqlValidationSeverity.ERROR && !pinned.contains(a.code())
                          ? a.withSeverity(SparqlValidationSeverity.ERROR)
                          : a)
              .toList();
    };
  }

  /**
   * {@code true} for codes that represent unambiguous structural problems regardless of how
   * complete or exploratory the schema is.
   */
  private static boolean isStructural(SparqlValidationCode code) {
    return switch (code) {
      case SYNTAX_ERROR,
          UNKNOWN_CLASS,
          UNKNOWN_PROPERTY,
          UNKNOWN_VOCABULARY_TERM,
          INVALID_CARDINALITY,
          INVALID_VALUE_RANGE ->
          true;
      default -> false;
    };
  }
}
