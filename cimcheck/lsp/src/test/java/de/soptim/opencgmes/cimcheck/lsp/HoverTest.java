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

package de.soptim.opencgmes.cimcheck.lsp;

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.core.schema.SchemaIndex;
import java.util.List;
import java.util.Optional;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the hover-documentation feature: term extraction at cursor position and rdfs:label
 * / rdfs:comment retrieval via {@link SchemaIndex}.
 */
public class HoverTest {

  private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String PROFILE = "http://example.org/profile/EQ/1.0";

  private static final String PROP_R = CIM + "ACLineSegment.r";
  private static final String PROP_CONTAINER = CIM + "Equipment.EquipmentContainer";
  private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
  private static final String CLASS_SUB = CIM + "Substation";

  private PrefixMapping pm;

  @Before
  public void setUp() {
    pm = PrefixMapping.Factory.create();
    pm.setNsPrefix("cim", CIM);
    pm.setNsPrefix("xsd", XSD);
    pm.setNsPrefix("rdf", RDF.getURI());
  }

  // ============================================================================================
  // termAtPosition — full IRI form
  // ============================================================================================

  @Test
  public void fullIri_cursorOnAngle_resolves() {
    String src = "SELECT * WHERE { ?s <" + CIM + "ACLineSegment.r> ?o }";
    int col = src.indexOf('<');
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNotNull(t);
    assertEquals(PROP_R, t.getURI());
  }

  @Test
  public void fullIri_cursorInsideIri_resolves() {
    String src = "SELECT * WHERE { ?s <" + CIM + "ACLineSegment.r> ?o }";
    int col = src.indexOf(CIM) + 5; // somewhere in the middle of the IRI
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNotNull(t);
    assertEquals(PROP_R, t.getURI());
  }

  @Test
  public void fullIri_cursorOnClosingAngle_resolves() {
    String src = "{ ?s <" + CIM + "ACLineSegment.r> ?o }";
    int col = src.indexOf('>');
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNotNull(t);
    assertEquals(PROP_R, t.getURI());
  }

  // ============================================================================================
  // termAtPosition — prefixed name form
  // ============================================================================================

  @Test
  public void prefixedName_cursorOnPrefix_resolves() {
    String src = "SELECT * WHERE { ?s cim:ACLineSegment.r ?o }";
    int col = src.indexOf("cim:");
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNotNull(t);
    assertEquals(PROP_R, t.getURI());
  }

  @Test
  public void prefixedName_cursorInMiddle_resolves() {
    String src = "SELECT * WHERE { ?s cim:ACLineSegment.r ?o }";
    int col = src.indexOf("ACLine") + 3;
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNotNull(t);
    assertEquals(PROP_R, t.getURI());
  }

  @Test
  public void prefixedName_cursorOnLastChar_resolves() {
    String src = "{ ?s cim:ACLineSegment.r ?o }";
    int col = src.indexOf(".r") + 1;
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNotNull(t);
    assertEquals(PROP_R, t.getURI());
  }

