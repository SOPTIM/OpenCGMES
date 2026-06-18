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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclValidationResult;
import java.util.List;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Test;

/**
 * Verifies term-level checking of the closed standard vocabularies (rdf/rdfs/owl/sh): genuine terms
 * are accepted, typos are reported as {@link SparqlValidationCode#UNKNOWN_VOCABULARY_TERM}, open
 * annotation namespaces stay exempt, and the {@code "ignore"} opt-out restores the legacy
 * behaviour.
 */
public class StandardVocabularyTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String PROFILE = "http://example.org/profile/Equipment/1.0";

  private static final String PREAMBLE =
      "PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
          + "PREFIX owl:  <http://www.w3.org/2002/07/owl#>\n"
          + "PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>\n"
          + "PREFIX dct:  <http://purl.org/dc/terms/>\n"
          + "PREFIX cim:  <"
          + CIM
          + ">\n";

  private static SparqlValidationApi api(boolean check) {
    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                PROFILE, List.of(CIM + "ACLineSegment"), List.of(CIM + "IdentifiedObject.name"))
            .build();
    return new SparqlValidationApi(index, DefaultPrefixes.BUILT_IN, check);
  }

  // ---- the loaded term sets are authoritative -------------------------------------------

  @Test
  public void knownTermsAreRecognised() {
    assertTrue(isKnown("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"));
    assertTrue(isKnown("http://www.w3.org/2000/01/rdf-schema#label"));
    assertTrue(isKnown("http://www.w3.org/2000/01/rdf-schema#subClassOf"));
    assertTrue(isKnown("http://www.w3.org/2002/07/owl#Class"));
    assertTrue(isKnown("http://www.w3.org/2002/07/owl#sameAs"));
    assertTrue(isKnown("http://www.w3.org/ns/shacl#minCount"));
    assertTrue(isKnown("http://www.w3.org/ns/shacl#NodeShape"));
  }

  /** Less-common but valid terms in checkable (predicate / class) position must not be flagged. */
  @Test
  public void uncommonButValidTermsAreRecognised() {
    assertTrue(isKnown("http://www.w3.org/2002/07/owl#topObjectProperty"));
    assertTrue(isKnown("http://www.w3.org/2002/07/owl#NamedIndividual"));
    assertTrue(isKnown("http://www.w3.org/2000/01/rdf-schema#isDefinedBy"));
    assertTrue(isKnown("http://www.w3.org/2000/01/rdf-schema#Datatype"));
    assertTrue(isKnown("http://www.w3.org/ns/shacl#PropertyShape"));
    assertTrue(isKnown("http://www.w3.org/ns/shacl#qualifiedValueShape"));
  }

  @Test
  public void typosAreNotRecognisedButAreInAClosedNamespace() {
    assertFalse(isKnown("http://www.w3.org/1999/02/22-rdf-syntax-ns#typ"));
    assertTrue(
        StandardVocabulary.isClosedNamespace(
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#typ")));
    assertFalse(
        StandardVocabulary.isClosedNamespace(
            NodeFactory.createURI("http://www.w3.org/2001/XMLSchema#string")));
  }

  // ---- query validation -----------------------------------------------------------------

  @Test
  public void genuineStandardTermsAreAccepted() {
    var r =
        api(true)
            .validateSparql(
                PREAMBLE
                    + "SELECT * WHERE {\n"
                    + "  ?s a cim:ACLineSegment ;\n"
                    + "     rdfs:label ?l ;\n"
                    + "     owl:sameAs ?o .\n"
                    + "}");
    assertNoVocabError(r);
    assertTrue(r.isValid());
  }

  @Test
  public void misspelledPropertyInClosedNamespaceIsReported() {
    var r = api(true).validateSparql(PREAMBLE + "SELECT * WHERE { ?s rdfs:labl ?l . }");
    var a = single(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM);
    assertTrue(a.message().contains("RDFS"));
    assertEquals(SparqlValidationSeverity.ERROR, a.severity());
  }

  @Test
  public void misspelledTypeObjectInClosedNamespaceIsReported() {
    var r = api(true).validateSparql(PREAMBLE + "SELECT * WHERE { ?s a owl:Clas . }");
    var a = single(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM);
    assertTrue(a.message().contains("OWL"));
  }

  @Test
  public void openAnnotationNamespaceStaysExempt() {
    // dct: (Dublin Core terms) is open — even a nonsense term is accepted silently.
    var r = api(true).validateSparql(PREAMBLE + "SELECT * WHERE { ?s dct:whateverNonsense ?o . }");
    assertNoVocabError(r);
  }

  @Test
  public void vocabularyErrorSurvivesPermissiveStrictness() {
    // UNKNOWN_VOCABULARY_TERM is a structural "term does not exist" error, like UNKNOWN_PROPERTY,
    // so it must not be filtered out by the most lenient strictness level.
    var r = api(true).validateSparql(PREAMBLE + "SELECT * WHERE { ?s rdfs:labl ?l . }");
    var kept = StrictnessLevel.PERMISSIVE.apply(r.annotations());
    assertTrue(
        "UNKNOWN_VOCABULARY_TERM must survive permissive mode",
        kept.stream().anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  @Test
  public void ignoreModeRestoresLegacyBehaviour() {
    var r = api(false).validateSparql(PREAMBLE + "SELECT * WHERE { ?s rdfs:labl ?l . }");
    assertNoVocabError(r);
    assertTrue(r.isValid());
  }

  // ---- SHACL validation -----------------------------------------------------------------

  private static final String SHACL_PREAMBLE =
      "@prefix sh:   <http://www.w3.org/ns/shacl#> .\n"
          + "@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
          + "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n"
          + "@prefix cim:  <"
          + CIM
          + "> .\n"
          + "@prefix ex:   <http://example.org/> .\n";

  @Test
  public void shaclValidShapeHasNoVocabularyErrors() {
    var r =
        shacl(
            true,
            SHACL_PREAMBLE
                + "ex:S a sh:NodeShape ;\n"
                + "  sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCount 1 ] .\n");
    assertTrue(vocabErrors(r).isEmpty());
  }

  @Test
  public void shaclMisspelledConstraintPredicateIsReported() {
    // sh:minCountt is a predicate typo — the most common SHACL mistake the user reported.
    var r =
        shacl(
            true,
            SHACL_PREAMBLE
                + "ex:S a sh:NodeShape ;\n"
                + "  sh:targetClass cim:ACLineSegment ;\n"
                + "  sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCountt 1 ] .\n");
    var hits = vocabErrors(r);
    assertEquals("expected exactly one vocab error, got: " + r.shapeAnnotations(), 1, hits.size());
    assertTrue(hits.get(0).message().contains("SHACL"));
  }

  @Test
  public void shaclMisspelledTypeObjectIsReported() {
    var r =
        shacl(
            true,
            SHACL_PREAMBLE + "ex:S a sh:NodeShapee ;\n" + "  sh:targetClass cim:ACLineSegment .\n");
    assertEquals(1, vocabErrors(r).size());
  }

  @Test
  public void shaclIgnoreModeSuppressesVocabularyErrors() {
    var r =
        shacl(
            false,
            SHACL_PREAMBLE
                + "ex:S a sh:NodeShape ;\n"
                + "  sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCountt 1 ] .\n");
    assertTrue(vocabErrors(r).isEmpty());
  }

  // ---- helpers --------------------------------------------------------------------------

  private static ShaclValidationResult shacl(boolean check, String turtle) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(turtle, Lang.TURTLE).parse(g);
    return api(check).validateShacl(g);
  }

  private static List<SparqlValidationAnnotation> vocabErrors(ShaclValidationResult r) {
    return r.shapeAnnotations().stream()
        .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_VOCABULARY_TERM)
        .toList();
  }

  private static boolean isKnown(String uri) {
    return StandardVocabulary.isKnownTerm(NodeFactory.createURI(uri));
  }

  private static void assertNoVocabError(SparqlValidationResult r) {
    var hits =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_VOCABULARY_TERM)
            .toList();
    assertTrue("expected no vocabulary errors, got: " + hits, hits.isEmpty());
  }

  private static SparqlValidationAnnotation single(
      SparqlValidationResult r, SparqlValidationCode code) {
    var matches = r.annotations().stream().filter(a -> a.code() == code).toList();
    assertEquals("expected exactly one " + code + ", got: " + r.annotations(), 1, matches.size());
    return matches.get(0);
  }
}
