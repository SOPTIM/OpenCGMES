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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.schema.SchemaIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link DefinitionIndex}: fragment scanning, declaration priority, location lookup,
 * and workspace symbol search.
 */
public class DefinitionIndexTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String PROFILE_EQ = "http://example.org/profile/EQ/1.0";
  private static final String PROFILE_TP = "http://example.org/profile/TP/1.0";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  // ============================================================================================
  // scanFragments — RDF/XML format
  // ============================================================================================

  @Test
  public void scanFragments_rdfXmlAbout_declarationLine() throws IOException {
    String content =
        "<?xml version=\"1.0\"?>\n"
            + "<rdf:RDF>\n"
            + "  <rdfs:Class rdf:about=\"#ACLineSegment\">\n"
            + "    <rdfs:subClassOf rdf:resource=\"#Conductor\"/>\n"
            + "  </rdfs:Class>\n"
            + "</rdf:RDF>\n";
    Path file = writeTmp("eq.rdf", content);

    Map<String, Integer> frags = DefinitionIndex.scanFragments(file);

    // Declaration line is line 2 (0-based)
    assertEquals(Integer.valueOf(2), frags.get("ACLineSegment"));
    // Reference (subClassOf) is line 3 — declaration wins for ACLineSegment, so Conductor
    // is first seen on line 3 as a reference
    assertNotNull("Conductor reference should be indexed", frags.get("Conductor"));
  }

  @Test
  public void scanFragments_declarationBeatsEarlierReference() throws IOException {
    // A file where rdf:resource="#Foo" appears BEFORE rdf:about="#Foo".
    String content =
        "<?xml version=\"1.0\"?>\n"
            + "<rdf:RDF>\n"
            + "  <rdfs:Class rdf:about=\"#Bar\">\n"
            + "    <rdfs:subClassOf rdf:resource=\"#Foo\"/>\n" // line 3: reference
            + "  </rdfs:Class>\n"
            + "  <rdfs:Class rdf:about=\"#Foo\">\n" // line 5: declaration
            + "  </rdfs:Class>\n"
            + "</rdf:RDF>\n";
    Path file = writeTmp("schema.rdf", content);

    Map<String, Integer> frags = DefinitionIndex.scanFragments(file);

    // Declaration on line 5 should override the earlier reference on line 3
    assertEquals(
        "rdf:about declaration must win over earlier rdf:resource reference",
        Integer.valueOf(5),
        frags.get("Foo"));
  }

  @Test
  public void scanFragments_absoluteIriInAbout() throws IOException {
    String content = "  <rdfs:Class rdf:about=\"http://iec.ch/TC57/CIM100#ACLineSegment\">\n";
    Path file = writeTmp("eq.rdf", content);

    Map<String, Integer> frags = DefinitionIndex.scanFragments(file);
    assertEquals(Integer.valueOf(0), frags.get("ACLineSegment"));
  }

  // ============================================================================================
  // scanFragments — Turtle format
  // ============================================================================================

  @Test
  public void scanFragments_turtleFullIri() throws IOException {
    String content =
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + "<http://iec.ch/TC57/CIM100#ACLineSegment> a owl:Class .\n";
    Path file = writeTmp("eq.ttl", content);

    Map<String, Integer> frags = DefinitionIndex.scanFragments(file);
    assertEquals(Integer.valueOf(2), frags.get("ACLineSegment"));
  }

  // ============================================================================================
  // DefinitionIndex.build — end-to-end with a real source file
  // ============================================================================================

  @Test
  public void build_findsClassLocation() throws IOException {
    String rdfXml =
        "<?xml version=\"1.0\"?>\n"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
            + "         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n"
            + "         xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
            + "  <rdfs:Class rdf:about=\""
            + CIM
            + "ACLineSegment\">\n"
            + "  </rdfs:Class>\n"
            + "  <rdf:Property rdf:about=\""
            + CIM
            + "ACLineSegment.r\">\n"
            + "    <rdfs:domain rdf:resource=\""
            + CIM
            + "ACLineSegment\"/>\n"
            + "  </rdf:Property>\n"
            + "</rdf:RDF>\n";
    Path file = writeTmp("eq.rdf", rdfXml);

    SchemaIndex index = indexFromRdfXml(rdfXml, PROFILE_EQ);
    VersionIri profileIri = VersionIri.of(PROFILE_EQ);
    DefinitionIndex defIndex = DefinitionIndex.build(index, Map.of(profileIri, file));

    Node cls = NodeFactory.createURI(CIM + "ACLineSegment");
    Node prop = NodeFactory.createURI(CIM + "ACLineSegment.r");

    Optional<Location> clsLoc = defIndex.locationOf(cls);
    Optional<Location> propLoc = defIndex.locationOf(prop);

    assertTrue("Class location must be found", clsLoc.isPresent());
    assertTrue("Property location must be found", propLoc.isPresent());

    // Class declared on line 4 (0-based); property on line 6
    assertEquals(4, clsLoc.get().getRange().getStart().getLine());
    assertEquals(6, propLoc.get().getRange().getStart().getLine());

    // Both point to the same source file
    assertTrue(clsLoc.get().getUri().endsWith("eq.rdf"));
    assertTrue(propLoc.get().getUri().endsWith("eq.rdf"));
  }

  @Test
  public void build_noSourcePaths_emptyIndex() {
    SchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                PROFILE_EQ, List.of(CIM + "ACLineSegment"), List.of(CIM + "ACLineSegment.r"))
            .build();
    DefinitionIndex defIndex = DefinitionIndex.build(index, Map.of());

    assertFalse(defIndex.locationOf(NodeFactory.createURI(CIM + "ACLineSegment")).isPresent());
  }

  @Test
  public void build_multiProfile_usesFirstMatchingFile() throws IOException {
    String eqContent =
        "  <rdfs:Class rdf:about=\""
            + CIM
            + "ACLineSegment\">\n" // line 0
            + "  <rdfs:Class rdf:about=\""
            + CIM
            + "Substation\">\n"; // line 1
    String tpContent = "  <rdfs:Class rdf:about=\"" + CIM + "TopologicalNode\">\n";
    Path eqFile = writeTmp("eq.rdf", eqContent);
    Path tpFile = writeTmp("tp.rdf", tpContent);

    SchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(PROFILE_EQ, List.of(CIM + "ACLineSegment", CIM + "Substation"), List.of())
            .addProfile(PROFILE_TP, List.of(CIM + "TopologicalNode"), List.of())
            .build();

    DefinitionIndex defIndex =
        DefinitionIndex.build(
            index,
            Map.of(
                VersionIri.of(PROFILE_EQ), eqFile,
                VersionIri.of(PROFILE_TP), tpFile));

    Optional<Location> aclLoc = defIndex.locationOf(NodeFactory.createURI(CIM + "ACLineSegment"));
    Optional<Location> tnLoc = defIndex.locationOf(NodeFactory.createURI(CIM + "TopologicalNode"));

    assertTrue(aclLoc.isPresent());
    assertTrue(aclLoc.get().getUri().endsWith("eq.rdf"));

    assertTrue(tnLoc.isPresent());
    assertTrue(tnLoc.get().getUri().endsWith("tp.rdf"));
  }

  // ============================================================================================
  // findSymbols
  // ============================================================================================

  @Test
  public void findSymbols_emptyQuery_returnsAll() throws IOException {
    Path file =
        writeTmp(
            "eq.rdf",
            "  <rdfs:Class rdf:about=\""
                + CIM
                + "ACLineSegment\">\n"
                + "  <rdf:Property rdf:about=\""
                + CIM
                + "ACLineSegment.r\">\n");

    SchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                PROFILE_EQ, List.of(CIM + "ACLineSegment"), List.of(CIM + "ACLineSegment.r"))
            .build();
    DefinitionIndex defIndex =
        DefinitionIndex.build(index, Map.of(VersionIri.of(PROFILE_EQ), file));

    List<WorkspaceSymbol> symbols = defIndex.findSymbols("", index);
    assertEquals(2, symbols.size());
  }

  @Test
  public void findSymbols_queryFilters_caseInsensitive() throws IOException {
    Path file =
        writeTmp(
            "eq.rdf",
            "  <rdfs:Class rdf:about=\""
                + CIM
                + "ACLineSegment\">\n"
                + "  <rdfs:Class rdf:about=\""
                + CIM
                + "Substation\">\n");

    SchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(PROFILE_EQ, List.of(CIM + "ACLineSegment", CIM + "Substation"), List.of())
            .build();
    DefinitionIndex defIndex =
        DefinitionIndex.build(index, Map.of(VersionIri.of(PROFILE_EQ), file));

    List<WorkspaceSymbol> symbols = defIndex.findSymbols("aclineseg", index);
    assertEquals(1, symbols.size());
    assertEquals("ACLineSegment", symbols.get(0).getName());
    assertEquals(SymbolKind.Class, symbols.get(0).getKind());
  }

  @Test
  public void findSymbols_sortedAlphabetically() throws IOException {
    Path file =
        writeTmp(
            "eq.rdf",
            "  <rdfs:Class rdf:about=\""
                + CIM
                + "Zebra\">\n"
                + "  <rdfs:Class rdf:about=\""
                + CIM
                + "Apple\">\n"
                + "  <rdfs:Class rdf:about=\""
                + CIM
                + "Mango\">\n");

    SchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(PROFILE_EQ, List.of(CIM + "Zebra", CIM + "Apple", CIM + "Mango"), List.of())
            .build();
    DefinitionIndex defIndex =
        DefinitionIndex.build(index, Map.of(VersionIri.of(PROFILE_EQ), file));

    List<WorkspaceSymbol> symbols = defIndex.findSymbols("", index);
    List<String> names = symbols.stream().map(WorkspaceSymbol::getName).toList();
    assertEquals(List.of("Apple", "Mango", "Zebra"), names);
  }

  // ============================================================================================
  // Helpers
  // ============================================================================================

  private Path writeTmp(String name, String content) throws IOException {
    Path file = tmp.newFile(name).toPath();
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static SchemaIndex indexFromRdfXml(String content, String profileIri) {
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(content, Lang.RDFXML).parse(g);
    var v = VersionIri.of(profileIri);
    return RdfsSchemaIndex.builder().addProfile(RdfsSchemaIndex.indexGraph(v, g)).build();
  }
}
