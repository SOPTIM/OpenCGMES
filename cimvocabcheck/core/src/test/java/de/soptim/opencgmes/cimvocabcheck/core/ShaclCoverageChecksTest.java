/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimvocabcheck.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the SHACL constraint-coverage checks in the shape analyzer: {@code sh:in}, {@code
 * sh:hasValue}, {@code sh:datatype} vocabulary, {@code sh:targetSubjectsOf}/{@code
 * sh:targetObjectsOf}, {@code sh:ignoredProperties}, the property-pair constraints, value-range
 * contradictions, and {@code sh:deactivated}.
 */
public class ShaclCoverageChecksTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  private static final String SCHEMA =
      "@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
          + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
          + "@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n"
          + "@prefix cim:  <"
          + CIM
          + "> .\n"
          + "cim:WindGenUnitKind a rdfs:Class .\n"
          + "cim:WindGenUnitKind.offshore a cim:WindGenUnitKind .\n"
          + "cim:WindGenUnitKind.onshore  a cim:WindGenUnitKind .\n"
          + "cim:WindGeneratingUnit a rdfs:Class .\n"
          + "cim:WindGeneratingUnit.windGenUnitType a rdf:Property ;\n"
          + "  rdfs:domain cim:WindGeneratingUnit ; rdfs:range cim:WindGenUnitKind .\n"
          + "cim:ConductingEquipment a rdfs:Class .\n"
          + "cim:ACLineSegment a rdfs:Class .\n"
          + "cim:Terminal a rdfs:Class .\n"
          + "cim:Terminal.ConductingEquipment a rdf:Property ;\n"
          + "  rdfs:domain cim:Terminal ; rdfs:range cim:ConductingEquipment .\n"
          + "cim:IdentifiedObject a rdfs:Class .\n"
          + "cim:IdentifiedObject.name a rdf:Property ;\n"
          + "  rdfs:domain cim:IdentifiedObject ; rdfs:range xsd:string .\n"
          + "cim:ACLineSegment.r a rdf:Property ;\n"
          + "  rdfs:domain cim:ACLineSegment ; rdfs:range xsd:float .\n";

  private static final String PREFIXES =
      "@prefix sh:   <http://www.w3.org/ns/shacl#> .\n"
          + "@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
          + "@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n"
          + "@prefix cim:  <"
          + CIM
          + "> .\n"
          + "@prefix ex:   <http://example.org/> .\n";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    var g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(SCHEMA, Lang.TURTLE).parse(g);
    api =
        new SparqlValidationApi(
            RdfsSchemaIndex.builder().addProfile(VersionIri.of("urn:p"), g).build());
  }

  // ---- A1: sh:in --------------------------------------------------------------------------

  @Test
  public void inEnumList_validMembers_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:WindGeneratingUnit ;\n"
                + "  sh:property [ sh:path cim:WindGeneratingUnit.windGenUnitType ;\n"
                + "    sh:in ( cim:WindGenUnitKind.offshore cim:WindGenUnitKind.onshore ) ] .");
    assertFalse(has(r, SparqlValidationCode.INVALID_ENUM_VALUE));
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_CLASS));
  }

  @Test
  public void inEnumList_typoMember_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:WindGeneratingUnit ;\n"
                + "  sh:property [ sh:path cim:WindGeneratingUnit.windGenUnitType ;\n"
                + "    sh:in ( cim:WindGenUnitKind.offshore cim:WindGenUnitKind.offshroe ) ] .");
    assertTrue(
        hasForTerm(r, SparqlValidationCode.INVALID_ENUM_VALUE, CIM + "WindGenUnitKind.offshroe"));
  }

  @Test
  public void inClassList_knownClass_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:Terminal ;\n"
                + "  sh:property [ sh:path cim:Terminal.ConductingEquipment ;\n"
                + "    sh:in ( cim:ACLineSegment ) ] .");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_CLASS));
    assertFalse(has(r, SparqlValidationCode.INVALID_ENUM_VALUE));
  }

  @Test
  public void inClassList_unknownTerm_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:Terminal ;\n"
                + "  sh:property [ sh:path cim:Terminal.ConductingEquipment ;\n"
                + "    sh:in ( cim:ACLineSegmentt ) ] .");
    assertTrue(hasForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, CIM + "ACLineSegmentt"));
  }

  @Test
  public void inLiteralList_skipped() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:IdentifiedObject ;\n"
                + "  sh:property [ sh:path cim:IdentifiedObject.name ;\n"
                + "    sh:in ( \"a\" \"b\" ) ] .");
    assertFalse(has(r, SparqlValidationCode.INVALID_ENUM_VALUE));
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_CLASS));
  }

  @Test
  public void inList_headerExtensionMember_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:Terminal ;\n"
                + "  sh:property [ sh:path cim:Terminal.ConductingEquipment ;\n"
                + "    sh:in ( rdf:Statements ) ] .");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_CLASS));
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  // ---- A9: sh:hasValue --------------------------------------------------------------------

  @Test
  public void hasValue_validEnumMember_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:WindGeneratingUnit ;\n"
                + "  sh:property [ sh:path cim:WindGeneratingUnit.windGenUnitType ;\n"
                + "    sh:hasValue cim:WindGenUnitKind.offshore ] .");
    assertFalse(has(r, SparqlValidationCode.INVALID_ENUM_VALUE));
  }

  @Test
  public void hasValue_typoEnumMember_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:WindGeneratingUnit ;\n"
                + "  sh:property [ sh:path cim:WindGeneratingUnit.windGenUnitType ;\n"
                + "    sh:hasValue cim:WindGenUnitKind.offshroe ] .");
    assertTrue(
        hasForTerm(r, SparqlValidationCode.INVALID_ENUM_VALUE, CIM + "WindGenUnitKind.offshroe"));
  }

  // ---- A6: sh:datatype vocabulary ---------------------------------------------------------

  @Test
  public void datatype_validXsd_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:IdentifiedObject.name ; sh:datatype xsd:string ] .");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  @Test
  public void datatype_typoXsd_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:IdentifiedObject.name ; sh:datatype xsd:strng ] .");
    assertTrue(
        hasForTerm(
            r,
            SparqlValidationCode.UNKNOWN_VOCABULARY_TERM,
            "http://www.w3.org/2001/XMLSchema#strng"));
  }

  @Test
  public void datatype_rdfLangString_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:IdentifiedObject.name ;\n"
                + "    sh:datatype rdf:langString ] .");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  // ---- A2: sh:targetSubjectsOf / sh:targetObjectsOf ---------------------------------------

  @Test
  public void targetSubjectsOf_knownProperty_accepted() {
    var r = shacl("ex:S a sh:NodeShape ; sh:targetSubjectsOf cim:IdentifiedObject.name .");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_PROPERTY));
  }

  @Test
  public void targetObjectsOf_unknownProperty_flagged() {
    var r = shacl("ex:S a sh:NodeShape ; sh:targetObjectsOf cim:IdentifiedObject.namee .");
    assertTrue(
        hasForTerm(r, SparqlValidationCode.UNKNOWN_PROPERTY, CIM + "IdentifiedObject.namee"));
  }

  // ---- A3: sh:ignoredProperties -----------------------------------------------------------

  @Test
  public void ignoredProperties_rdfTypeAndKnown_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:IdentifiedObject ; sh:closed true ;\n"
                + "  sh:ignoredProperties ( rdf:type cim:IdentifiedObject.name ) .");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_PROPERTY));
  }

  @Test
  public void ignoredProperties_unknown_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:IdentifiedObject ; sh:closed true ;\n"
                + "  sh:ignoredProperties ( rdf:type cim:Bogus.prop ) .");
    assertTrue(hasForTerm(r, SparqlValidationCode.UNKNOWN_PROPERTY, CIM + "Bogus.prop"));
  }

  // ---- A7: property-pair constraints ------------------------------------------------------

  @Test
  public void lessThan_knownProperty_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ; sh:lessThan cim:ACLineSegment.r ]"
                + " .");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_PROPERTY));
  }

  @Test
  public void equals_unknownProperty_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ; sh:equals cim:ACLineSegment.rr ]"
                + " .");
    assertTrue(hasForTerm(r, SparqlValidationCode.UNKNOWN_PROPERTY, CIM + "ACLineSegment.rr"));
  }

  // ---- A8: value-range contradictions -----------------------------------------------------

  @Test
  public void valueRange_minGreaterThanMax_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ;\n"
                + "    sh:minInclusive 5 ; sh:maxInclusive 1 ] .");
    assertTrue(has(r, SparqlValidationCode.INVALID_VALUE_RANGE));
  }

  @Test
  public void valueRange_consistent_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ;\n"
                + "    sh:minInclusive 1 ; sh:maxInclusive 5 ] .");
    assertFalse(has(r, SparqlValidationCode.INVALID_VALUE_RANGE));
  }

  @Test
  public void valueRange_exclusiveContradiction_flagged() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ;\n"
                + "    sh:minExclusive 5 ; sh:maxExclusive 1 ] .");
    assertTrue(has(r, SparqlValidationCode.INVALID_VALUE_RANGE));
  }

  // ---- A10: sh:deactivated ----------------------------------------------------------------

  @Test
  public void deactivatedShape_notChecked() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:deactivated true ;\n"
                + "  sh:targetClass cim:DoesNotExist .");
    assertFalse(
        "deactivated shape must not be validated", has(r, SparqlValidationCode.UNKNOWN_CLASS));
  }

  @Test
  public void activeShape_stillChecked() {
    var r = shacl("ex:S a sh:NodeShape ; sh:targetClass cim:DoesNotExist .");
    assertTrue(hasForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, CIM + "DoesNotExist"));
  }

  @Test
  public void deactivatedShape_nestedPropertyShapeNotChecked() {
    // A deactivated node shape deactivates its nested sh:property constraints too.
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:deactivated true ; sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:Removed.prop ; sh:minCount 1 ] .");
    assertFalse(
        "nested property shape of a deactivated shape must not be validated",
        has(r, SparqlValidationCode.UNKNOWN_PROPERTY));
  }

  // ---- value-range equal bounds -----------------------------------------------------------

  @Test
  public void valueRange_exclusiveEqualBounds_flagged() {
    // minExclusive 5 with maxExclusive 5 admits no value (x > 5 and x < 5).
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ;\n"
                + "    sh:minExclusive 5 ; sh:maxExclusive 5 ] .");
    assertTrue(has(r, SparqlValidationCode.INVALID_VALUE_RANGE));
  }

  @Test
  public void valueRange_inclusiveEqualBounds_accepted() {
    // minInclusive 5 with maxInclusive 5 is satisfiable by the single value 5.
    var r =
        shacl(
            "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ;\n"
                + "    sh:minInclusive 5 ; sh:maxInclusive 5 ] .");
    assertFalse(has(r, SparqlValidationCode.INVALID_VALUE_RANGE));
  }

  // ---- helpers ----------------------------------------------------------------------------

  private ShaclValidationResult shacl(String shapesBody) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(PREFIXES + shapesBody + "\n", Lang.TURTLE).parse(g);
    return api.validateShacl(g);
  }

  private static boolean has(ShaclValidationResult r, SparqlValidationCode code) {
    return r.shapeAnnotations().stream().anyMatch(a -> a.code() == code);
  }

  private static boolean hasForTerm(
      ShaclValidationResult r, SparqlValidationCode code, String uri) {
    return r.shapeAnnotations().stream()
        .anyMatch(a -> a.code() == code && a.term() != null && uri.equals(a.term().getURI()));
  }
}
