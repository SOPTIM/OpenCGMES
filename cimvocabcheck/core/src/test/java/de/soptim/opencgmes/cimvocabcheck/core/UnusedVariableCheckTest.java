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

import java.util.List;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.Test;

/**
 * Tests for the unused-variable warnings {@code PROJECTED_VARIABLE_UNBOUND} and {@code
 * UNUSED_VARIABLE}. The check is schema-independent, so it is exercised through {@link
 * SparqlValidationApi#checkSyntaxOnly(String)}.
 */
public class UnusedVariableCheckTest {

  private static List<SparqlValidationAnnotation> warnings(String query) {
    return SparqlValidationApi.checkSyntaxOnly(query).annotations();
  }

  private static List<SparqlValidationAnnotation> ofCode(
      List<SparqlValidationAnnotation> annotations, SparqlValidationCode code) {
    return annotations.stream().filter(a -> a.code() == code).toList();
  }

  // ---- case 1: projected but unbound -------------------------------------------------------

  @Test
  public void projectedButUnboundVariableIsReported() {
    var ann =
        warnings(
            """
            SELECT ?s ?nmae WHERE {
              ?s cim:name ?name .
              FILTER(?name != "")
            }
            """);
    assertEquals(1, ann.size());
    var a = ann.get(0);
    assertEquals(SparqlValidationCode.PROJECTED_VARIABLE_UNBOUND, a.code());
    assertEquals(SparqlValidationSeverity.WARN, a.severity());
    assertTrue(a.message().contains("?nmae"));
    assertEquals("nmae", a.term().getName());
    // Located at the ?nmae token in the SELECT clause (line 1, after "SELECT ?s ").
    assertEquals(Integer.valueOf(1), a.line());
    assertEquals(Integer.valueOf(11), a.column());
  }

  @Test
  public void projectionExpressionArgumentsCountAsUsage() {
    var ann =
        warnings(
            """
            SELECT ?s (STRLEN(?name) AS ?len) WHERE {
              ?s cim:name ?name .
            }
            """);
    assertTrue(ann.isEmpty());
  }

  @Test
  public void orderByAloneDoesNotBindAProjectedVariable() {
    var ann =
        warnings("SELECT ?s ?x WHERE { ?s a cim:ACLineSegment . FILTER(?s != ?x) } ORDER BY ?x");
    // ?x is used in a FILTER inside the body — conservatively counted as appearing in the
    // query body, so no PROJECTED_VARIABLE_UNBOUND is raised for it.
    assertTrue(ann.isEmpty());
  }

  @Test
  public void constructTemplateVariableNeverBoundIsReported() {
    var ann =
        warnings(
            """
            CONSTRUCT { ?s cim:IdentifiedObject.name ?nmae } WHERE {
              ?s cim:name ?name .
              FILTER(?name != "")
            }
            """);
    assertEquals(1, ann.size());
    assertEquals(SparqlValidationCode.PROJECTED_VARIABLE_UNBOUND, ann.get(0).code());
    assertTrue(ann.get(0).message().contains("CONSTRUCT template"));
  }

  @Test
  public void describeVariableNeverBoundIsReported() {
    var ann = warnings("DESCRIBE ?x WHERE { ?s a cim:ACLineSegment . FILTER(?s != <urn:a>) }");
    assertEquals(1, ann.size());
    assertEquals(SparqlValidationCode.PROJECTED_VARIABLE_UNBOUND, ann.get(0).code());
    assertTrue(ann.get(0).message().contains("?x"));
  }

  @Test
  public void trailingValuesBlockBindsProjectedVariable() {
    var ann =
        warnings(
            "SELECT ?s ?v WHERE { ?s a cim:ACLineSegment . FILTER(?s != ?v) } VALUES ?v { 1 2 }");
    assertTrue(ann.isEmpty());
  }

  // ---- case 2: bound but unreferenced -------------------------------------------------------

  @Test
  public void boundButUnreferencedVariableIsReported() {
    var ann =
        warnings(
            """
            SELECT ?s WHERE {
              ?s a cim:ACLineSegment .
              ?s cim:name ?name .
            }
            """);
    assertEquals(1, ann.size());
    var a = ann.get(0);
    assertEquals(SparqlValidationCode.UNUSED_VARIABLE, a.code());
    assertEquals(SparqlValidationSeverity.WARN, a.severity());
    assertTrue(a.message().contains("?name"));
    assertEquals(Integer.valueOf(3), a.line());
  }

  @Test
  public void unusedBindTargetIsReported() {
    var ann = warnings("SELECT ?s WHERE { ?s a cim:ACLineSegment . BIND(1 AS ?x) }");
    assertEquals(1, ann.size());
    assertEquals(SparqlValidationCode.UNUSED_VARIABLE, ann.get(0).code());
    assertTrue(ann.get(0).message().contains("BIND"));
  }

  @Test
  public void unusedValuesVariableIsReported() {
    var ann = warnings("SELECT ?s WHERE { ?s a cim:ACLineSegment . VALUES ?v { 1 } }");
    assertEquals(1, ann.size());
    assertEquals(SparqlValidationCode.UNUSED_VARIABLE, ann.get(0).code());
    assertTrue(ann.get(0).message().contains("VALUES"));
  }

