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
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import java.util.List;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Test;

/**
 * Verifies {@link SparqlValidationCode#NON_STANDARD_HEADER_TERM}: the non-standard {@code
 * rdf:Statements*} terms of the 552 header are warned about wherever they are used, while the
 * genuine W3C reification terms ({@code rdf:Statement}, {@code rdf:subject}, …) stay accepted.
 */
public class HeaderVocabularyTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

  private static final String SPARQL_PREFIXES =
      "PREFIX rdf: <" + RDF + ">\nPREFIX cim: <" + CIM + ">\n";

  private static final String SHACL_PREFIXES =
      "@prefix sh:  <http://www.w3.org/ns/shacl#> .\n"
          + "@prefix rdf: <"
          + RDF
          + "> .\n"
          + "@prefix cim: <"
          + CIM
          + "> .\n"
          + "@prefix ex:  <http://example.org/> .\n";

  private final SparqlValidationApi api =
      new SparqlValidationApi(
          RdfsSchemaIndex.builder()
              .addProfile(
                  "http://example.org/profile/1.0",
                  List.of(CIM + "ACLineSegment"),
                  List.of(CIM + "IdentifiedObject.name"))
              .build());

  // ---- SPARQL ---------------------------------------------------------------------------

  @Test
  public void sparql_headerTermsInEveryPosition_warned() {
    var r =
        api.validateSparql(
            SPARQL_PREFIXES
                + "SELECT * WHERE { ?s a rdf:Statements ; rdf:Statements.predicate ?p ."
                + " FILTER(?p != rdf:Statements.object) }");
    var hits = headerTerms(r.annotations());
    assertEquals("class, predicate and expression constant: " + r.annotations(), 3, hits.size());
    assertTrue(hits.stream().allMatch(a -> a.severity() == SparqlValidationSeverity.WARN));
    assertTrue(hits.get(0).message().contains("not an RDF term"));
  }

  @Test
  public void sparql_standardReificationTerms_notWarned() {
    var r =
        api.validateSparql(
            SPARQL_PREFIXES
                + "SELECT * WHERE { ?st a rdf:Statement ; rdf:subject ?s ; rdf:predicate ?p ;"
                + " rdf:object ?o . }");
    assertTrue(r.annotations().toString(), headerTerms(r.annotations()).isEmpty());
    assertTrue(r.annotations().toString(), r.isValid());
  }

  @Test
  public void sparql_typoNextToHeaderTerm_stillVocabularyError() {
    var r = api.validateSparql(SPARQL_PREFIXES + "SELECT * WHERE { ?s rdf:Statements.subjekt ?o }");
    assertTrue(headerTerms(r.annotations()).isEmpty());
    assertTrue(
        r.annotations().stream()
            .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  // ---- SHACL ----------------------------------------------------------------------------

  /** Each shape using a header term is marked, but one shape is marked once per term. */
  @Test
  public void shacl_reportedOncePerTermAndShape() {
    var r =
        api.validateShacl(
            shapes(
                "ex:A sh:path ( cim:IdentifiedObject.name rdf:Statements.subject ) ;"
                    + " sh:in ( rdf:Statements.subject rdf:Statements ) .\n"
                    + "ex:B sh:path [ sh:inversePath rdf:Statements.subject ] .\n"));
    var hits = headerTerms(r.shapeAnnotations());
    assertEquals(r.shapeAnnotations().toString(), 3, hits.size());
    assertTrue(
        "the owning named shape is the location hint",
        hits.stream().allMatch(a -> a.locationHint() != null && a.locationHint().isURI()));
  }

  @Test
  public void shacl_deactivatedShape_notWarned() {
    var r =
        api.validateShacl(
            shapes(
                "ex:S a sh:NodeShape ; sh:deactivated true ;\n"
                    + "  sh:property [ sh:path ( cim:IdentifiedObject.name rdf:Statements.object"
                    + " ) ] ."));
    assertTrue(r.shapeAnnotations().toString(), headerTerms(r.shapeAnnotations()).isEmpty());
  }

  @Test
  public void shacl_standardReificationTerms_notWarned() {
    var r =
        api.validateShacl(
            shapes(
                "ex:S a sh:NodeShape ; sh:targetClass rdf:Statement ;\n"
                    + "  sh:property [ sh:path rdf:predicate ; sh:maxCount 1 ] ."));
    assertTrue(r.shapeAnnotations().toString(), headerTerms(r.shapeAnnotations()).isEmpty());
  }

  /** The warning needs no schema, so the syntax-only fallback reports it too. */
  @Test
  public void shacl_syntaxOnlyFallback_warned() {
    var r =
        SparqlValidationApi.checkShaclSyntaxOnly(
            shapes("ex:S sh:path ( cim:IdentifiedObject.name rdf:Statements.subject ) ."));
    assertEquals(1, headerTerms(r.shapeAnnotations()).size());
  }

  // ---- helpers --------------------------------------------------------------------------

  private static List<SparqlValidationAnnotation> headerTerms(
      List<SparqlValidationAnnotation> annotations) {
    return annotations.stream()
        .filter(a -> a.code() == SparqlValidationCode.NON_STANDARD_HEADER_TERM)
        .toList();
  }

  private static Graph shapes(String turtle) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(SHACL_PREFIXES + turtle, Lang.TURTLE).parse(g);
    return g;
  }
}
