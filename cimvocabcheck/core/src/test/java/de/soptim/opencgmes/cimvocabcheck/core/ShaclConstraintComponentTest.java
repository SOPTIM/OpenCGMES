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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
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
 * Regression tests for the SHACL shape analyzer's handling of constraint components, standard
 * vocabulary in class position, and terms defined locally in the shapes document.
 *
 * <p>These guard the Phase-1 false-positive fixes:
 *
 * <ul>
 *   <li>{@code sh:class}/{@code sh:targetClass} pointing at a standard-vocabulary class (e.g.
 *       {@code rdf:List}, {@code rdfs:Resource}) must not be reported as an unknown CIM class.
 *   <li>{@code sh:path}/{@code sh:class} inside a {@code sh:parameter} (custom constraint
 *       component) declare a parameter, not a CIM data property/class, and must not be checked
 *       against CIM.
 *   <li>Classes/properties declared in the same shapes file must not be reported as missing.
 * </ul>
 *
 * <p>Genuine typos must still be reported — the closing tests assert no over-suppression.
 */
public class ShaclConstraintComponentTest {

  private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String SH = "http://www.w3.org/ns/shacl#";
  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";

  private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
  private static final String CLASS_SUBSTATION = CIM + "Substation";
  private static final String PROP_R = CIM + "ACLineSegment.r";
  private static final String PROP_CONTAINER = CIM + "Equipment.EquipmentContainer";

  private static final String PROFILE = "http://example.org/profile/1.0";