  @Test
  public void prefixedName_unknownPrefix_returnsNull() {
    String src = "{ ?s unknown:prop ?o }";
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, src.indexOf("unknown"), pm);
    assertNull(t);
  }

  // ============================================================================================
  // termAtPosition — 'a' keyword
  // ============================================================================================

  @Test
  public void aKeyword_returnsRdfType() {
    String src = "SELECT * WHERE { ?s a cim:ACLineSegment }";
    int col = src.indexOf(" a ") + 1;
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNotNull(t);
    assertEquals(RDF.type.getURI(), t.getURI());
  }

  // ============================================================================================
  // termAtPosition — comment and edge cases
  // ============================================================================================

  @Test
  public void positionInComment_returnsNull() {
    String src = "# cim:ACLineSegment.r is here";
    int col = src.indexOf("cim:");
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNull("terms inside # comments must not be resolved", t);
  }

  @Test
  public void positionOnWhitespace_returnsNull() {
    String src = "{ ?s  cim:ACLineSegment.r ?o }";
    // double space — cursor on the second space
    int col = src.indexOf("?s") + 2;
    Node t = SparqlTextDocumentService.termAtPosition(src, 0, col, pm);
    assertNull(t);
  }

  @Test
  public void nullText_returnsNull() {
    assertNull(SparqlTextDocumentService.termAtPosition(null, 0, 0, pm));
  }

  @Test
  public void lineOutOfRange_returnsNull() {
    assertNull(SparqlTextDocumentService.termAtPosition("SELECT *", 99, 0, pm));
  }

  @Test
  public void multiLineText_correctLineResolved() {
    String src = "PREFIX cim: <" + CIM + ">\n" + "SELECT * WHERE { ?s cim:ACLineSegment.r ?o }";
    // Line 1 (0-based), token starts at col of "cim:"
    int col = src.split("\n")[1].indexOf("cim:");
    Node t = SparqlTextDocumentService.termAtPosition(src, 1, col, pm);
    assertNotNull(t);
    assertEquals(PROP_R, t.getURI());
  }

  // ============================================================================================
  // SchemaIndex.labelOf / commentOf — populated from rdfs:label / rdfs:comment in TTL graph
  // ============================================================================================

  @Test
  public void commentOf_returnsRdfsComment() {
    String ttl =
        "@prefix cim: <"
            + CIM
            + "> .\n"
            + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
            + "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n"
            + "<"
            + PROP_R
            + "> a owl:DatatypeProperty ;\n"
            + "    rdfs:comment \"Resistance of the line.\" .\n";

    SchemaIndex index = indexFromTurtle(ttl);
    List<VersionIri> scope = index.findProperty(NodeFactory.createURI(PROP_R));
    assertFalse("property must be found", scope.isEmpty());

    Optional<String> comment = index.commentOf(NodeFactory.createURI(PROP_R), scope);
    assertTrue("rdfs:comment must be present", comment.isPresent());
    assertEquals("Resistance of the line.", comment.get());
  }

  @Test
  public void labelOf_returnsRdfsLabel() {
    String ttl =
        "@prefix cim: <"
            + CIM
            + "> .\n"
            + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
            + "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n"
            + "<"
            + CLASS_AC_LINE
            + "> a owl:Class ;\n"
            + "    rdfs:label \"ACLineSegment\" ;\n"
            + "    rdfs:comment \"A wire...\" .\n";

    SchemaIndex index = indexFromTurtle(ttl);
    List<VersionIri> scope = index.findClass(NodeFactory.createURI(CLASS_AC_LINE));
    assertFalse(scope.isEmpty());

    Optional<String> label = index.labelOf(NodeFactory.createURI(CLASS_AC_LINE), scope);
    assertTrue(label.isPresent());
    assertEquals("ACLineSegment", label.get());
  }

  @Test
  public void commentOf_noCommentInSchema_returnsEmpty() {
    String ttl =
        "@prefix cim: <"
            + CIM
            + "> .\n"
            + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + "<"
            + CLASS_AC_LINE
            + "> a owl:Class .\n";

    SchemaIndex index = indexFromTurtle(ttl);
    List<VersionIri> scope = index.findClass(NodeFactory.createURI(CLASS_AC_LINE));
    Optional<String> comment = index.commentOf(NodeFactory.createURI(CLASS_AC_LINE), scope);
    assertFalse("no rdfs:comment in schema → empty", comment.isPresent());
  }

  @Test
  public void commentOf_termNotInSchema_returnsEmpty() {
    SchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(PROFILE, List.of(CLASS_AC_LINE), List.of(PROP_R))
            .build();
    // Term not in schema at all
    Optional<String> comment =
        index.commentOf(NodeFactory.createURI(CIM + "NoSuch"), index.getAllProfiles());
    assertFalse(comment.isPresent());
  }

  // ============================================================================================
  // Helper
  // ============================================================================================

  private static SchemaIndex indexFromTurtle(String ttl) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(ttl, Lang.TURTLE).parse(g);
    var v = VersionIri.of(PROFILE);
    var schema = RdfsSchemaIndex.indexGraph(v, g);
    return RdfsSchemaIndex.builder().addProfile(schema).build();
  }
}
