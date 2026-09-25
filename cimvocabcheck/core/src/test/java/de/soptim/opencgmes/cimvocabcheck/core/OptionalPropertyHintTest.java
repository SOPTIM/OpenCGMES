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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import java.util.List;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link SparqlValidationCode#PROPERTY_MAY_BE_ABSENT} hint: matching a property whose CIM
 * multiplicity allows zero values outside an {@code OPTIONAL} block silently drops solutions.
 *
 * <p>Most of these cases assert that the hint stays <em>quiet</em> — with roughly two thirds of a
 * CGMES profile optional, the suppressions are what make the check usable.
 */
public class OptionalPropertyHintTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String CIMS = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";

  private static final String SCHEMA =
      "@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
          + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
          + "@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n"
          + "@prefix cim:  <"
          + CIM
          + "> .\n"
          + "@prefix cims: <"
          + CIMS
          + "> .\n"
          + "cim:Line a rdfs:Class .\n"
          + "cim:Line.name a rdf:Property ;\n"
          + "  rdfs:domain cim:Line ; rdfs:range xsd:string ;\n"
          + "  cims:multiplicity <"
          + CIMS
          + "M:1..1> .\n"
          + "cim:Line.description a rdf:Property ;\n"
          + "  rdfs:domain cim:Line ; rdfs:range xsd:string ;\n"
          + "  cims:multiplicity <"
          + CIMS
          + "M:0..1> .\n"
          + "cim:Line.note a rdf:Property ;\n"
          + "  rdfs:domain cim:Line ; rdfs:range xsd:string ;\n"
          + "  cims:multiplicity <"
          + CIMS
          + "M:0..n> .\n"
          + "cim:Line.length a rdf:Property ;\n"
          + "  rdfs:domain cim:Line ; rdfs:range xsd:float .\n";

  private static final String PREFIX = "PREFIX cim: <" + CIM + ">\n";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    var g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(SCHEMA, Lang.TURTLE).parse(g);
    api =
        new SparqlValidationApi(
            RdfsSchemaIndex.builder().addProfile(VersionIri.of("urn:p"), g).build());
  }

  // ---- fires ------------------------------------------------------------------------------

  @Test
  public void optionalPropertyInMandatoryClause_hinted() {
    var hints =
        hints(
            "SELECT ?l ?d WHERE {\n"
                + "  ?l a cim:Line ;\n"
                + "     cim:Line.description ?d .\n"
                + "}");
    assertEquals(1, hints.size());
    assertEquals(SparqlValidationSeverity.INFO, hints.get(0).severity());
    assertEquals(CIM + "Line.description", hints.get(0).term().getURI());
    assertTrue(hints.get(0).message().contains("0..1"));
    assertEquals(Integer.valueOf(4), hints.get(0).line()); // 1 prefix line + 3
  }

  @Test
  public void unboundedOptionalProperty_hinted() {
    assertEquals(1, hints("SELECT * WHERE { ?l a cim:Line ; cim:Line.note ?n }").size());
  }

  @Test
  public void constantSubject_hintedWithoutOtherPatterns() {
    // A fixed subject needs no other pattern to anchor it.
    assertEquals(1, hints("SELECT * WHERE { <urn:uuid:line-1> cim:Line.description ?d }").size());
  }

  @Test
  public void sameAttributeTwiceOnSameSubject_hintedOnce() {
    assertEquals(
        1,
        hints(
                "SELECT * WHERE {\n"
                    + "  ?l a cim:Line ; cim:Line.description ?d .\n"
                    + "  ?l cim:Line.description ?d2 .\n"
                    + "}")
            .size());
  }

  // ---- stays quiet ------------------------------------------------------------------------

  @Test
  public void insideOptional_quiet() {
    assertTrue(
        hints(
                "SELECT * WHERE {\n"
                    + "  ?l a cim:Line .\n"
                    + "  OPTIONAL { ?l cim:Line.description ?d }\n"
                    + "}")
            .isEmpty());
  }

  @Test
  public void insideUnionBranch_quiet() {
    assertTrue(
        hints(
                "SELECT * WHERE {\n"
                    + "  ?l a cim:Line .\n"
                    + "  { ?l cim:Line.description ?d } UNION { ?l cim:Line.note ?d }\n"
                    + "}")
            .isEmpty());
  }

  @Test
  public void insideMinusAndNotExists_quiet() {
    assertTrue(
        hints(
                "SELECT * WHERE {\n"
                    + "  ?l a cim:Line .\n"
                    + "  MINUS { ?l cim:Line.description ?d }\n"
                    + "  FILTER NOT EXISTS { ?l cim:Line.note ?n }\n"
                    + "}")
            .isEmpty());
  }

  @Test
  public void mandatoryProperty_quiet() {
    assertTrue(hints("SELECT * WHERE { ?l a cim:Line ; cim:Line.name ?n }").isEmpty());
  }

  @Test
  public void propertyWithoutDeclaredMultiplicity_quiet() {
    assertTrue(hints("SELECT * WHERE { ?l a cim:Line ; cim:Line.length ?len }").isEmpty());
  }

  @Test
  public void literalObject_quiet() {
    // Matching a specific value is a deliberate filter; requiring presence is the point.
    assertTrue(
        hints("SELECT * WHERE { ?l a cim:Line ; cim:Line.description \"feeder\" }").isEmpty());
  }

  @Test
  public void objectConstrainedByFilter_quiet() {
    assertTrue(
        hints(
                "SELECT * WHERE {\n"
                    + "  ?l a cim:Line ; cim:Line.description ?d .\n"
                    + "  FILTER(STRLEN(?d) > 3)\n"
                    + "}")
            .isEmpty());
  }

  @Test
  public void objectConstrainedByValues_quiet() {
    assertTrue(
        hints(
                "SELECT * WHERE {\n"
                    + "  ?l a cim:Line ; cim:Line.description ?d .\n"
                    + "  VALUES ?d { \"feeder\" }\n"
                    + "}")
            .isEmpty());
  }

  @Test
  public void subjectBoundOnlyByThisPattern_quiet() {
    // Wrapping the sole pattern binding ?l in OPTIONAL would leave nothing to join against.
    assertTrue(hints("SELECT * WHERE { ?l cim:Line.description ?d }").isEmpty());
  }

  @Test
  public void constructTemplate_quiet() {
    // The template produces triples; it does not match them, so optionality does not apply.
    var hints =
        hints(
            "CONSTRUCT { ?l cim:Line.description ?d }\n"
                + "WHERE { ?l a cim:Line . OPTIONAL { ?l cim:Line.description ?d } }");
    assertTrue(hints.isEmpty());
  }

  @Test
  public void insertTemplate_quiet() {
    assertTrue(
        hints(
                "INSERT { ?l cim:Line.description ?d }\n"
                    + "WHERE { ?l a cim:Line . OPTIONAL { ?l cim:Line.description ?d } }")
            .isEmpty());
  }

  // ---- interaction with the strictness level and rule overrides ----------------------------

  @Test
  public void permissiveDropsTheHint() {
    var raw = hints("SELECT * WHERE { ?l a cim:Line ; cim:Line.description ?d }");
    assertEquals(1, raw.size());
    assertTrue(StrictnessLevel.PERMISSIVE.apply(raw).isEmpty());
  }

  @Test
  public void ruleOverrideSurvivesPedantic() {
    var raw = hints("SELECT * WHERE { ?l a cim:Line ; cim:Line.description ?d }");
    var rules = RuleSeverities.parse(java.util.Map.of("PROPERTY_MAY_BE_ABSENT", "info"));
    var out = rules.apply(raw, StrictnessLevel.PEDANTIC);
    assertEquals(1, out.size());
    assertEquals(SparqlValidationSeverity.INFO, out.get(0).severity());
  }

  @Test
  public void ruleOffRemovesTheHint() {
    var raw = hints("SELECT * WHERE { ?l a cim:Line ; cim:Line.description ?d }");
    var rules = RuleSeverities.parse(java.util.Map.of("PROPERTY_MAY_BE_ABSENT", "off"));
    assertTrue(rules.apply(raw, StrictnessLevel.DEFAULT).isEmpty());
  }

  // ---- helpers ----------------------------------------------------------------------------

  private List<SparqlValidationAnnotation> hints(String queryBody) {
    var result = api.validateSparql(PREFIX + queryBody);
    assertFalse(
        "query must parse: " + result.annotations(),
        result.annotations().stream().anyMatch(a -> a.code() == SparqlValidationCode.SYNTAX_ERROR));
    return result.annotations().stream()
        .filter(a -> a.code() == SparqlValidationCode.PROPERTY_MAY_BE_ABSENT)
        .toList();
  }
}
