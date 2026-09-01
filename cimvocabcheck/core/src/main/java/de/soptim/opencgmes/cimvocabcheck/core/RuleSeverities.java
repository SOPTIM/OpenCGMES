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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-check severity overrides, configured under the {@code "rules"} key of {@code
 * opencgmes.jsonc}:
 *
 * <pre>{@code
 * "rules": {
 *   "PROPERTY_MAY_BE_ABSENT": "off",
 *   "UNKNOWN_TERM_IN_EXPRESSION": "error"
 * }
 * }</pre>
 *
 * <p>Keys are {@link SparqlValidationCode} names; values are {@code off}, {@code info}, {@code
 * warn} or {@code error}. A code set to {@code off} is dropped from the result; any other value
 * replaces the severity the check itself chose.
 *
 * <p>An explicitly configured code is <em>final</em>: {@link StrictnessLevel} neither promotes nor
 * filters it. Without that rule, a project that turns a hint down to {@code info} would still see
 * it fail the build under {@code pedantic} — which is exactly the situation the override exists to
 * prevent. It also works the other way: a code pinned to {@code error} survives {@code permissive}.
 */
public final class RuleSeverities {

  /** No overrides — every finding keeps the severity its check assigned. */
  public static final RuleSeverities NONE = new RuleSeverities(Map.of(), Set.of());

  private final Map<SparqlValidationCode, SparqlValidationSeverity> severities;
  private final Set<SparqlValidationCode> disabled;

  private RuleSeverities(
      Map<SparqlValidationCode, SparqlValidationSeverity> severities,
      Set<SparqlValidationCode> disabled) {
    this.severities = severities;
    this.disabled = disabled;
  }

  /**
   * Parses a raw {@code code → severity} map as read from the config file. A {@code null} or empty
   * map yields {@link #NONE}.
   *
   * @throws IllegalArgumentException if a key is not a known validation code or a value is not one
   *     of {@code off}, {@code info}, {@code warn}, {@code error}
   */
  public static RuleSeverities parse(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return NONE;
    }
    var severities =
        new EnumMap<SparqlValidationCode, SparqlValidationSeverity>(SparqlValidationCode.class);
    var disabled = EnumSet.noneOf(SparqlValidationCode.class);
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      SparqlValidationCode code = parseCode(entry.getKey());
      SparqlValidationSeverity severity = parseSeverity(entry.getKey(), entry.getValue());
      if (severity == null) {
        disabled.add(code);
      } else {
        severities.put(code, severity);
      }
    }
    return new RuleSeverities(Map.copyOf(severities), Set.copyOf(disabled));
  }

  /** Returns whether any override is configured. */
  public boolean isEmpty() {
    return severities.isEmpty() && disabled.isEmpty();
  }

  /** The codes this configuration mentions; {@link StrictnessLevel} leaves them untouched. */
  public Set<SparqlValidationCode> configuredCodes() {
    var all = EnumSet.noneOf(SparqlValidationCode.class);
    all.addAll(severities.keySet());
    all.addAll(disabled);
    return all;
  }

  /**
   * Applies the overrides to {@code annotations} and then {@code level}, returning a new immutable
   * list. Disabled codes are dropped, overridden codes get their configured severity, and every
   * configured code is exempt from the strictness level's promotion and filtering.
   */
  public List<SparqlValidationAnnotation> apply(
      List<SparqlValidationAnnotation> annotations, StrictnessLevel level) {
    if (isEmpty()) {
      return level.apply(annotations);
    }
    var overridden = new ArrayList<SparqlValidationAnnotation>(annotations.size());
    for (SparqlValidationAnnotation a : annotations) {
      if (disabled.contains(a.code())) {
        continue;
      }
      SparqlValidationSeverity override = severities.get(a.code());
      overridden.add(override == null ? a : a.withSeverity(override));
    }
    return level.apply(overridden, configuredCodes());
  }

  private static SparqlValidationCode parseCode(String key) {
    try {
      return SparqlValidationCode.valueOf(key.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown validation code '" + key + "' in 'rules'.", e);
    }
  }

  /** Returns the configured severity, or {@code null} for {@code off}. */
  private static SparqlValidationSeverity parseSeverity(String code, String value) {
    String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    return switch (v) {
      case "off" -> null;
      case "info" -> SparqlValidationSeverity.INFO;
      case "warn" -> SparqlValidationSeverity.WARN;
      case "error" -> SparqlValidationSeverity.ERROR;
      default ->
          throw new IllegalArgumentException(
              "Unknown severity '"
                  + value
                  + "' for rule '"
                  + code
                  + "'. Valid values: off, info, warn, error.");
    };
  }
}