  private static final String PREFIXES =
      "@prefix sh:   <"
          + SH
          + "> .\n"
          + "@prefix xsd:  <"
          + XSD
          + "> .\n"
          + "@prefix rdf:  <"
          + RDF
          + "> .\n"
          + "@prefix rdfs: <"
          + RDFS
          + "> .\n"
          + "@prefix cim:  <"
          + CIM
          + "> .\n"
          + "@prefix ex:   <http://example.org/> .\n"
          + "@prefix :     <http://example.org/local#> .\n";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                PROFILE,
                List.of(CLASS_AC_LINE, CLASS_SUBSTATION),
                List.of(PROP_R, PROP_CONTAINER),
                Map.of(
                    PROP_R, List.of(CLASS_AC_LINE),
                    PROP_CONTAINER, List.of(CLASS_AC_LINE)),
                Map.of(
                    PROP_R, List.of(XSD + "double"),
                    PROP_CONTAINER, List.of(CLASS_SUBSTATION)),
                Map.of())
            .build();
    api = new SparqlValidationApi(index);
  }

  // ============================================================================================
  // Custom constraint component (the reported user bug)
  // ============================================================================================

  /** The exact user-reported snippet must produce no unknown-term diagnostics. */
  @Test
  public void customConstraintComponent_noFalsePositives() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + ":MyKeyConstraintComponent\n"
                    + "    a sh:ConstraintComponent ;\n"
                    + "    sh:parameter [\n"
                    + "        sh:path :myproperty ;\n"
                    + "        sh:class rdf:List ;\n"
                    + "    ] ;\n"
                    + "    sh:nodeValidator :MyKeyValidator ."));
    assertFalse(
        "parameter sh:path :myproperty must not be reported as an unknown CIM property",
        hasCodeForTerm(
            r, SparqlValidationCode.UNKNOWN_PROPERTY, "http://example.org/local#myproperty"));
    assertFalse(
        "parameter sh:class rdf:List must not be reported as an unknown CIM class",
        hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, RDF + "List"));
    assertFalse(
        "rdf:List is a valid standard vocabulary term, not a typo",
        hasCode(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  /** A parameter's sh:path naming a fresh property is a declaration, never a CIM reference. */
  @Test
  public void parameterPath_notCheckedAgainstSchema() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + ":CC a sh:ConstraintComponent ;\n"
                    + "    sh:parameter [ sh:path :brandNewParam ] ."));
    assertFalse(
        hasCodeForTerm(
            r, SparqlValidationCode.UNKNOWN_PROPERTY, "http://example.org/local#brandNewParam"));
  }

  // ============================================================================================
  // Standard vocabulary in class position
  // ============================================================================================

  /** sh:targetClass rdfs:Resource — a valid standard class, not an unknown CIM class. */
  @Test
  public void targetClass_standardVocabulary_accepted() {
    var r =
        api.validateShacl(
            parseShapes(PREFIXES + "ex:S a sh:NodeShape ; sh:targetClass rdfs:Resource ."));
    assertFalse(hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, RDFS + "Resource"));
    assertFalse(hasCode(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  /** sh:class rdf:List on an ordinary property shape — standard class, accepted. */
  @Test
  public void shClass_standardVocabulary_accepted() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                    + "  sh:property [ sh:path cim:Equipment.EquipmentContainer ; sh:class rdf:List"
                    + " ] ."));
    assertFalse(hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, RDF + "List"));
  }

  // ============================================================================================
  // Locally-defined terms
  // ============================================================================================

  /** A class declared in the same file must not be reported as missing from the CIM schema. */
  @Test
  public void locallyDefinedClass_accepted() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + ":LocalClass a rdfs:Class .\n"
                    + "ex:S a sh:NodeShape ; sh:targetClass :LocalClass ."));
    assertFalse(
        hasCodeForTerm(
            r, SparqlValidationCode.UNKNOWN_CLASS, "http://example.org/local#LocalClass"));
  }

  /** A property declared in the same file must not be reported as missing from the CIM schema. */
  @Test
  public void locallyDefinedProperty_accepted() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + ":localProp a rdf:Property .\n"
                    + "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                    + "  sh:property [ sh:path :localProp ] ."));
    assertFalse(
        hasCodeForTerm(
            r, SparqlValidationCode.UNKNOWN_PROPERTY, "http://example.org/local#localProp"));
  }

  /** A property declared only via rdfs:domain (no rdf:type) is still a local definition. */
  @Test
  public void propertyDeclaredViaDomainOnly_accepted() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + ":helperProp rdfs:domain cim:ACLineSegment .\n"
                    + "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                    + "  sh:property [ sh:path :helperProp ] ."));
    assertFalse(
        hasCodeForTerm(
            r, SparqlValidationCode.UNKNOWN_PROPERTY, "http://example.org/local#helperProp"));
  }

  /** A class declared only via rdfs:subClassOf (no rdf:type) is still a local definition. */
  @Test
  public void classDeclaredViaSubClassOfOnly_accepted() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + ":LocalSub rdfs:subClassOf cim:ACLineSegment .\n"
                    + "ex:S a sh:NodeShape ; sh:targetClass :LocalSub ."));
    assertFalse(
        hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, "http://example.org/local#LocalSub"));
  }

  // ============================================================================================
  // No over-suppression — genuine errors must still be reported
  // ============================================================================================

  /** A genuine CIM class typo is still flagged. */
  @Test
  public void unknownCimClass_stillReported() {
    var r =
        api.validateShacl(
            parseShapes(PREFIXES + "ex:S a sh:NodeShape ; sh:targetClass cim:Substationn ."));
    assertTrue(hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, CIM + "Substationn"));
  }

  /** A genuine CIM property typo in an ordinary property shape is still flagged. */
  @Test
  public void unknownCimProperty_stillReported() {
    var r =
        api.validateShacl(
            parseShapes(
                PREFIXES
                    + "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                    + "  sh:property [ sh:path cim:ACLineSegment.rr ] ."));
    assertTrue(hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_PROPERTY, CIM + "ACLineSegment.rr"));
  }

  /** A closed-namespace typo in class position is a vocabulary typo, not a missing CIM class. */
  @Test
  public void closedNamespaceTypoInClassPosition_reportedAsVocabulary() {
    var r =
        api.validateShacl(parseShapes(PREFIXES + "ex:S a sh:NodeShape ; sh:targetClass sh:Fooo ."));
    assertTrue(
        "sh:Fooo must be reported as an unknown vocabulary term",
        hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM, SH + "Fooo"));
    assertFalse(
        "sh:Fooo must not be reported as an unknown CIM class",
        hasCodeForTerm(r, SparqlValidationCode.UNKNOWN_CLASS, SH + "Fooo"));
  }

  // ============================================================================================
  // helpers
  // ============================================================================================

  private static boolean hasCode(ShaclValidationResult r, SparqlValidationCode code) {
    return r.shapeAnnotations().stream().anyMatch(a -> a.code() == code);
  }

  private static boolean hasCodeForTerm(
      ShaclValidationResult r, SparqlValidationCode code, String uri) {
    return r.shapeAnnotations().stream()
        .anyMatch(a -> a.code() == code && a.term() != null && uri.equals(a.term().getURI()));
  }

  private static Graph parseShapes(String turtle) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(turtle, Lang.TURTLE).parse(g);
    return g;
  }
}
