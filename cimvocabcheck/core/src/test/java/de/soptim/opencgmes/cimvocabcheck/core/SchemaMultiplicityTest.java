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

import de.soptim.opencgmes.cimvocabcheck.core.schema.Multiplicity;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import java.util.Optional;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests indexing of CIM {@code cims:multiplicity} and the SHACL cardinality cross-check against it.
 */
public class SchemaMultiplicityTest {

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
          + "cim:ACLineSegment a rdfs:Class .\n"
          + "cim:ACLineSegment.r a rdf:Property ;\n"
          + "  rdfs:domain cim:ACLineSegment ; rdfs:range xsd:float ;\n"
          + "  cims:multiplicity <"
          + CIMS
          + "M:0..1> .\n"
          + "cim:ACLineSegment.name a rdf:Property ;\n"
          + "  rdfs:domain cim:ACLineSegment ; rdfs:range xsd:string ;\n"
          + "  cims:multiplicity <"
          + CIMS
          + "M:1..1> .\n";

  private static final String PREFIXES =
      "@prefix sh:  <http://www.w3.org/ns/shacl#> .\n"
          + "@prefix cim: <"
          + CIM
          + "> .\n"
          + "@prefix ex:  <http://example.org/> .\n";

  private RdfsSchemaIndex index;
  private SparqlValidationApi api;

  @Before
  public void setUp() {
    var g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(SCHEMA, Lang.TURTLE).parse(g);
    index = RdfsSchemaIndex.builder().addProfile(VersionIri.of("urn:p"), g).build();
    api = new SparqlValidationApi(index);
  }

  // ---- parsing ----------------------------------------------------------------------------

  @Test
  public void parsesMultiplicityForms() {
    assertEquals(new Multiplicity(0, 1), parse("M:0..1"));
    assertEquals(new Multiplicity(1, 1), parse("M:1..1"));
    assertEquals(new Multiplicity(1, 1), parse("M:1"));
    assertEquals(new Multiplicity(0, null), parse("M:0..n"));
    assertEquals(new Multiplicity(1, null), parse("M:1..n"));
    assertTrue(Multiplicity.parse(NodeFactory.createURI(CIMS + "notAMultiplicity")).isEmpty());
  }

  private static Multiplicity parse(String local) {
    return Multiplicity.parse(NodeFactory.createURI(CIMS + local)).orElseThrow();
  }

  // ---- indexing ---------------------------------------------------------------------------

  @Test
  public void multiplicityIsIndexed() {
    Optional<Multiplicity> m =
        index.multiplicityOf(NodeFactory.createURI(CIM + "ACLineSegment.r"), null);
    assertEquals(Optional.of(new Multiplicity(0, 1)), m);
  }

  // ---- cross-check ------------------------------------------------------------------------

  @Test
  public void minCountAboveSchemaUpperBound_flagged() {
    // schema says at most 1 (M:0..1), shape requires 2 → unsatisfiable.
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 2 ] .");
    assertTrue(has(r, SparqlValidationCode.CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY));
  }

  @Test
  public void maxCountBelowSchemaLowerBound_flagged() {
    // schema requires at least 1 (M:1..1), shape forbids all → unsatisfiable.
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.name ; sh:maxCount 0 ] .");
    assertTrue(has(r, SparqlValidationCode.CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY));
  }

  @Test
  public void stricterShapeThanSchema_accepted() {
    // schema M:0..1 (optional), profile tightens to exactly one → valid narrowing, no conflict.
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.r ; sh:minCount 1 ; sh:maxCount 1 ]"
                + " .");
    assertFalse(has(r, SparqlValidationCode.CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY));
  }

  @Test
  public void consistentShape_accepted() {
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.name ; sh:minCount 1 ; sh:maxCount 1 ]"
                + " .");
    assertFalse(has(r, SparqlValidationCode.CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY));
  }

  @Test
  public void propertyWithoutMultiplicity_notCrossChecked() {
    // A property the schema has no multiplicity for must never be flagged.
    var r =
        shacl(
            "ex:S a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:ACLineSegment.unknownProp ; sh:minCount 9 ] .");
    assertFalse(has(r, SparqlValidationCode.CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY));
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
}
