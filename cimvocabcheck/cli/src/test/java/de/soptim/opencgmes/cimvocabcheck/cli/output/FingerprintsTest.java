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

package de.soptim.opencgmes.cimvocabcheck.cli.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationSeverity;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Var;
import org.junit.Test;

/** A finding keeps its fingerprint across edits that do not touch it. */
public class FingerprintsTest {

  private static final Node FOO = NodeFactory.createURI("http://example.org/Foo");
  private static final Node BAR = NodeFactory.createURI("http://example.org/Bar");

  private static SparqlValidationAnnotation finding(
      int line, int column, SparqlValidationCode code, Node term) {
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        line,
        column,
        "message " + line,
        code,
        term,
        null,
        null,
        null);
  }

  private static String first(SparqlValidationAnnotation a) {
    return new Fingerprints().of("q.rq", a);
  }

  @Test
  public void movingAFindingKeepsItsFingerprint() {
    assertEquals(
        first(finding(3, 12, SparqlValidationCode.UNKNOWN_CLASS, FOO)),
        first(finding(7, 4, SparqlValidationCode.UNKNOWN_CLASS, FOO)));
  }

  @Test
  public void fileCodeAndTermEachDistinguishFindings() {
    String base = first(finding(1, 1, SparqlValidationCode.UNKNOWN_CLASS, FOO));

    assertNotEquals(base, first(finding(1, 1, SparqlValidationCode.UNKNOWN_CLASS, BAR)));
    assertNotEquals(base, first(finding(1, 1, SparqlValidationCode.UNKNOWN_PROPERTY, FOO)));
    assertNotEquals(
        base,
        new Fingerprints().of("other.rq", finding(1, 1, SparqlValidationCode.UNKNOWN_CLASS, FOO)));
  }

  @Test
  public void variablesAreTermsToo() {
    assertNotEquals(
        first(finding(1, 1, SparqlValidationCode.UNUSED_VARIABLE, Var.alloc("a"))),
        first(finding(1, 1, SparqlValidationCode.UNUSED_VARIABLE, Var.alloc("b"))));
  }

  @Test
  public void repeatedFindingsGetDistinctFingerprintsByOccurrence() {
    var fingerprints = new Fingerprints();
    String once = fingerprints.of("q.rq", finding(2, 1, SparqlValidationCode.UNKNOWN_CLASS, FOO));
    String twice = fingerprints.of("q.rq", finding(5, 1, SparqlValidationCode.UNKNOWN_CLASS, FOO));

    assertNotEquals(once, twice);
    assertEquals(once, first(finding(9, 9, SparqlValidationCode.UNKNOWN_CLASS, FOO)));
  }

  @Test
  public void blankNodeTermsDoNotLeakTheirRunSpecificLabel() {
    assertEquals(
        first(
            finding(1, 1, SparqlValidationCode.INVALID_CARDINALITY, NodeFactory.createBlankNode())),
        first(
            finding(
                1, 1, SparqlValidationCode.INVALID_CARDINALITY, NodeFactory.createBlankNode())));
  }
}
