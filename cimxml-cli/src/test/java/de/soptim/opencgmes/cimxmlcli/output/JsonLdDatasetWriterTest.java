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

package de.soptim.opencgmes.cimxmlcli.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimxmlcli.TestData;
import de.soptim.opencgmes.cimxmlcli.dataset.DatasetAssembler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JsonLdDatasetWriterTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private DatasetGraph dataset;
  private Path out;

  @Before
  public void setUp() throws IOException {
    final var context =
        TestData.miniContext(tmp.newFolder("rdfs").toPath(), tmp.newFolder("models").toPath());
    dataset = new DatasetAssembler().assemble(context);
    out = tmp.newFolder("out").toPath();
  }

  private void assertRoundtrip(String fileName, boolean compact) throws IOException {
    final var expected = TestData.graphSizes(dataset);
    final var target = OutputTarget.of(out.resolve(fileName));

    final var bytes = new JsonLdDatasetWriter(compact).write(dataset, target);

    assertEquals(Files.size(target.path()), bytes);
    final var read = RDFDataMgr.loadDatasetGraph(target.path().toString());
    assertEquals(expected, TestData.graphSizes(read));
    assertTrue(read.getDefaultGraph().isEmpty());
  }

  @Test
  public void roundtripsExpandedJsonLd() throws IOException {
    assertRoundtrip("igm.jsonld", false);
    final var json = Files.readString(out.resolve("igm.jsonld"), StandardCharsets.UTF_8);
    assertFalse("expanded form has no @context", json.contains("@context"));
  }

  @Test
  public void roundtripsCompactedJsonLd() throws IOException {
    assertRoundtrip("igm.jsonld", true);
    final var json = Files.readString(out.resolve("igm.jsonld"), StandardCharsets.UTF_8);
    assertTrue(json.contains("@context"));
  }

  @Test
  public void roundtripsGzippedJsonLd() throws IOException {
    assertRoundtrip("igm.jsonld.gz", false);
    final var head = Files.readAllBytes(out.resolve("igm.jsonld.gz"));
    assertEquals((byte) 0x1f, head[0]);
    assertEquals((byte) 0x8b, head[1]);
  }

  @Test
  public void replacesAnExistingFile() throws IOException {
    Files.writeString(out.resolve("igm.jsonld"), "old");
    assertRoundtrip("igm.jsonld", false);
  }

  @Test
  public void leavesNoFileBehindOnFailure() throws IOException {
    // Triple terms cannot be written as JSON-LD
    final var broken = DatasetGraphFactory.create();
    final var node = NodeFactory.createURI("urn:x");
    broken.add(node, node, node, NodeFactory.createTripleTerm(node, node, node));
    Files.writeString(out.resolve("igm.jsonld"), "old");

    assertThrows(
        RuntimeException.class,
        () ->
            new JsonLdDatasetWriter(false)
                .write(broken, OutputTarget.of(out.resolve("igm.jsonld"))));

    assertEquals("old", Files.readString(out.resolve("igm.jsonld")));
    try (Stream<Path> files = Files.list(out)) {
      assertEquals(1, files.count());
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
