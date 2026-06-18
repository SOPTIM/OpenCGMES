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

package de.soptim.opencgmes.cimcheck.core;

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Semantic check tests: domain/range, subClassOf relaxation, datatype mismatch, implied type,
 * property path chain compatibility.
 */
public class SemanticValidationTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

  // ----- class hierarchy ------------------------------------------------------------------
  // IdentifiedObject
  //   └─ Equipment
  //       └─ ConductingEquipment
  //           └─ ACLineSegment
  // Substation
  //   └─ VoltageLevel

  private static final String CLASS_IDENTIFIED = CIM + "IdentifiedObject";
  private static final String CLASS_EQUIPMENT = CIM + "Equipment";
  private static final String CLASS_CONDUCTING = CIM + "ConductingEquipment";
  private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
  private static final String CLASS_VOLTAGE = CIM + "VoltageLevel";
  private static final String CLASS_SUBSTATION = CIM + "Substation";

  private static final String PROP_NAME =
      CIM + "IdentifiedObject.name"; // domain=IdentifiedObject, range=xsd:string
  private static final String PROP_R =
      CIM + "ACLineSegment.r"; // domain=ACLineSegment,   range=xsd:double
  private static final String PROP_LENGTH =
      CIM + "Conductor.length"; // domain=ConductingEquipment, range=xsd:double
  private static final String PROP_NOMINAL_V =
      CIM + "VoltageLevel.nominalVoltage"; // domain=VoltageLevel,    range=xsd:double
  private static final String PROP_EQ_CONTAINER =
      CIM + "Equipment.EquipmentContainer"; // domain=Equipment,       range=VoltageLevel
  private static final String PROP_VL_SUB =
      CIM + "VoltageLevel.Substation"; // domain=VoltageLevel,    range=Substation

  private static final String PROFILE = "http://example.org/profile/CIM/1.0";

  private static final String PREAMBLE =
      "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
          + "PREFIX cim: <"
          + CIM
          + ">\n"
          + "PREFIX xsd: <"
          + XSD
          + ">\n";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    var classes =
        List.of(
            CLASS_IDENTIFIED,
            CLASS_EQUIPMENT,
            CLASS_CONDUCTING,
            CLASS_AC_LINE,
            CLASS_VOLTAGE,
            CLASS_SUBSTATION);
    var properties =
        List.of(PROP_NAME, PROP_R, PROP_LENGTH, PROP_NOMINAL_V, PROP_EQ_CONTAINER, PROP_VL_SUB);
    Map<String, List<String>> domains =
        Map.of(
            PROP_NAME, List.of(CLASS_IDENTIFIED),
            PROP_R, List.of(CLASS_AC_LINE),
            PROP_LENGTH, List.of(CLASS_CONDUCTING),
            PROP_NOMINAL_V, List.of(CLASS_VOLTAGE),
            PROP_EQ_CONTAINER, List.of(CLASS_EQUIPMENT),
            PROP_VL_SUB, List.of(CLASS_VOLTAGE));
    Map<String, List<String>> ranges =
        Map.of(
            PROP_NAME, List.of(XSD + "string"),
            PROP_R, List.of(XSD + "double"),
            PROP_LENGTH, List.of(XSD + "double"),
            PROP_NOMINAL_V, List.of(XSD + "double"),
            PROP_EQ_CONTAINER, List.of(CLASS_VOLTAGE),
            PROP_VL_SUB, List.of(CLASS_SUBSTATION));
    Map<String, List<String>> subClassOf =
        Map.of(
            CLASS_EQUIPMENT, List.of(CLASS_IDENTIFIED),
            CLASS_CONDUCTING, List.of(CLASS_EQUIPMENT),
            CLASS_AC_LINE, List.of(CLASS_CONDUCTING),
            CLASS_VOLTAGE, List.of(CLASS_IDENTIFIED),
            CLASS_SUBSTATION, List.of(CLASS_IDENTIFIED));

    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(PROFILE, classes, properties, domains, ranges, subClassOf)
            .build();
    api = new SparqlValidationApi(index);
  }

  // -- subClassOf relaxation accepts polymorphic property use -----------------------------

  @Test
  public void subClassOfRelaxationAcceptsParentProperty() {
    // cim:IdentifiedObject.name has domain IdentifiedObject; ACLineSegment ⊑ IdentifiedObject.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:IdentifiedObject.name ?n . }");
    assertNoErrors(r);
  }

  // -- direct-domain match ---------------------------------------------------------------

  @Test
  public void directDomainMatchAccepted() {
    var r =
        api.validateSparql(
            PREAMBLE + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?x . }");
    assertNoErrors(r);
  }

  // -- PROPERTY_NOT_ALLOWED_FOR_CLASS on wrong-class usage --------------------------------

  @Test
  public void propertyNotAllowedForWrongClass() {
    // VoltageLevel.nominalVoltage is for VoltageLevel; subject is ACLineSegment.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:VoltageLevel.nominalVoltage ?v ."
                + " }");
    var a = expectSingle(r, SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS);
    assertEquals(SparqlValidationSeverity.ERROR, a.severity());
    assertTrue(
        "message names the bad property", a.message().contains("VoltageLevel.nominalVoltage"));
    assertTrue("message names the subject class", a.message().contains("ACLineSegment"));
  }

  // -- QUERY_IMPLIED_TYPE INFO when subject has no explicit type --------------------------

  @Test
  public void impliedTypeInfoForUntypedSubject() {
    var r = api.validateSparql(PREAMBLE + "SELECT * WHERE { ?s cim:ACLineSegment.r ?x . }");
    var a = expectSingle(r, SparqlValidationCode.QUERY_IMPLIED_TYPE);
    assertEquals(SparqlValidationSeverity.INFO, a.severity());
    assertEquals(CLASS_AC_LINE, a.term().getURI());
    assertTrue(a.message().contains("ACLineSegment.r"));
  }

  @Test
  public void noImpliedTypeHintWhenSubjectTypedDynamically() {
    // ?s is typed dynamically (?s a ?t), the canonical CGMES subClassOf* idiom. The author
    // has typed it, so the implied-type hint would be noise and must be suppressed.
    var r =
        api.validateSparql(PREAMBLE + "SELECT * WHERE { ?s a ?t . ?s cim:ACLineSegment.r ?x . }");
    long hints =
        r.annotations().stream()
            .filter(an -> an.code() == SparqlValidationCode.QUERY_IMPLIED_TYPE)
            .count();
    assertEquals("dynamically-typed subject should not get an implied-type hint", 0, hints);
  }

  // -- DATATYPE_MISMATCH WARN when literal does not match range ---------------------------

  @Test
  public void datatypeMismatchWarning() {
    var r =
        api.validateSparql(
            PREAMBLE + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r \"abc\" . }");
    var a = expectSingle(r, SparqlValidationCode.DATATYPE_MISMATCH);
    assertEquals(SparqlValidationSeverity.WARN, a.severity());
    assertTrue(a.message().contains("xsd:string") || a.message().contains("string"));
  }

  @Test
  public void numericLiteralAcceptedForXsdDouble() {
    // "5"^^xsd:int is in the numeric family; range is xsd:double — compatible.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r \"5\"^^xsd:int ."
                + " }");
    long mismatch =
        r.annotations().stream()
            .filter(an -> an.code() == SparqlValidationCode.DATATYPE_MISMATCH)
            .count();
    assertEquals("xsd:int should be compatible with xsd:double", 0, mismatch);
  }

  @Test
  public void classRangeEmitsWarnForLiteral() {
    // PROP_EQ_CONTAINER has range VoltageLevel (a class). Using a literal instead of an IRI
    // reference should produce a DATATYPE_MISMATCH WARN.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:Equipment.EquipmentContainer"
                + " \"foo\" . }");
    var a = expectSingle(r, SparqlValidationCode.DATATYPE_MISMATCH);
    assertEquals(SparqlValidationSeverity.WARN, a.severity());
    assertTrue(
        "message should mention IRI reference",
        a.message().contains("IRI reference expected") || a.message().contains("class"));
  }

  // -- Property path chain checks ---------------------------------------------------------

  @Test
  public void compatiblePropertyPathChain() {
    // range(Equipment.EquipmentContainer) = VoltageLevel = domain(VoltageLevel.nominalVoltage)
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; "
                + "  cim:Equipment.EquipmentContainer/cim:VoltageLevel.nominalVoltage ?v . }");
    long chainErrors =
        r.annotations().stream()
            .filter(
                a ->
                    a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS
                        && a.message().contains("chain"))
            .count();
    assertEquals(0, chainErrors);
  }

  @Test
  public void incompatiblePropertyPathChain() {
    // range(VoltageLevel.Substation) = Substation; domain(ACLineSegment.r) = ACLineSegment.
    // Substation is not in the ACLineSegment hierarchy — chain should error.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { ?s cim:VoltageLevel.Substation/cim:ACLineSegment.r ?x . }");
    boolean found =
        r.annotations().stream()
            .anyMatch(
                a ->
                    a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS
                        && a.message().contains("chain"));
    assertTrue("expected path-chain error, got: " + r.annotations(), found);
  }

  // -- Scope-aware type inference: UNION branches are independent -------------------------

  @Test
  public void unionBranchTypesDoNotCrossContaminate() {
    // Branch 1 declares ?s as ACLineSegment; branch 2 uses nominalVoltage (domain=VoltageLevel).
    // The ACLineSegment type must NOT suppress an error in branch 2 — the branches are
    // alternatives and ?s in branch 2 has no declared type.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r } "
                + "  UNION "
                + "  { ?s cim:VoltageLevel.nominalVoltage ?v } "
                + "}");
    // Branch 2's ?s has no type in scope 2 → implied-type INFO fires (not PROPERTY_NOT_ALLOWED).
    // The old code would incorrectly inherit ACLineSegment from branch 1 and fire an ERROR.
    long domainErrors =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS)
            .count();
    assertEquals(
        "type from UNION branch 1 must not produce domain error in branch 2", 0, domainErrors);
  }

  @Test
  public void correctUnionUsageHasNoError() {
    // Both branches use the same property correctly within their own typed contexts.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r } "
                + "  UNION "
                + "  { ?s a cim:VoltageLevel ; cim:VoltageLevel.nominalVoltage ?v } "
                + "}");
    assertNoErrors(r);
  }

  @Test
  public void optionalTypesDoNotPoisonRequiredPart() {
    // The required part uses ACLineSegment.r. An OPTIONAL block asserts ?s is VoltageLevel.
    // The old code would use VoltageLevel as the type for ?s and fire a domain error.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s cim:ACLineSegment.r ?r . "
                + "  OPTIONAL { ?s a cim:VoltageLevel } "
                + "}");
    long domainErrors =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS)
            .count();
    assertEquals("OPTIONAL type must not poison domain check in required part", 0, domainErrors);
  }

  @Test
  public void rootTypePropagatesIntoOptional() {
    // The required part types ?s as ACLineSegment; the OPTIONAL uses ACLineSegment.r.
    // The root type must still reach the OPTIONAL body so domain check passes.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s a cim:ACLineSegment . "
                + "  OPTIONAL { ?s cim:ACLineSegment.r ?r } "
                + "}");
    assertNoErrors(r);
  }

  @Test
  public void unionBranchTypePropagatesIntoNestedOptional() {
    // Branch type (?s = ACLineSegment, in scope [0,1]) must propagate into the nested
    // OPTIONAL body (scope [0,1,2]) so that the domain check on ACLineSegment.r passes.
    // With a flat scope model this would produce QUERY_IMPLIED_TYPE (no type found).
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  { ?s a cim:ACLineSegment . OPTIONAL { ?s cim:ACLineSegment.r ?r } } "
                + "  UNION "
                + "  { ?s a cim:VoltageLevel ; cim:VoltageLevel.nominalVoltage ?v } "
                + "}");
    assertNoErrors(r);
  }

  // -- Negative patterns (MINUS, NOT EXISTS, EXISTS) must not leak type assertions -----------

  @Test
  public void minusTypeDoesNotPoisonOuterScope() {
    // MINUS { ?s a cim:VoltageLevel } is a negative pattern — the type there must not
    // bleed into the outer scope and trigger PROPERTY_NOT_ALLOWED_FOR_CLASS on ACLineSegment.r.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s cim:ACLineSegment.r ?r . "
                + "  MINUS { ?s a cim:VoltageLevel } "
                + "}");
    long domainErrors =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS)
            .count();
    assertEquals("MINUS type must not poison domain check in outer scope", 0, domainErrors);
  }

  @Test
  public void filterNotExistsTypeDoesNotPoisonOuterScope() {
    // FILTER NOT EXISTS { ?s a cim:VoltageLevel } — the type inside the NOT EXISTS
    // body must not leak into the outer scope's domain checks.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s cim:ACLineSegment.r ?r . "
                + "  FILTER NOT EXISTS { ?s a cim:VoltageLevel } "
                + "}");
    long domainErrors =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS)
            .count();
    assertEquals(
        "FILTER NOT EXISTS type must not poison domain check in outer scope", 0, domainErrors);
  }

  @Test
  public void filterExistsTypeDoesNotPoisonOuterScope() {
    // FILTER EXISTS { ?s a cim:VoltageLevel } — the type inside the EXISTS body must
    // not leak into the outer scope's domain checks.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s cim:ACLineSegment.r ?r . "
                + "  FILTER EXISTS { ?s a cim:VoltageLevel } "
                + "}");
    long domainErrors =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS)
            .count();
    assertEquals("FILTER EXISTS type must not poison domain check in outer scope", 0, domainErrors);
  }

  @Test
  public void minusBodyStillValidatesUnknownTerms() {
    // Even though MINUS is isolated for type inference, unknown properties inside it
    // must still be reported as errors.
    var r =
        api.validateSparql(
            PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s a cim:ACLineSegment . "
                + "  MINUS { ?s cim:doesNotExist ?x } "
                + "}");
    boolean hasUnknownProp =
        r.annotations().stream().anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_PROPERTY);
    assertTrue("MINUS body unknown property must still be reported", hasUnknownProp);
  }

  // -- silence on incomplete schemas ------------------------------------------------------

  @Test
  public void noErrorWhenDomainIsUnknown() {
    // Use a property that the schema declares but with no domain — checker stays silent.
    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                PROFILE,
                List.of(CLASS_AC_LINE),
                List.of(PROP_R),
                Map.of(), // no domains
                Map.of(),
                Map.of())
            .build();
    var quietApi = new SparqlValidationApi(index);
    var r =
        quietApi.validateSparql(
            PREAMBLE + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?x . }");
    assertNoErrors(r);
  }

  // ---- helpers --------------------------------------------------------------------------

  private static void assertNoErrors(SparqlValidationResult r) {
    var errors =
        r.annotations().stream()
            .filter(a -> a.severity() == SparqlValidationSeverity.ERROR)
            .toList();
    assertTrue("expected no ERROR annotations, got: " + errors, errors.isEmpty());
  }

  private static SparqlValidationAnnotation expectSingle(
      SparqlValidationResult r, SparqlValidationCode code) {
    var matches = r.annotations().stream().filter(a -> a.code() == code).toList();
    assertEquals(
        "expected exactly one annotation with code " + code + ", got: " + r.annotations(),
        1,
        matches.size());
    return matches.get(0);
  }
}
