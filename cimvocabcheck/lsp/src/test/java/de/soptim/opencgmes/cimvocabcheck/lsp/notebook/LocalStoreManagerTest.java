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

package de.soptim.opencgmes.cimvocabcheck.lsp.notebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exercises {@link LocalStoreManager}: format dispatch (Turtle/TriG via Jena, {@code .xml} via the
 * CIMXML parser), the union semantics documented on the class, and the cache behavior (reuse,
 * mtime/size invalidation, LRU eviction, failed parses not cached).
 */
public class LocalStoreManagerTest {

  private static final String EX = "http://example.org/";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final LocalStoreManager manager = new LocalStoreManager();

  // ---- parsing and union semantics ------------------------------------------------------------

  @Test
  public void parsesTurtleIntoTheDefaultGraph() throws Exception {
    Path file = turtleFile("data.ttl", "<" + EX + "s> <" + EX + "p> <" + EX + "o> .");

    DatasetGraph union = manager.unionFor(List.of(file));

    assertTrue(union.getDefaultGraph().contains(uri(EX + "s"), uri(EX + "p"), uri(EX + "o")));
  }

  @Test
  public void unionOfTwoFilesSeesTriplesFromBoth() throws Exception {
    Path a = turtleFile("a.ttl", "<" + EX + "a> <" + EX + "p> 1 .");
    Path b = turtleFile("b.ttl", "<" + EX + "b> <" + EX + "p> 2 .");

    Graph defaultGraph = manager.unionFor(List.of(a, b)).getDefaultGraph();

    assertTrue(defaultGraph.contains(uri(EX + "a"), uri(EX + "p"), null));
    assertTrue(defaultGraph.contains(uri(EX + "b"), uri(EX + "p"), null));
  }

  @Test
  public void namedGraphsStayAddressableAndJoinTheDefaultUnion() throws Exception {
    Path file = write("data.trig", "<" + EX + "g> { <" + EX + "s> <" + EX + "p> <" + EX + "o> . }");

    DatasetGraph union = manager.unionFor(List.of(file));

    assertTrue("GRAPH lookup must still work", union.containsGraph(uri(EX + "g")));
    assertTrue(union.getGraph(uri(EX + "g")).contains(uri(EX + "s"), uri(EX + "p"), uri(EX + "o")));
    assertTrue(
        "a bare triple pattern must see named-graph data",
        union.getDefaultGraph().contains(uri(EX + "s"), uri(EX + "p"), uri(EX + "o")));
  }

