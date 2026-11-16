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

import static de.soptim.opencgmes.cimreader.core.TestModelBuilder.differenceModel;
import static de.soptim.opencgmes.cimreader.core.TestModelBuilder.fullModel;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimreader.core.TestModelBuilder;
import de.soptim.opencgmes.cimreader.core.source.ModelSource;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class CimValidatorTest {

  private static final String F1 = "urn:uuid:f1";
  private static final String F2 = "urn:uuid:f2";
  private static final String D1 = "urn:uuid:d1";
  private static final String D2 = "urn:uuid:d2";
  private static final String SSH = "urn:uuid:ssh";
  private static final String MISSING_1 = "urn:uuid:missing1";
  private static final String MISSING_2 = "urn:uuid:missing2";

  private CimValidator validator;

  @Before
  public void setUp() {
    validator = new CimValidator();
  }

  // ---------- validateHeader ----------

  @Test
  public void completeFullModelHeader_hasNoIssues() {
    validator.validateHeader(fullModel(F1).toHeader());

    assertNoIssues();
  }

  @Test
  public void completeDifferenceModelHeader_hasNoIssues() {
    validator.validateHeader(differenceModel(D1).supersedes(F1).toHeader());

    assertNoIssues();
  }

  @Test
  public void missingModel_isReported() {
    validator.validateHeader(fullModel(F1).withoutModel().toHeader());

    assertMissingField("model");
  }

  @Test
  public void missingVersion_isReported() {
    validator.validateHeader(fullModel(F1).withoutVersion().toHeader());

    assertSingleMissingField("version");
  }

  @Test
  public void missingModelingAuthoritySet_isReported() {
    validator.validateHeader(fullModel(F1).withoutModelingAuthoritySet().toHeader());

    assertSingleMissingField("modelingAuthoritySet");
  }

  @Test
  public void missingProfiles_isReported() {
    validator.validateHeader(fullModel(F1).withoutProfiles().toHeader());

    assertSingleMissingField("profiles");
  }

  @Test
  public void missingScenarioTime_isReported() {
    validator.validateHeader(fullModel(F1).withoutScenarioTime().toHeader());

    assertSingleMissingField("scenarioTime");
  }

  @Test
  public void differenceModelWithoutSupersedes_isReported() {
    validator.validateHeader(differenceModel(D1).toHeader());

    assertSingleMissingField("supersedes");
  }

  @Test
  public void multipleMissingFields_areAllReported() {
    validator.validateHeader(fullModel(F1).withoutVersion().withoutScenarioTime().toHeader());

    assertEquals(2, validator.getIssues().size());
    assertMissingField("version");
    assertMissingField("scenarioTime");
  }

  @Test
  public void validateHeaders_checksEverySource() {
    validator.validateHeaders(List.of(
        source(fullModel(F1).withoutVersion()),
        source(fullModel(F2).withoutScenarioTime())));

    assertEquals(2, validator.getIssues().size());
    assertMissingField("version");
    assertMissingField("scenarioTime");
  }

  // ---------- validateDependencies ----------

  @Test
  public void presentDependency_hasNoIssues() {
    validator.validateDependencies(List.of(
        source(fullModel(F1)),
        source(fullModel(SSH).dependentOn(F1))));

    assertNoIssues();
  }

  @Test
  public void missingDependency_isReported() {
    validator.validateDependencies(List.of(source(fullModel(F1).dependentOn(MISSING_1))));

    assertSingleIssue(MissingDependencyIssue.class, MISSING_1);
  }

  @Test
  public void eachMissingDependency_isReported() {
    validator.validateDependencies(List.of(
        source(fullModel(F1).dependentOn(MISSING_1, MISSING_2))));

    assertEquals(2, count(MissingDependencyIssue.class));
  }

  // ---------- validateCycles ----------

  @Test
  public void singleFullModel_hasNoCycle() {
    validator.validateCycles(List.of(source(fullModel(F1))));

    assertNoIssues();
  }

  @Test
  public void linearSupersedesChain_hasNoCycle() {
    validator.validateCycles(List.of(
        source(differenceModel(D2).supersedes(D1)),
        source(differenceModel(D1).supersedes(F1)),
        source(fullModel(F1))));

    assertNoIssues();
  }

  @Test
  public void twoModelCycle_isReported() {
    validator.validateCycles(List.of(
        source(differenceModel(D1).supersedes(D2)),
        source(differenceModel(D2).supersedes(D1))));

    assertSingleIssue(CyclicDependencyIssue.class, D1);
    assertTrue(validator.getIssues().getFirst().getMessage().contains(D2));
  }

  @Test
  public void selfCycle_isReported() {
    validator.validateCycles(List.of(source(differenceModel(D1).supersedes(D1))));

    assertSingleIssue(CyclicDependencyIssue.class, D1);
  }

  @Test
  public void missingSupersededModel_isReported() {
    validator.validateCycles(List.of(source(differenceModel(D1).supersedes(MISSING_1))));

    assertSingleIssue(MissingDependencyIssue.class, MISSING_1);
  }

  // ---------- validate / hasErrors ----------

  @Test
  public void validate_validModelSet_hasNoErrors() {
    validator.validate(List.of(
        source(fullModel(F1)),
        source(differenceModel(D1).supersedes(F1)),
        source(fullModel(SSH).dependentOn(F1))));

    assertNoIssues();
  }

  @Test
  public void validate_combinesAllChecks() {
    validator.validate(List.of(
        source(fullModel(F1).withoutVersion().dependentOn(MISSING_1)),
        source(differenceModel(D1).supersedes(D2)),
        source(differenceModel(D2).supersedes(D1))));

    assertEquals(3, validator.getIssues().size());
    assertEquals(1, count(MissingHeaderFieldIssue.class));
    assertEquals(1, count(MissingDependencyIssue.class));
    assertEquals(1, count(CyclicDependencyIssue.class));
  }

  @Test
  public void hasErrors_isFalseInitially() {
    assertNoIssues();
  }

  // ---------- Helpers ----------

  private static ModelSource source(TestModelBuilder builder) {
    return new ModelSource(builder.toHeader(), null);
  }

  private void assertNoIssues() {
    assertTrue("Unexpected issues: " + messages(), validator.getIssues().isEmpty());
    assertFalse(validator.hasErrors());
  }

  private void assertSingleMissingField(String field) {
    assertSingleIssue(MissingHeaderFieldIssue.class, "'" + field + "'");
  }

  private void assertMissingField(String field) {
    assertTrue("Expected missing field '" + field + "', got: " + messages(),
        validator.getIssues().stream().anyMatch(i -> i instanceof MissingHeaderFieldIssue
            && i.getMessage().contains("'" + field + "'")));
    assertTrue(validator.hasErrors());
  }

  private void assertSingleIssue(Class<? extends ValidationIssue> type, String messagePart) {
    assertEquals("Issues: " + messages(), 1, validator.getIssues().size());
    var issue = validator.getIssues().getFirst();
    assertTrue("Expected " + type.getSimpleName() + " but was " + issue.getClass().getSimpleName(),
        type.isInstance(issue));
    assertTrue("Message '" + issue.getMessage() + "' should contain " + messagePart,
        issue.getMessage().contains(messagePart));
    assertTrue(validator.hasErrors());
  }

  private long count(Class<? extends ValidationIssue> type) {
    return validator.getIssues().stream().filter(type::isInstance).count();
  }

  private List<String> messages() {
    return validator.getIssues().stream().map(ValidationIssue::getMessage).toList();
  }
}