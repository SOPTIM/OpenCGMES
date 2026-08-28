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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Unit tests for {@link RuleSeverities}: parsing, overriding, and precedence over strictness. */
public class RuleSeveritiesTest {

  // ---- parse ------------------------------------------------------------------------------

  @Test
  public void nullOrEmptyYieldsNone() {
    assertSame(RuleSeverities.NONE, RuleSeverities.parse(null));
    assertSame(RuleSeverities.NONE, RuleSeverities.parse(Map.of()));
    assertTrue(RuleSeverities.NONE.isEmpty());
  }

  @Test
  public void parsesCaseInsensitively() {
    var rules = RuleSeverities.parse(Map.of("property_may_be_absent", "OFF"));
    assertEquals(
        java.util.Set.of(SparqlValidationCode.PROPERTY_MAY_BE_ABSENT), rules.configuredCodes());
  }

  @Test(expected = IllegalArgumentException.class)
  public void unknownCodeRejected() {
    RuleSeverities.parse(Map.of("NO_SUCH_CHECK", "off"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void unknownSeverityRejected() {
    RuleSeverities.parse(Map.of("PROPERTY_MAY_BE_ABSENT", "quiet"));
  }

  // ---- apply ------------------------------------------------------------------------------

  @Test
  public void offDropsOnlyThatCode() {
    var rules = RuleSeverities.parse(Map.of("PROPERTY_MAY_BE_ABSENT", "off"));
    var out = rules.apply(sample(), StrictnessLevel.DEFAULT);
    assertEquals(2, out.size());
    assertTrue(
        out.stream().noneMatch(a -> a.code() == SparqlValidationCode.PROPERTY_MAY_BE_ABSENT));
  }

  @Test
  public void severityIsReplaced() {
    var rules = RuleSeverities.parse(Map.of("PROPERTY_MAY_BE_ABSENT", "error"));
    var out = rules.apply(sample(), StrictnessLevel.DEFAULT);
    assertEquals(SparqlValidationSeverity.ERROR, severityOf(out));
  }

  // ---- precedence over the strictness level -----------------------------------------------

  @Test
  public void configuredCodeIsNotPromotedByPedantic() {
    var rules = RuleSeverities.parse(Map.of("PROPERTY_MAY_BE_ABSENT", "info"));
    var out = rules.apply(sample(), StrictnessLevel.PEDANTIC);
    assertEquals(SparqlValidationSeverity.INFO, severityOf(out));
    // The unconfigured warning is still promoted.
    assertTrue(
        out.stream()
            .filter(a -> a.code() == SparqlValidationCode.DATATYPE_MISMATCH)
            .allMatch(a -> a.severity() == SparqlValidationSeverity.ERROR));
  }

  @Test
  public void configuredCodeIsNotPromotedByStrict() {
    var rules = RuleSeverities.parse(Map.of("DATATYPE_MISMATCH", "warn"));
    var out = rules.apply(sample(), StrictnessLevel.STRICT);
    assertTrue(
        out.stream()
            .filter(a -> a.code() == SparqlValidationCode.DATATYPE_MISMATCH)
            .allMatch(a -> a.severity() == SparqlValidationSeverity.WARN));
  }

  @Test
  public void configuredCodeSurvivesPermissive() {
    // Permissive normally drops every non-structural finding; an explicit rule overrides that.
    var rules = RuleSeverities.parse(Map.of("PROPERTY_MAY_BE_ABSENT", "warn"));
    var out = rules.apply(sample(), StrictnessLevel.PERMISSIVE);
    assertEquals(SparqlValidationSeverity.WARN, severityOf(out));
    assertEquals(2, out.size()); // the hint plus the structural UNKNOWN_CLASS error
  }

  @Test
  public void withoutRulesStrictnessAppliesUnchanged() {
    var out = RuleSeverities.NONE.apply(sample(), StrictnessLevel.PERMISSIVE);
    assertEquals(1, out.size());
    assertEquals(SparqlValidationCode.UNKNOWN_CLASS, out.get(0).code());
  }

  // ---- config integration -----------------------------------------------------------------

  @Test
  public void configExposesParsedRules() {
    var config =
        new de.soptim.opencgmes.cimvocabcheck.core.config.CimvocabcheckConfig(
            null, null, null, null, null, null, null, Map.of("PROPERTY_MAY_BE_ABSENT", "off"));
    assertEquals(
        java.util.Set.of(SparqlValidationCode.PROPERTY_MAY_BE_ABSENT),
        config.ruleSeverities().configuredCodes());
  }

  // ---- helpers ----------------------------------------------------------------------------

  private static List<SparqlValidationAnnotation> sample() {
    return List.of(
        annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.UNKNOWN_CLASS),
        annotation(SparqlValidationSeverity.WARN, SparqlValidationCode.DATATYPE_MISMATCH),
        annotation(SparqlValidationSeverity.INFO, SparqlValidationCode.PROPERTY_MAY_BE_ABSENT));
  }

  private static SparqlValidationSeverity severityOf(List<SparqlValidationAnnotation> out) {
    return out.stream()
        .filter(a -> a.code() == SparqlValidationCode.PROPERTY_MAY_BE_ABSENT)
        .findFirst()
        .orElseThrow()
        .severity();
  }

  private static SparqlValidationAnnotation annotation(
      SparqlValidationSeverity severity, SparqlValidationCode code) {
    return new SparqlValidationAnnotation(
        severity, null, null, "test message", code, null, List.of(), List.of(), null);
  }
}
