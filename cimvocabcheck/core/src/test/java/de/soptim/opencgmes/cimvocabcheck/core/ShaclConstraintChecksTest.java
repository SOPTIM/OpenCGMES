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

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclShapeAnalyzer;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@code sh:nodeKind} / range-compatibility check and the {@code sh:minCount} /
 * {@code sh:maxCount} cardinality-consistency check in {@link ShaclShapeAnalyzer}.
 *
 * <p>The schema used here has explicit range declarations so the nodeKind check fires:
 *
 * <ul>
 *   <li>{@code cim:ACLineSegment.r} → {@code xsd:double} (datatype property)
 *   <li>{@code cim:IdentifiedObject.name} → {@code xsd:string} (datatype property)
 *   <li>{@code cim:Equipment.EquipmentContainer} → {@code cim:Substation} (object property)
 * </ul>
 */
public class ShaclConstraintChecksTest {

  private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String SH = "http://www.w3.org/ns/shacl#";

  private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
  private static final String CLASS_SUBSTATION = CIM + "Substation";

  private static final String PROP_R = CIM + "ACLineSegment.r";
  private static final String PROP_NAME = CIM + "IdentifiedObject.name";
  private static final String PROP_CONTAINER = CIM + "Equipment.EquipmentContainer";

  private static final String PROFILE = "http://example.org/profile/1.0";

