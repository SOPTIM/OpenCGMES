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

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/**
 * Direct unit tests for {@link SourceLocator}.
 *
 * <p>The validator-level tests (e.g. {@link SparqlValidationApiTest}) exercise the locator
 * indirectly via the {@code line}/{@code column} fields of emitted annotations; these tests cover
 * the locator algorithm in isolation, including the three lookup forms (full IRI, prefixed name,
 * {@code a} keyword) and the earliest-match selection.
 */
public class SourceLocatorTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  @Test
  public void fullIriFormIsFound() {
    String q = "SELECT * WHERE { ?s <http://iec.ch/TC57/CIM100#ACLineSegment> ?o }";
    var loc = SourceLocator.locate(q, NodeFactory.createURI(CIM + "ACLineSegment"));
    assertEquals(Integer.valueOf(1), loc.line());
    assertEquals(Integer.valueOf(21), loc.column());
  }

  @Test
  public void prefixedNameIsFoundViaPrefixMap() {
    String q = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim:ACLineSegment ?o }";
    Query parsed = QueryFactory.create(q);
    var loc =
        SourceLocator.locate(
            q, NodeFactory.createURI(CIM + "ACLineSegment"), parsed.getPrefixMapping());
    // "cim:" appears on line 2 inside the WHERE.
    assertEquals(Integer.valueOf(2), loc.line());
    // 1-based column of the 'c' in "cim:"
    assertTrue("expected column at start of cim:..., got " + loc.column(), loc.column() > 0);
  }

  @Test
  public void aKeywordResolvesToRdfType() {
    String q = "SELECT * WHERE { ?s a ?cls }";
    var loc = SourceLocator.locate(q, RDF.type.asNode(), PrefixMapping.Factory.create());
    assertEquals(Integer.valueOf(1), loc.line());
    // 'a' is at "{ ?s a ?cls }" — find its column manually.
    int idx = q.indexOf("a ?cls");
    assertEquals(Integer.valueOf(idx + 1), loc.column());
  }

  @Test
  public void aKeywordNotMatchedInsidePrefixedLocalName() {
    // The local name "ab" must NOT be picked up as the 'a' keyword.
    String q = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim:abc ?o }";
    Query parsed = QueryFactory.create(q);
    var loc = SourceLocator.locate(q, RDF.type.asNode(), parsed.getPrefixMapping());
    // No 'a' keyword in predicate position; locator should return UNKNOWN.
    assertNull("no rdf:type usage in query, but locator returned " + loc, loc.line());
    assertNull(loc.column());
  }

  @Test
  public void earliestOccurrenceWins() {
    // Term written first as full IRI then as prefixed name — locator returns the earlier one.
    String q =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE {\n"
            + "  ?s <"
            + CIM
            + "ACLineSegment.r> ?r .\n"
            + "  ?s cim:ACLineSegment.r ?r2 .\n"
            + "}";
    Query parsed = QueryFactory.create(q);
    var loc =
        SourceLocator.locate(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping());
    assertEquals(
        "earliest occurrence is on line 3 (the full IRI form)", Integer.valueOf(3), loc.line());
  }

  @Test
  public void prefixDeclaredButFullIriUsedStillResolves() {
    // PrefixMapping declares cim:, but the query body uses the full IRI form.
    String q = "PREFIX cim: <" + CIM + ">\n" + "SELECT * WHERE { ?s <" + CIM + "Foo> ?o }";
    Query parsed = QueryFactory.create(q);
    var loc =
        SourceLocator.locate(q, NodeFactory.createURI(CIM + "Foo"), parsed.getPrefixMapping());
    assertNotNull(loc.line());
    assertEquals(Integer.valueOf(2), loc.line());
  }

  @Test
  public void prefixedNameIsNotMatchedAsSubstringOfLongerName() {
    // 'cim:ACLineSegment.r' must NOT match inside 'cim:ACLineSegment.resistance'.
    String q =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE {\n"
            + "  ?a cim:ACLineSegment.resistance ?x .\n"
            + "  ?b cim:ACLineSegment.r ?y .\n"
            + "}";
    Query parsed = QueryFactory.create(q);
    var loc =
        SourceLocator.locate(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping());
    assertEquals(
        "must skip the substring hit on line 3 and find the real token on line 4",
        Integer.valueOf(4),
        loc.line());
  }

  @Test
  public void unknownTermReturnsUnknown() {
    String q = "SELECT * WHERE { ?s ?p ?o }";
    var loc = SourceLocator.locate(q, NodeFactory.createURI("http://example.org/Missing"));
    assertNull(loc.line());
    assertNull(loc.column());
  }

  // ---- Turtle source (used by the LSP for SHACL structural annotations) -------------------

  @Test
  public void locatesTermInTurtleSourceViaPrefixedName() {
    // Simulates the LSP looking up a sh:targetClass value in a .ttl file.
    String turtle =
        "@prefix sh:  <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix cim: <"
            + CIM
            + "> .\n"
            + "\n"
            + "ex:BadShape sh:targetClass cim:DoesNotExist .\n";
    PrefixMapping pm = PrefixMapping.Factory.create();
    pm.setNsPrefix("cim", CIM);
    pm.setNsPrefix("sh", "http://www.w3.org/ns/shacl#");
    var loc = SourceLocator.locate(turtle, NodeFactory.createURI(CIM + "DoesNotExist"), pm);
    assertEquals("cim:DoesNotExist is on the 4th line", Integer.valueOf(4), loc.line());
    // Column should point at the 'c' in 'cim:DoesNotExist', not line 1.
    assertNotNull("column must be resolved", loc.column());
    assertTrue("column must be > 1 (not at line start)", loc.column() > 1);
  }

  @Test
  public void locatesTermInTurtleSourceViaFullIri() {
    // Full-IRI form in Turtle is also found correctly.
    String turtle =
        "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
            + "ex:Shape sh:targetClass <"
            + CIM
            + "ACLineSegment> .\n";
    var loc = SourceLocator.locate(turtle, NodeFactory.createURI(CIM + "ACLineSegment"));
    assertEquals(Integer.valueOf(2), loc.line());
    // Column points to the '<' of the full IRI.
    assertNotNull(loc.column());
    String line2 = "ex:Shape sh:targetClass <" + CIM + "ACLineSegment> .";
    int expected = line2.indexOf('<') + 1; // 1-based
    assertEquals(Integer.valueOf(expected), loc.column());
  }

  @Test
  public void turtleCommentIsNotMatchedAsTerm() {
    // Term appears only inside a comment on line 1; the real occurrence is on line 2.
    String turtle =
        "# cim:ACLineSegment is referenced below\n"
            + "ex:Shape sh:targetClass <"
            + CIM
            + "ACLineSegment> .\n";
    var loc = SourceLocator.locate(turtle, NodeFactory.createURI(CIM + "ACLineSegment"));
    assertEquals(
        "full-IRI occurrence must be on line 2, not in comment on line 1",
        Integer.valueOf(2),
        loc.line());
  }

  @Test
  public void nullInputsAreSafe() {
    assertEquals(
        SourceLocator.UNKNOWN,
        SourceLocator.locate(null, RDF.type.asNode(), PrefixMapping.Factory.create()));
    assertEquals(
        SourceLocator.UNKNOWN,
        SourceLocator.locate("SELECT * WHERE { }", null, PrefixMapping.Factory.create()));
  }

  @Test
  public void blankNodeAndVariableNodesReturnUnknown() {
    var blank = NodeFactory.createBlankNode();
    var v = NodeFactory.createVariable("x");
    assertEquals(SourceLocator.UNKNOWN, SourceLocator.locate("SELECT *", blank));
    assertEquals(SourceLocator.UNKNOWN, SourceLocator.locate("SELECT *", v));
  }

  // ---- locateWithHint — variable subject disambiguates duplicate property occurrences ------

  @Test
  public void locateWithHint_variableHint_picksNearestOccurrence() {
    // ?badSub is on line 4, near the second cim:r — that's the one the error is about.
    String q =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE {\n"
            + "  ?goodSub cim:ACLineSegment.r ?r1 .\n"
            + "  ?badSub  cim:ACLineSegment.r ?r2 .\n"
            + "}";
    Query parsed = QueryFactory.create(q);
    var hint = NodeFactory.createVariable("badSub");

    var loc =
        SourceLocator.locateWithHint(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping(), hint);

    assertEquals("should point to line 4 (closest to ?badSub)", Integer.valueOf(4), loc.line());
  }

  @Test
  public void locateWithHint_variableHint_firstOccurrenceWhenHintIsNearest() {
    // ?s is closer to the FIRST cim:r on line 3.
    String q =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE {\n"
            + "  ?s cim:ACLineSegment.r ?r1 .\n"
            + "  ?other cim:ACLineSegment.r ?r2 .\n"
            + "}";
    Query parsed = QueryFactory.create(q);
    var hint = NodeFactory.createVariable("s");

    var loc =
        SourceLocator.locateWithHint(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping(), hint);

    assertEquals("should point to line 3 (closest to ?s)", Integer.valueOf(3), loc.line());
  }

  @Test
  public void locateWithHint_nullHint_fallsBackToFirstOccurrence() {
    String q =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE {\n"
            + "  ?s1 cim:ACLineSegment.r ?r1 .\n"
            + "  ?s2 cim:ACLineSegment.r ?r2 .\n"
            + "}";
    Query parsed = QueryFactory.create(q);

    var loc =
        SourceLocator.locateWithHint(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping(), null);

    assertEquals("null hint → first occurrence on line 3", Integer.valueOf(3), loc.line());
  }

  @Test
  public void locateWithHint_singleOccurrence_returnsThatOccurrence() {
    String q = "PREFIX cim: <" + CIM + ">\n" + "SELECT * WHERE { ?s cim:ACLineSegment.r ?r }";
    Query parsed = QueryFactory.create(q);
    var hint = NodeFactory.createVariable("s");

    var loc =
        SourceLocator.locateWithHint(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping(), hint);

    assertEquals(Integer.valueOf(2), loc.line());
  }

  @Test
  public void locateWithHint_hintNotFoundInText_fallsBackToFirstOccurrence() {
    // Hint variable ?ghost never appears in the query text.
    String q =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE {\n"
            + "  ?s1 cim:ACLineSegment.r ?r1 .\n"
            + "  ?s2 cim:ACLineSegment.r ?r2 .\n"
            + "}";
    Query parsed = QueryFactory.create(q);
    var hint = NodeFactory.createVariable("ghost");

    var loc =
        SourceLocator.locateWithHint(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping(), hint);

    assertEquals(
        "hint not found → fall back to first occurrence on line 3", Integer.valueOf(3), loc.line());
  }

  @Test
  public void locateWithHint_uriHint_picksNearestOccurrence() {
    // Hint is a URI constant, not a variable.
    String iriSub2 = CIM + "Substation.S2";
    String q =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE {\n"
            + "  <"
            + CIM
            + "Substation.S1> cim:ACLineSegment.r ?r1 .\n"
            + "  <"
            + iriSub2
            + "> cim:ACLineSegment.r ?r2 .\n"
            + "}";
    Query parsed = QueryFactory.create(q);
    var hint = NodeFactory.createURI(iriSub2);

    var loc =
        SourceLocator.locateWithHint(
            q, NodeFactory.createURI(CIM + "ACLineSegment.r"), parsed.getPrefixMapping(), hint);

    assertEquals("URI hint on line 4 → picks predicate on line 4", Integer.valueOf(4), loc.line());
  }
}
