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

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import java.util.Collection;
import java.util.List;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SparqlValidationApi#inferProfileScope(Graph)} and the updated {@link
 * SparqlValidationApi#validateShacl(Graph)} (which now uses inferred scope instead of
 * all-profiles).
 */
public class InferProfileScopeTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String SH = "http://www.w3.org/ns/shacl#";

  private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
  private static final String CLASS_SUB = CIM + "Substation";
  private static final String CLASS_TP_NODE = CIM + "TopologicalNode";
  private static final String PROP_R = CIM + "ACLineSegment.r";
  private static final String PROP_NOMINAL = CIM + "TopologicalNode.nominalVoltage";

  private static final String PROFILE_EQ = "http://example.org/profile/EQ/1.0";
  private static final String PROFILE_TP = "http://example.org/profile/TP/1.0";
  private static final String PROFILE_DY = "http://example.org/profile/DY/1.0";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(PROFILE_EQ, List.of(CLASS_AC_LINE, CLASS_SUB), List.of(PROP_R))
            .addProfile(PROFILE_TP, List.of(CLASS_TP_NODE), List.of(PROP_NOMINAL))
            .addProfile(PROFILE_DY, List.of(CIM + "SynchronousMachine"), List.of())
            .build();
    api = new SparqlValidationApi(index);
  }

  // ============================================================================================
  // inferProfileScope — scope from sh:targetClass
  // ============================================================================================

  @Test
  public void inferScope_targetClassEq_returnsEqOnly() {
    Graph shapes =
        parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            [] sh:targetClass cim:ACLineSegment .
            """);

    Collection<VersionIri> scope = api.inferProfileScope(shapes);

    assertTrue("EQ profile expected", scope.contains(VersionIri.of(PROFILE_EQ)));
    assertFalse("TP profile must not be included", scope.contains(VersionIri.of(PROFILE_TP)));
    assertFalse("DY profile must not be included", scope.contains(VersionIri.of(PROFILE_DY)));
  }

  @Test
  public void inferScope_targetClassTp_returnsTpOnly() {
    Graph shapes =
        parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            [] sh:targetClass cim:TopologicalNode .
            """);

    Collection<VersionIri> scope = api.inferProfileScope(shapes);

    assertTrue("TP profile expected", scope.contains(VersionIri.of(PROFILE_TP)));
    assertFalse("EQ profile must not be included", scope.contains(VersionIri.of(PROFILE_EQ)));
  }

  // ============================================================================================
  // inferProfileScope — scope from sh:path
  // ============================================================================================

  @Test
  public void inferScope_shPath_includedInScope() {
    Graph shapes =
        parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            [] sh:property [ sh:path cim:TopologicalNode.nominalVoltage ] .
            """);

    Collection<VersionIri> scope = api.inferProfileScope(shapes);

    assertTrue("TP profile expected (via sh:path)", scope.contains(VersionIri.of(PROFILE_TP)));
    assertFalse("EQ must not be included", scope.contains(VersionIri.of(PROFILE_EQ)));
  }

  // ============================================================================================
  // inferProfileScope — cross-profile shapes
  // ============================================================================================

  @Test
  public void inferScope_crossProfile_returnsBothProfiles() {
    Graph shapes =
        parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            [] sh:targetClass cim:ACLineSegment .
            [] sh:targetClass cim:TopologicalNode .
            """);

    Collection<VersionIri> scope = api.inferProfileScope(shapes);

    assertTrue("EQ profile expected", scope.contains(VersionIri.of(PROFILE_EQ)));
    assertTrue("TP profile expected", scope.contains(VersionIri.of(PROFILE_TP)));
    assertFalse("DY profile must not be included", scope.contains(VersionIri.of(PROFILE_DY)));
    assertEquals("exactly two profiles", 2, scope.size());
  }

  // ============================================================================================
  // inferProfileScope — fallback to all profiles
  // ============================================================================================

  @Test
  public void inferScope_emptyGraph_returnsAllProfiles() {
    Graph shapes = GraphFactory.createDefaultGraph();
    Collection<VersionIri> scope = api.inferProfileScope(shapes);

    assertEquals("fallback: all three profiles", 3, scope.size());
    assertTrue(scope.contains(VersionIri.of(PROFILE_EQ)));
    assertTrue(scope.contains(VersionIri.of(PROFILE_TP)));
    assertTrue(scope.contains(VersionIri.of(PROFILE_DY)));
  }

  @Test
  public void inferScope_unknownTermsOnly_returnsAllProfiles() {
    Graph shapes =
        parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            [] sh:targetClass ex:NonCimClass .
            """);

    Collection<VersionIri> scope = api.inferProfileScope(shapes);

    assertEquals("unknown-terms-only → fallback to all profiles", 3, scope.size());
  }

  // ============================================================================================
  // inferProfileScope — embedded SPARQL terms contribute to scope
  // ============================================================================================

  @Test
  public void inferScope_embeddedSparqlTermsContribute() {
    // Structural position has only an EQ class; embedded SPARQL references a TP property.
    // Scope must include TP because of the embedded SPARQL term.
    Graph shapes =
        parse(
            """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex:  <http://example.org/shapes#> .

            ex:
                sh:declare [ sh:prefix "cim" ;
                             sh:namespace "http://iec.ch/TC57/CIM100#"^^xsd:anyURI ] .

            ex:Shape
                a sh:NodeShape ;
                sh:targetClass cim:ACLineSegment ;
                sh:sparql [
                    a sh:SPARQLConstraint ;
                    sh:prefixes ex: ;
                    sh:select \"\"\"
                        SELECT $this WHERE {
                            $this cim:TopologicalNode.nominalVoltage ?nv .
                        }
                    \"\"\" ;
                ] .
            """);

    Collection<VersionIri> scope = api.inferProfileScope(shapes);

    assertTrue(
        "EQ must be in scope (from sh:targetClass)", scope.contains(VersionIri.of(PROFILE_EQ)));
    assertTrue(
        "TP must be in scope (from embedded SPARQL)", scope.contains(VersionIri.of(PROFILE_TP)));
  }

  // ============================================================================================
  // validateShacl(Graph) — uses inferred scope, not all-profiles
  // ============================================================================================

  @Test
  public void validateShacl_noArgUsesInferredScope() {
    // Shapes reference only EQ terms. An unknown-class error should list only the
    // EQ profile in selectedProfiles — NOT the TP or DY profiles.
    Graph shapes =
        parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            [] sh:targetClass cim:ACLineSegment .
            [] sh:targetClass cim:NonExistentClass .
            """);

    ShaclValidationResult result = api.validateShacl(shapes);

    assertFalse("should have an error for the non-existent class", result.isValid());
    var annotations = result.shapeAnnotations();
    assertEquals("exactly one error expected", 1, annotations.size());

    SparqlValidationAnnotation a = annotations.get(0);
    assertEquals(SparqlValidationCode.UNKNOWN_CLASS, a.code());
    // selectedProfiles reflects the inferred scope {EQ}, not all three profiles
    assertEquals("inferred scope must be EQ only", 1, a.selectedProfiles().size());
    assertTrue(
        "EQ must be in selectedProfiles", a.selectedProfiles().contains(VersionIri.of(PROFILE_EQ)));
  }

  @Test
  public void validateShacl_dyOnlyTerms_fallsBackToAllProfiles() {
    // Shapes reference a DY-only class. Inferred scope = {DY}.
    // An unknown property against {DY} should report that profile.
    Graph shapes =
        parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            [] sh:targetClass cim:SynchronousMachine .
            [] sh:property [ sh:path cim:NonExistentProp ] .
            """);

    ShaclValidationResult result = api.validateShacl(shapes);
    assertFalse("sh:path references a non-existent property", result.isValid());
    // selectedProfiles of the error must be {DY} (inferred from sh:targetClass)
    boolean dyInSelectedProfiles =
        result.shapeAnnotations().stream()
            .anyMatch(a -> a.selectedProfiles().contains(VersionIri.of(PROFILE_DY)));
    assertTrue("DY must appear in selectedProfiles", dyInSelectedProfiles);
  }

  @Test
  public void validateShacl_emptyGraph_validWithFallback() {
    // An empty shapes graph has no errors regardless of scope.
    Graph shapes = GraphFactory.createDefaultGraph();
    ShaclValidationResult result = api.validateShacl(shapes);
    assertTrue("empty shapes graph must produce no errors", result.isValid());
  }

  // ============================================================================================
  // Helper
  // ============================================================================================

  private static Graph parse(String turtle) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(turtle, Lang.TURTLE).parse(g);
    return g;
  }
}
