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

import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.Test;

/**
 * Tests for {@link SparqlValidationApi#checkSyntaxOnly(String)} — the schema-independent fallback
 * used when no schema can be resolved (e.g. an unreachable {@code # [endpoint=...]}).
 */
public class SyntaxOnlyValidationTest {

  @Test
  public void validQueryHasNoAnnotations() {
    var result = SparqlValidationApi.checkSyntaxOnly("SELECT * WHERE { ?s ?p ?o }");
    assertTrue(result.annotations().isEmpty());
  }

  @Test
  public void validUpdateHasNoAnnotations() {
    var result =
        SparqlValidationApi.checkSyntaxOnly(
            "INSERT DATA { <http://ex/a> <http://ex/p> <http://ex/b> }");
    assertTrue(result.annotations().isEmpty());
  }

  @Test
  public void brokenQueryYieldsSingleSyntaxError() {
    var result = SparqlValidationApi.checkSyntaxOnly("SELEECT * WHERE { ?s ?p ?o }");
    assertEquals(1, result.annotations().size());
    assertEquals(SparqlValidationCode.SYNTAX_ERROR, result.annotations().get(0).code());
  }

  @Test
  public void builtInPrefixesAreNotMisreportedAsSyntaxErrors() {
    // cim: is a built-in default prefix; using it without an explicit PREFIX line must not
    // be flagged as a syntax error by the fallback.
    var result = SparqlValidationApi.checkSyntaxOnly("SELECT * WHERE { ?s a cim:ACLineSegment }");
    assertTrue(result.annotations().isEmpty());
  }

  // ---- SHACL syntax-only fallback: schema-independent vocabulary-typo check ----------------

  private static org.apache.jena.graph.Graph turtle(String ttl) {
    var m = ModelFactory.createDefaultModel();
    RDFParser.fromString(ttl, Lang.TURTLE).parse(m);
    return m.getGraph();
  }

  @Test
  public void shaclSyntaxOnlyFlagsMisspelledShaclTerm() {
    // No schema is available (the syntax-only fallback), but a misspelt SHACL term must still
    // be reported — the vocabulary check is schema-independent.
    var g =
        turtle(
            """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            cim:Switch_open_Shape a sh:NodeShape ;
              sh:taaargetClass cim:Switch ;
              sh:property [ sh:path cim:Switch.open ; sh:datatype <http://www.w3.org/2001/XMLSchema#boolean> ] .
            """);
    var result = SparqlValidationApi.checkShaclSyntaxOnly(g);
    long vocab =
        result.shapeAnnotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_VOCABULARY_TERM)
            .count();
    assertEquals("misspelt sh: term should be flagged in the syntax-only fallback", 1, vocab);
  }

  @Test
  public void shaclSyntaxOnlyAcceptsValidShaclTerms() {
    var g =
        turtle(
            """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            cim:Switch_open_Shape a sh:NodeShape ;
              sh:targetClass cim:Switch ;
              sh:property [ sh:path cim:Switch.open ; sh:datatype <http://www.w3.org/2001/XMLSchema#boolean> ] .
            """);
    var result = SparqlValidationApi.checkShaclSyntaxOnly(g);
    assertTrue("valid SHACL terms must not be flagged", result.shapeAnnotations().isEmpty());
  }
}