  @Test
  public void filterUsageCountsAsUse() {
    var ann = warnings("SELECT ?s WHERE { ?s cim:name ?name . FILTER(STRLEN(?name) > 0) }");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void orderByCountsAsUse() {
    var ann = warnings("SELECT ?s WHERE { ?s cim:name ?name } ORDER BY ?name");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void aggregateArgumentCountsAsUse() {
    var ann = warnings("SELECT ?type (COUNT(?s) AS ?n) WHERE { ?s a ?type } GROUP BY ?type");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void selectStarProjectsEverything() {
    var ann = warnings("SELECT * WHERE { ?s cim:name ?name }");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void askBodyIsAnExistenceTest() {
    var ann = warnings("ASK { ?s cim:name ?name }");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void notExistsBodyIsAnExistenceTest() {
    var ann =
        warnings(
            "SELECT ?s WHERE { ?s a cim:ACLineSegment . FILTER NOT EXISTS { ?s cim:name ?any } }");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void minusBodyIsAnExistenceTest() {
    var ann = warnings("SELECT ?s WHERE { ?s a cim:ACLineSegment MINUS { ?s cim:name ?x } }");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void variablePredicateIsNotReported() {
    // ?p cannot be replaced by a blank node — a single-use predicate variable is not flagged
    // (the dynamic-predicate notice covers that situation in schema-aware validation).
    var ann = warnings("SELECT ?s WHERE { ?s ?p <urn:x> }");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void graphNameWildcardIsNotReported() {
    var ann = warnings("SELECT ?s WHERE { GRAPH ?g { ?s a cim:ACLineSegment } }");
    assertTrue(ann.isEmpty());
  }

  @Test
  public void variableUsedTwiceInOnePatternIsAJoin() {
    var ann = warnings("SELECT ?s WHERE { ?s cim:name ?s }");
    assertTrue(ann.isEmpty());
  }

  // ---- sub-query scoping --------------------------------------------------------------------

  @Test
  public void subQueryScopeIsAnalyzedIndependently() {
    var ann =
        warnings(
            """
            SELECT ?s WHERE {
              { SELECT ?s WHERE { ?s cim:name ?x } }
            }
            """);
    // ?x is unused inside the sub-query; the sub-query's projected ?s joining the outer scope
    // only once is fine.
    assertEquals(1, ann.size());
    assertEquals(SparqlValidationCode.UNUSED_VARIABLE, ann.get(0).code());
    assertTrue(ann.get(0).message().contains("?x"));
  }

  @Test
  public void innerScopeVariableDoesNotSatisfyOuterProjection() {
    var ann =
        warnings(
            """
            SELECT ?s ?x WHERE {
              { SELECT ?s WHERE { ?s cim:name ?x . FILTER(?x != "") } }
            }
            """);
    // The inner ?x is not projected out of the sub-query, so the outer projected ?x is unbound.
    var unbound = ofCode(ann, SparqlValidationCode.PROJECTED_VARIABLE_UNBOUND);
    assertEquals(1, unbound.size());
    assertTrue(unbound.get(0).message().contains("?x"));
  }

  // ---- updates are out of scope --------------------------------------------------------------

  @Test
  public void updatesAreNotChecked() {
    var ann = warnings("DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }");
    assertTrue(ann.isEmpty());
  }

  // ---- strictness interaction -----------------------------------------------------------------

  @Test
  public void permissiveStrictnessSuppressesTheWarnings() {
    var ann = warnings("SELECT ?s ?nmae WHERE { ?s cim:name ?name . FILTER(?name != \"\") }");
    assertEquals(1, ann.size());
    assertTrue(StrictnessLevel.PERMISSIVE.apply(ann).isEmpty());
  }

  // ---- SHACL embedded SPARQL: pre-bound variables ----------------------------------------------

  private static org.apache.jena.graph.Graph turtle(String ttl) {
    var m = ModelFactory.createDefaultModel();
    RDFParser.fromString(ttl, Lang.TURTLE).parse(m);
    return m.getGraph();
  }

  @Test
  public void shaclPreboundVariablesAreExempt() {
    // $this is projected without appearing in a binding position — legal in SHACL because the
    // engine pre-binds it; must not be reported.
    var g =
        turtle(
            """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            cim:Shape a sh:NodeShape ;
              sh:targetClass cim:ACLineSegment ;
              sh:sparql [
                sh:select ""\"
                  SELECT $this WHERE {
                    FILTER NOT EXISTS { $this <http://iec.ch/TC57/CIM100#IdentifiedObject.name> ?n }
                  }
                ""\" ;
              ] .
            """);
    var result = SparqlValidationApi.checkShaclSyntaxOnly(g);
    for (var er : result.embeddedResults()) {
      assertTrue(er.result().annotations().isEmpty());
    }
  }

  @Test
  public void shaclEmbeddedQueryWithGenuinelyUnusedVariableIsReported() {
    var g =
        turtle(
            """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            cim:Shape a sh:NodeShape ;
              sh:targetClass cim:ACLineSegment ;
              sh:sparql [
                sh:select ""\"
                  SELECT $this ?value WHERE {
                    $this <http://iec.ch/TC57/CIM100#IdentifiedObject.name> ?value .
                    $this <http://iec.ch/TC57/CIM100#Equipment.aggregate> ?leftover .
                  }
                ""\" ;
              ] .
            """);
    var result = SparqlValidationApi.checkShaclSyntaxOnly(g);
    var all =
        result.embeddedResults().stream()
            .flatMap(er -> er.result().annotations().stream())
            .toList();
    assertEquals(1, all.size());
    assertEquals(SparqlValidationCode.UNUSED_VARIABLE, all.get(0).code());
    assertTrue(all.get(0).message().contains("?leftover"));
  }
}