  private static final String PREFIXES =
      "@prefix sh:  <"
          + SH
          + "> .\n"
          + "@prefix xsd: <"
          + XSD
          + "> .\n"
          + "@prefix cim: <"
          + CIM
          + "> .\n"
          + "@prefix ex:  <http://example.org/> .\n";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                PROFILE,
                List.of(CLASS_AC_LINE, CLASS_SUBSTATION),
                List.of(PROP_R, PROP_NAME, PROP_CONTAINER),
                Map.of(
                    PROP_R, List.of(CLASS_AC_LINE),
                    PROP_NAME, List.of(CLASS_AC_LINE),
                    PROP_CONTAINER, List.of(CLASS_AC_LINE)),
                Map.of(
                    PROP_R, List.of(XSD + "double"),
                    PROP_NAME, List.of(XSD + "string"),
                    PROP_CONTAINER, List.of(CLASS_SUBSTATION)),
                Map.of())
            .build();
    api = new SparqlValidationApi(index);
  }

  // ============================================================================================
  // sh:nodeKind vs rdfs:range checks
  // ============================================================================================

  /** sh:nodeKind sh:IRI on a datatype property (xsd:double range) → WARN. */
  @Test
  public void nodeKind_IRI_on_datatype_property_warns() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:nodeKind sh:IRI ] ."));
    assertTrue(hasNodeKindWarning(r, PROP_R));
  }

  /** sh:nodeKind sh:BlankNode on a datatype property (xsd:double range) → WARN. */
  @Test
  public void nodeKind_BlankNode_on_datatype_property_warns() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:nodeKind sh:BlankNode ]"
                    + " ."));
    assertTrue(hasNodeKindWarning(r, PROP_R));
  }

  /** sh:nodeKind sh:BlankNodeOrIRI on a datatype property (xsd:double range) → WARN. */
  @Test
  public void nodeKind_BlankNodeOrIRI_on_datatype_property_warns() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:nodeKind"
                    + " sh:BlankNodeOrIRI ] ."));
    assertTrue(hasNodeKindWarning(r, PROP_R));
  }

  /** sh:nodeKind sh:Literal on an object property (class range) → WARN. */
  @Test
  public void nodeKind_Literal_on_object_property_warns() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:Equipment.EquipmentContainer ; sh:nodeKind"
                    + " sh:Literal ] ."));
    assertTrue(hasNodeKindWarning(r, PROP_CONTAINER));
  }

  /** sh:nodeKind sh:Literal on a datatype property → no warning (consistent). */
  @Test
  public void nodeKind_Literal_on_datatype_property_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:nodeKind sh:Literal ]"
                    + " ."));
    assertFalse(
        "Literal nodeKind on a datatype property must not warn", hasNodeKindWarning(r, PROP_R));
  }

  /** sh:nodeKind sh:IRI on an object property (class range) → no warning (consistent). */
  @Test
  public void nodeKind_IRI_on_object_property_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:Equipment.EquipmentContainer ; sh:nodeKind"
                    + " sh:IRI ] ."));
    assertFalse(
        "IRI nodeKind on an object property must not warn", hasNodeKindWarning(r, PROP_CONTAINER));
  }

  /** sh:nodeKind sh:BlankNodeOrIRI on an object property (class range) → no warning. */
  @Test
  public void nodeKind_BlankNodeOrIRI_on_object_property_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:Equipment.EquipmentContainer ; sh:nodeKind"
                    + " sh:BlankNodeOrIRI ] ."));
    assertFalse(hasNodeKindWarning(r, PROP_CONTAINER));
  }

  /**
   * sh:nodeKind sh:IRIOrLiteral — ambiguous (could be either), so we do not flag it even when range
   * is unambiguously a datatype.
   */
  @Test
  public void nodeKind_IRIOrLiteral_not_flagged() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:nodeKind sh:IRIOrLiteral"
                    + " ] ."));
    assertFalse(
        "sh:IRIOrLiteral is ambiguous — must not produce a nodeKind warning",
        hasNodeKindWarning(r, PROP_R));
  }

  /**
   * sh:nodeKind sh:BlankNodeOrLiteral — ambiguous (includes literals, which matches a datatype
   * property). We do not flag it even when nodeKind restricts to non-IRI.
   */
  @Test
  public void nodeKind_BlankNodeOrLiteral_on_datatype_not_flagged() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:nodeKind"
                    + " sh:BlankNodeOrLiteral ] ."));
    assertFalse(
        "sh:BlankNodeOrLiteral includes Literal — must not warn for a datatype property",
        hasNodeKindWarning(r, PROP_R));
  }

  /** sh:nodeKind on an inverse path — complex path, check must be skipped. */
  @Test
  public void nodeKind_on_inverse_path_skipped() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path [ sh:inversePath cim:ACLineSegment.r ] ;"
                    + " sh:nodeKind sh:IRI ] ."));
    assertFalse(
        "nodeKind check must be skipped for non-simple (inverse) paths",
        hasNodeKindWarning(r, PROP_R));
  }

  /** sh:nodeKind on a sequence path — complex path, check must be skipped. */
  @Test
  public void nodeKind_on_sequence_path_skipped() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path ( cim:Equipment.EquipmentContainer"
                    + " cim:IdentifiedObject.name ) ; sh:nodeKind sh:IRI ] ."));
    assertFalse(
        "nodeKind check must be skipped for sequence paths",
        r.shapeAnnotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE));
  }

  /** Property not known in schema → no range information → nodeKind check skipped. */
  @Test
  public void nodeKind_property_not_in_schema_no_range_skipped() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:UnknownProperty ; sh:nodeKind sh:IRI ] ."));
    assertFalse(
        "nodeKind check must be skipped when property has no schema range",
        r.shapeAnnotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE));
  }

  // ============================================================================================
  // sh:minCount / sh:maxCount cardinality checks
  // ============================================================================================

  /** sh:minCount 3, sh:maxCount 1 — contradiction → ERROR. */
  @Test
  public void cardinality_minGreaterThanMax_isError() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 3 ; sh:maxCount"
                    + " 1 ] ."));
    assertTrue("minCount > maxCount must produce INVALID_CARDINALITY", hasCardinalityError(r));
  }

  /** sh:minCount 1, sh:maxCount 0 — contradiction (nothing can match) → ERROR. */
  @Test
  public void cardinality_minOne_maxZero_isError() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 1 ; sh:maxCount"
                    + " 0 ] ."));
    assertTrue("minCount 1, maxCount 0 must produce INVALID_CARDINALITY", hasCardinalityError(r));
  }

  /** sh:minCount 2, sh:maxCount 2 — equal, valid. */
  @Test
  public void cardinality_minEqualsMax_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 2 ; sh:maxCount"
                    + " 2 ] ."));
    assertFalse(
        "minCount == maxCount must not produce a cardinality error", hasCardinalityError(r));
  }

  /** sh:minCount 0, sh:maxCount 0 — explicitly forbidden property, valid cardinality. */
  @Test
  public void cardinality_minZero_maxZero_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 0 ; sh:maxCount"
                    + " 0 ] ."));
    assertFalse(hasCardinalityError(r));
  }

  /** sh:minCount 1, sh:maxCount 5 — normal range, valid. */
  @Test
  public void cardinality_normalRange_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 1 ; sh:maxCount"
                    + " 5 ] ."));
    assertFalse(hasCardinalityError(r));
  }

  /** Only sh:minCount present, no sh:maxCount → no contradiction possible → no error. */
  @Test
  public void cardinality_minOnly_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 1 ] ."));
    assertFalse("minCount alone must not produce a cardinality error", hasCardinalityError(r));
  }

  /** Only sh:maxCount present, no sh:minCount → no contradiction possible → no error. */
  @Test
  public void cardinality_maxOnly_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:maxCount 5 ] ."));
    assertFalse("maxCount alone must not produce a cardinality error", hasCardinalityError(r));
  }

  /**
   * Two separate property shapes in the same graph: one valid cardinality, one invalid. Only the
   * invalid one should be reported.
   */
  @Test
  public void cardinality_twoShapes_onlyBadOneReported() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 1 ; sh:maxCount"
                    + " 5 ] ;\n"
                    + "   sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCount 3 ;"
                    + " sh:maxCount 1 ] ."));
    long count =
        r.shapeAnnotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.INVALID_CARDINALITY)
            .count();
    assertEquals("exactly one INVALID_CARDINALITY annotation expected", 1, count);
  }

  /** Cardinality error annotation identifies the property path as the flagged term. */
  @Test
  public void cardinality_annotation_term_is_path_property() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 3 ; sh:maxCount"
                    + " 1 ] ."));
    boolean found =
        r.shapeAnnotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.INVALID_CARDINALITY)
            .anyMatch(a -> a.term() != null && PROP_R.equals(a.term().getURI()));
    assertTrue("INVALID_CARDINALITY annotation must reference the path property", found);
  }

  // ============================================================================================
  // sh:datatype vs rdfs:range checks
  // ============================================================================================

  /** sh:datatype on a property with class range (object property) → WARN. */
  @Test
  public void datatype_on_objectProperty_warns() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:Equipment.EquipmentContainer ; sh:datatype"
                    + " xsd:string ] ."));
    assertTrue(
        "sh:datatype on object property must produce DATATYPE_INCOMPATIBLE_WITH_RANGE",
        hasDatatypeIncompatibleWarning(r, PROP_CONTAINER));
  }

  /** sh:datatype on a property with datatype range → no warning (consistent). */
  @Test
  public void datatype_on_datatypeProperty_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:datatype xsd:double ]"
                    + " ."));
    assertFalse(
        "sh:datatype on datatype property must not produce DATATYPE_INCOMPATIBLE_WITH_RANGE",
        hasDatatypeIncompatibleWarning(r, PROP_R));
  }

  /** sh:datatype on a property not in schema → no range → check skipped. */
  @Test
  public void datatype_on_unknownProperty_skipped() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:UnknownProp ; sh:datatype xsd:string ] ."));
    assertFalse(
        "sh:datatype on unknown property (no schema range) must not warn",
        r.shapeAnnotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.DATATYPE_INCOMPATIBLE_WITH_RANGE));
  }

  /** sh:datatype on a complex (inverse) path → check skipped. */
  @Test
  public void datatype_on_complexPath_skipped() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path [ sh:inversePath cim:Equipment.EquipmentContainer"
                    + " ] ; sh:datatype xsd:string ] ."));
    assertFalse(
        "sh:datatype check must be skipped for non-simple paths",
        r.shapeAnnotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.DATATYPE_INCOMPATIBLE_WITH_RANGE));
  }

  // ============================================================================================
  // sh:class vs rdfs:range checks
  // ============================================================================================

  /** sh:class on a property with datatype range (datatype property) → WARN. */
  @Test
  public void class_on_datatypeProperty_warns() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:class cim:Substation"
                    + " ] ."));
    assertTrue(
        "sh:class on datatype property must produce CLASS_INCOMPATIBLE_WITH_RANGE",
        hasClassIncompatibleWarning(r, PROP_R));
  }

  /** sh:class on a property with class range (object property) → no warning. */
  @Test
  public void class_on_objectProperty_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:Equipment.EquipmentContainer ; sh:class"
                    + " cim:Substation ] ."));
    assertFalse(
        "sh:class on object property must not produce CLASS_INCOMPATIBLE_WITH_RANGE",
        hasClassIncompatibleWarning(r, PROP_CONTAINER));
  }

  /** sh:class on a property not in schema → no range → check skipped. */
  @Test
  public void class_on_unknownProperty_skipped() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:UnknownProp ; sh:class cim:Substation ] ."));
    assertFalse(
        "sh:class on unknown property (no schema range) must not warn",
        r.shapeAnnotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.CLASS_INCOMPATIBLE_WITH_RANGE));
  }

  // ============================================================================================
  // Interaction: both checks on the same property shape
  // ============================================================================================

  /** A property shape can have both a nodeKind warning and a cardinality error simultaneously. */
  @Test
  public void both_nodeKind_and_cardinality_errors_on_same_shape() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [\n"
                    + "    sh:path cim:ACLineSegment.r ;\n"
                    + "    sh:nodeKind sh:IRI ;\n"
                    + "    sh:minCount 3 ;\n"
                    + "    sh:maxCount 1 ;\n"
                    + "] ."));
    boolean hasNodeKind =
        r.shapeAnnotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE);
    boolean hasCardinality =
        r.shapeAnnotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.INVALID_CARDINALITY);
    assertTrue("nodeKind mismatch must be reported", hasNodeKind);
    assertTrue("cardinality contradiction must be reported", hasCardinality);
  }

  // ============================================================================================
  // sh:qualifiedMinCount / sh:qualifiedMaxCount checks
  // ============================================================================================

  private static final String QUALIFIED_PATH =
      "sh:path cim:Equipment.EquipmentContainer ; sh:qualifiedValueShape [ sh:class cim:Substation"
          + " ] ; ";

  /** qualifiedMinCount above qualifiedMaxCount — no value node set can satisfy both. */
  @Test
  public void qualified_minAboveMax_isError() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:qualifiedMinCount 3 ; sh:qualifiedMaxCount 1 ] ."));
    assertEquals(1, count(r, SparqlValidationCode.INVALID_CARDINALITY));
  }

  /** The conforming values are a subset of all values, so they cannot outnumber sh:maxCount. */
  @Test
  public void qualified_minAboveMaxCount_isError() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:qualifiedMinCount 2 ; sh:maxCount 1 ] ."));
    assertEquals(1, count(r, SparqlValidationCode.INVALID_CARDINALITY));
  }

  /** Equal qualified bounds and a qualified upper bound within sh:maxCount are both satisfiable. */
  @Test
  public void qualified_consistentBounds_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:qualifiedMinCount 2 ; sh:qualifiedMaxCount 2 ; sh:maxCount 4 ] ;\n"
                    + "   sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:qualifiedMaxCount 3 ] ."));
    assertEquals(0, count(r, SparqlValidationCode.INVALID_CARDINALITY));
    assertEquals(0, count(r, SparqlValidationCode.INVALID_CONSTRAINT_PARAMETER));
  }

  /** At least one qualified count is a mandatory parameter of sh:qualifiedValueShape. */
  @Test
  public void qualified_withoutAnyCount_isError() {
    var r =
        api.validateShacl(
            parseShapes(PREFIXES + "ex:S sh:property [ " + QUALIFIED_PATH + "sh:maxCount 4 ] ."));
    assertEquals(1, count(r, SparqlValidationCode.INVALID_CONSTRAINT_PARAMETER));
  }

  /** Qualified counts are checked on node shapes too, not only on property shapes. */
  @Test
  public void qualified_onNodeShape_isChecked() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:qualifiedValueShape [ sh:class cim:Substation ] ;"
                    + " sh:qualifiedMinCount 2 ; sh:qualifiedMaxCount 1 ."));
    assertEquals(1, count(r, SparqlValidationCode.INVALID_CARDINALITY));
  }

  /** A deactivated shape is ignored, including its qualified counts. */
  @Test
  public void qualified_deactivated_ignored() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:deactivated true ; sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:qualifiedMinCount 3 ; sh:qualifiedMaxCount 1 ] ;\n"
                    + "   sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:maxCount 1 ] ."));
    assertEquals(0, count(r, SparqlValidationCode.INVALID_CARDINALITY));
    assertEquals(0, count(r, SparqlValidationCode.INVALID_CONSTRAINT_PARAMETER));
  }

  // ============================================================================================
  // Negative count and length parameters
  // ============================================================================================

  /** Every count and length parameter must be a non-negative integer. */
  @Test
  public void negativeParameters_areErrors() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount -1 ] ;\n"
                    + "   sh:property [ sh:path cim:ACLineSegment.r ; sh:maxCount -1 ] ;\n"
                    + "   sh:property [ sh:path cim:IdentifiedObject.name ; sh:minLength -2 ;"
                    + " sh:maxLength -1 ] ;\n"
                    + "   sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:qualifiedMinCount -1 ; sh:qualifiedMaxCount -3 ] ."));
    assertEquals(6, count(r, SparqlValidationCode.INVALID_CONSTRAINT_PARAMETER));
  }

  /** The finding names the parameter and its value, and points at the shape's path property. */
  @Test
  public void negativeParameter_annotation_namesParameterAndPath() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:maxLength -5 ] ."));
    var annotation =
        r.shapeAnnotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.INVALID_CONSTRAINT_PARAMETER)
            .findFirst()
            .orElseThrow();
    assertEquals(SparqlValidationSeverity.ERROR, annotation.severity());
    assertTrue(annotation.message(), annotation.message().contains("sh:maxLength -5"));
    assertEquals(PROP_R, annotation.term().getURI());
  }

  /**
   * A negative bound is reported once as an ill-formed parameter and not compared as well — {@code
   * sh:minCount 0 ; sh:maxCount -1} would otherwise also look like min &gt; max.
   */
  @Test
  public void negativeParameter_notAlsoReportedAsContradiction() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 0 ;"
                    + " sh:maxCount -1 ] ;\n"
                    + "   sh:property [ "
                    + QUALIFIED_PATH
                    + "sh:qualifiedMinCount 1 ; sh:qualifiedMaxCount -1 ] ."));
    assertEquals(2, count(r, SparqlValidationCode.INVALID_CONSTRAINT_PARAMETER));
    assertEquals(0, count(r, SparqlValidationCode.INVALID_CARDINALITY));
  }

  /** Zero is a valid count and length. */
  @Test
  public void zeroParameters_ok() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCount 0 ;"
                    + " sh:maxCount 0 ; sh:minLength 0 ; sh:maxLength 0 ] ."));
    assertEquals(0, count(r, SparqlValidationCode.INVALID_CONSTRAINT_PARAMETER));
  }

  // ============================================================================================
  // Helpers
  // ============================================================================================

  private static boolean hasNodeKindWarning(ShaclValidationResult r, String propUri) {
    return r.shapeAnnotations().stream()
        .anyMatch(
            a ->
                a.code() == SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE
                    && a.term() != null
                    && propUri.equals(a.term().getURI()));
  }

  private static long count(ShaclValidationResult r, SparqlValidationCode code) {
    return r.shapeAnnotations().stream().filter(a -> a.code() == code).count();
  }

  private static boolean hasCardinalityError(ShaclValidationResult r) {
    return r.shapeAnnotations().stream()
        .anyMatch(a -> a.code() == SparqlValidationCode.INVALID_CARDINALITY);
  }

  private static boolean hasDatatypeIncompatibleWarning(ShaclValidationResult r, String propUri) {
    return r.shapeAnnotations().stream()
        .anyMatch(
            a ->
                a.code() == SparqlValidationCode.DATATYPE_INCOMPATIBLE_WITH_RANGE
                    && a.term() != null
                    && propUri.equals(a.term().getURI()));
  }

  private static boolean hasClassIncompatibleWarning(ShaclValidationResult r, String propUri) {
    return r.shapeAnnotations().stream()
        .anyMatch(
            a ->
                a.code() == SparqlValidationCode.CLASS_INCOMPATIBLE_WITH_RANGE
                    && a.term() != null
                    && propUri.equals(a.term().getURI()));
  }

  private static Graph parseShapes(String turtle) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(turtle, Lang.TURTLE).parse(g);
    return g;
  }
}
