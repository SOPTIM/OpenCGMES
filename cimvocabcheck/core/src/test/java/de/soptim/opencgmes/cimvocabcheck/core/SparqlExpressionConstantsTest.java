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
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests validation of constant IRIs used in {@code FILTER}, {@code VALUES} and {@code BIND}
 * expression positions — previously never checked for existence, enumeration membership or
 * vocabulary typos.
 */
public class SparqlExpressionConstantsTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  private static final String SCHEMA =
      "@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
          + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
          + "@prefix cim:  <"
          + CIM
          + "> .\n"
          + "cim:WindGenUnitKind a rdfs:Class .\n"
          + "cim:WindGenUnitKind.offshore a cim:WindGenUnitKind .\n"
          + "cim:WindGenUnitKind.onshore  a cim:WindGenUnitKind .\n"
          + "cim:WindGeneratingUnit a rdfs:Class .\n"
          + "cim:WindGeneratingUnit.windGenUnitType a rdf:Property ;\n"
          + "  rdfs:domain cim:WindGeneratingUnit ; rdfs:range cim:WindGenUnitKind .\n";

  private static final String PREFIXES =
      "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
          + "PREFIX cim: <"
          + CIM
          + ">\n"
          + "PREFIX ex:  <http://example.org/>\n";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    var g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(SCHEMA, Lang.TURTLE).parse(g);
    api =
        new SparqlValidationApi(
            RdfsSchemaIndex.builder().addProfile(VersionIri.of("urn:p"), g).build());
  }

  // ---- FILTER equality --------------------------------------------------------------------

  @Test
  public void filterEquality_validEnumMember_accepted() {
    var r =
        validate(
            "SELECT * WHERE { ?u cim:WindGeneratingUnit.windGenUnitType ?kind ."
                + " FILTER(?kind = cim:WindGenUnitKind.offshore) }");
    assertFalse(has(r, SparqlValidationCode.INVALID_ENUM_VALUE));
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_TERM_IN_EXPRESSION));
  }

  @Test
  public void filterEquality_typoEnumMember_flagged() {
    var r =
        validate(
            "SELECT * WHERE { ?u cim:WindGeneratingUnit.windGenUnitType ?kind ."
                + " FILTER(?kind = cim:WindGenUnitKind.offshroe) }");
    assertTrue(
        hasTerm(r, SparqlValidationCode.INVALID_ENUM_VALUE, CIM + "WindGenUnitKind.offshroe"));
  }

  @Test
  public void filterIn_typoEnumMember_flagged() {
    var r =
        validate(
            "SELECT * WHERE { ?u cim:WindGeneratingUnit.windGenUnitType ?kind . FILTER(?kind IN"
                + " (cim:WindGenUnitKind.offshore, cim:WindGenUnitKind.offshroe)) }");
    assertTrue(
        hasTerm(r, SparqlValidationCode.INVALID_ENUM_VALUE, CIM + "WindGenUnitKind.offshroe"));
    assertFalse(
        hasTerm(r, SparqlValidationCode.INVALID_ENUM_VALUE, CIM + "WindGenUnitKind.offshore"));
  }

  @Test
  public void filterEquality_closedNamespaceTypo_reportedAsVocabulary() {
    var r =
        validate(
            "SELECT * WHERE { ?u cim:WindGeneratingUnit.windGenUnitType ?kind ."
                + " FILTER(?kind = rdf:typ) }");
    assertTrue(hasTerm(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM, RDF() + "typ"));
  }

  @Test
  public void filterEquality_instanceIri_notErrorButWarn() {
    var r =
        validate("SELECT * WHERE { ?u a cim:WindGeneratingUnit . FILTER(?u = ex:SomeInstance) }");
    assertFalse(
        "instance IRI must not be an enum error", has(r, SparqlValidationCode.INVALID_ENUM_VALUE));
    // An unknown instance IRI is a soft warning, never an error.
    r.annotations().stream()
        .filter(
            a -> a.term() != null && ("http://example.org/SomeInstance").equals(a.term().getURI()))
        .forEach(a -> assertEquals(SparqlValidationSeverity.WARN, a.severity()));
  }

  // ---- VALUES -----------------------------------------------------------------------------

  @Test
  public void values_typoEnumMember_flagged() {
    var r =
        validate(
            "SELECT * WHERE { ?u cim:WindGeneratingUnit.windGenUnitType ?kind ."
                + " VALUES ?kind { cim:WindGenUnitKind.offshore cim:WindGenUnitKind.offshroe } }");
    assertTrue(
        hasTerm(r, SparqlValidationCode.INVALID_ENUM_VALUE, CIM + "WindGenUnitKind.offshroe"));
  }

  @Test
  public void values_unknownTerm_withoutContext_warns() {
    var r = validate("SELECT * WHERE { ?u ?p ?k . VALUES ?k { cim:NoSuchThing } }");
    assertTrue(hasTerm(r, SparqlValidationCode.UNKNOWN_TERM_IN_EXPRESSION, CIM + "NoSuchThing"));
  }

  // ---- BIND -------------------------------------------------------------------------------

  @Test
  public void bind_unknownConstant_warns() {
    var r =
        validate("SELECT * WHERE { ?u a cim:WindGeneratingUnit . BIND(cim:NoSuchThing AS ?x) }");
    assertTrue(hasTerm(r, SparqlValidationCode.UNKNOWN_TERM_IN_EXPRESSION, CIM + "NoSuchThing"));
  }

  @Test
  public void bind_headerExtensionConstant_accepted() {
    var r = validate("SELECT * WHERE { ?u a cim:WindGeneratingUnit . BIND(rdf:Statements AS ?x) }");
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_TERM_IN_EXPRESSION));
    assertFalse(has(r, SparqlValidationCode.UNKNOWN_VOCABULARY_TERM));
  }

  // ---- helpers ----------------------------------------------------------------------------

  private static String RDF() {
    return "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  }

  private SparqlValidationResult validate(String body) {
    return api.validateSparql(PREFIXES + body);
  }

  private static boolean has(SparqlValidationResult r, SparqlValidationCode code) {
    return r.annotations().stream().anyMatch(a -> a.code() == code);
  }

  private static boolean hasTerm(SparqlValidationResult r, SparqlValidationCode code, String uri) {
    return r.annotations().stream()
        .anyMatch(a -> a.code() == code && a.term() != null && uri.equals(a.term().getURI()));
  }
}