  @Test
  public void parsesCimxmlModelWithBodyAndHeaderVisibleInTheUnion() throws Exception {
    Path file = write("model.xml", CIMXML_FULL_MODEL);

    Graph defaultGraph = manager.unionFor(List.of(file)).getDefaultGraph();

    assertTrue(
        "model body must be queryable",
        defaultGraph.contains(
            uri("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
            uri("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
            null));
    assertTrue(
        "the FullModel header (a named graph in the parsed dataset) must join the union",
        defaultGraph.contains(
            uri("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
            uri("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),
            null));
  }

  // ---- failure modes ---------------------------------------------------------------------------

  @Test
  public void missingFileReportsFileNotFound() {
    Path missing = tmp.getRoot().toPath().resolve("nope.ttl");

    LocalStoreManager.StoreException e = expectStoreException(List.of(missing));

    assertEquals(ErrorCode.FILE_NOT_FOUND, e.code());
    assertTrue(e.getMessage().contains("nope.ttl"));
  }

  @Test
  public void directoryReportsFileNotFound() throws IOException {
    Path dir = tmp.newFolder("adir").toPath();

    assertEquals(ErrorCode.FILE_NOT_FOUND, expectStoreException(List.of(dir)).code());
  }

  @Test
  public void unparseableFileReportsFileParseErrorAndIsRetriedAfterAFix() throws Exception {
    Path file = turtleFile("broken.ttl", "this is not turtle @@@");

    LocalStoreManager.StoreException e = expectStoreException(List.of(file));
    assertEquals(ErrorCode.FILE_PARSE_ERROR, e.code());
    assertTrue("message should name the file", e.getMessage().contains("broken.ttl"));

    Files.writeString(file, "<" + EX + "s> <" + EX + "p> <" + EX + "o> .");
    assertTrue(
        "a failed parse must not be cached",
        manager
            .unionFor(List.of(file))
            .getDefaultGraph()
            .contains(uri(EX + "s"), uri(EX + "p"), uri(EX + "o")));
  }

  // ---- caching ---------------------------------------------------------------------------------

  // Cache reuse vs re-parse is observed through the identity of the per-file DatasetGraph
  // (datasetGraphFor is package-private for exactly this): a cache hit returns the identical
  // instance, a re-parse a fresh one. (Instance identity of getDefaultGraph() would not work —
  // in-memory datasets hand out a new GraphView wrapper on every call.)

  @Test
  public void unchangedFileIsServedFromTheCache() throws Exception {
    Path file = turtleFile("data.ttl", "<" + EX + "s> <" + EX + "p> 1 .");

    assertSame(manager.datasetGraphFor(file), manager.datasetGraphFor(file));
  }

  @Test
  public void changedMtimeInvalidatesTheCachedParse() throws Exception {
    Path file = turtleFile("data.ttl", "<" + EX + "s> <" + EX + "p> 1 .");
    DatasetGraph before = manager.datasetGraphFor(file);

    // Same byte length, different content: only the modification time reveals the change.
    Files.writeString(file, "<" + EX + "s> <" + EX + "p> 2 .");
    FileTime bumped = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2_000);
    Files.setLastModifiedTime(file, bumped);

    DatasetGraph after = manager.datasetGraphFor(file);
    assertNotSame(before, after);
    assertTrue(
        "the re-parse must expose the new content",
        after.getDefaultGraph().contains(uri(EX + "s"), uri(EX + "p"), integerLiteral("2")));
  }

  @Test
  public void changedSizeInvalidatesTheCachedParse() throws Exception {
    Path file = turtleFile("data.ttl", "<" + EX + "s> <" + EX + "p> 1 .");
    FileTime originalMtime = Files.getLastModifiedTime(file);
    DatasetGraph before = manager.datasetGraphFor(file);

    Files.writeString(file, "<" + EX + "s> <" + EX + "p> 1 . <" + EX + "s2> <" + EX + "p> 2 .");
    // Pin the mtime back to the cached one so only the size reveals the change.
    Files.setLastModifiedTime(file, originalMtime);

    assertNotSame(before, manager.datasetGraphFor(file));
  }

  @Test
  public void leastRecentlyUsedEntryIsEvictedBeyondCapacity() throws Exception {
    LocalStoreManager small = new LocalStoreManager(1);
    Path a = turtleFile("a.ttl", "<" + EX + "a> <" + EX + "p> 1 .");
    Path b = turtleFile("b.ttl", "<" + EX + "b> <" + EX + "p> 2 .");

    DatasetGraph firstParse = small.datasetGraphFor(a);
    assertSame("sanity: a cache hit while still resident", firstParse, small.datasetGraphFor(a));
    small.datasetGraphFor(b); // capacity 1: evicts a
    assertNotSame("evicted file must be re-parsed", firstParse, small.datasetGraphFor(a));
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** From the CIMXML FullModel structure exercised in the cimxml module's own tests. */
  private static final String CIMXML_FULL_MODEL =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
       <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
         <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
       </md:FullModel>
       <cim:MyEquipment rdf:ID="_f67fc354-9e39-4191-a456-67537399bc48">
         <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
       </cim:MyEquipment>
      </rdf:RDF>
      """;

  private Path turtleFile(String name, String turtle) throws IOException {
    return write(name, turtle);
  }

  private Path write(String name, String content) throws IOException {
    Path file = tmp.getRoot().toPath().resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private LocalStoreManager.StoreException expectStoreException(List<Path> files) {
    try {
      manager.unionFor(files);
      fail("expected a StoreException for " + files);
      throw new AssertionError("unreachable");
    } catch (LocalStoreManager.StoreException e) {
      return e;
    }
  }

  private static org.apache.jena.graph.Node uri(String uri) {
    return NodeFactory.createURI(uri);
  }

  private static org.apache.jena.graph.Node integerLiteral(String lexicalForm) {
    return NodeFactory.createLiteralDT(lexicalForm, XSDDatatype.XSDinteger);
  }
}
