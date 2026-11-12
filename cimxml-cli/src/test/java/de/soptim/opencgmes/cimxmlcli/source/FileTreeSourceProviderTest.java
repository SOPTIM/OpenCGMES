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

package de.soptim.opencgmes.cimxmlcli.source;

import static de.soptim.opencgmes.cimxmlcli.TestData.entries;
import static de.soptim.opencgmes.cimxmlcli.TestData.write;
import static de.soptim.opencgmes.cimxmlcli.TestData.zip;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileTreeSourceProviderTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  /** Reads all sources: display path to content. */
  private static List<String> readAll(FileTreeSourceIterator sources) throws IOException {
    final var result = new ArrayList<String>();
    while (sources.hasNext()) {
      final var source = sources.next();
      try (var in = source.openStream()) {
        result.add(
            source.displayPath() + "=" + new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return result;
  }

  private List<String> scan(Path root, FileTreeSourceProvider provider) throws IOException {
    try (var sources = provider.open(root)) {
      final var result = readAll(sources);
      assertEquals(List.of(), sources.problems());
      return result;
    }
  }

  @Test
  public void findsFilesRecursivelyInSortedOrder() throws IOException {
    final var root = tmp.getRoot().toPath();
    write(root.resolve("b.xml"), "b");
    write(root.resolve("sub/a.xml"), "a");
    write(root.resolve("a.xml"), "first");
    write(root.resolve("ignored.txt"), "x");
    write(root.resolve("UPPER.XML"), "upper");

    assertEquals(
        List.of("UPPER.XML=upper", "a.xml=first", "b.xml=b", "sub/a.xml=a"),
        scan(root, new FileTreeSourceProvider(".xml")));
  }

  @Test
  public void readsEntriesOfZipArchives() throws IOException {
    final var root = tmp.getRoot().toPath();
    write(
        root.resolve("igm.zip"),
        zip(entries("EQ.xml", "eq", "readme.txt", "x", "dir/TP.xml", "tp")));

    assertEquals(
        List.of("igm.zip!/EQ.xml=eq", "igm.zip!/dir/TP.xml=tp"),
        scan(root, new FileTreeSourceProvider(".xml")));
  }

  @Test
  public void readsNestedZipArchives() throws IOException {
    final var root = tmp.getRoot().toPath();
    final var inner = zip(entries("SSH.xml", "ssh"));
    write(root.resolve("outer.zip"), zip(entries("inner.zip", inner, "EQ.xml", "eq")));

    assertEquals(
        List.of("outer.zip!/inner.zip!/SSH.xml=ssh", "outer.zip!/EQ.xml=eq"),
        scan(root, new FileTreeSourceProvider(".xml")));
  }

  @Test
  public void closingAnEntryStreamKeepsTheArchiveOpen() throws IOException {
    final var root = tmp.getRoot().toPath();
    write(root.resolve("igm.zip"), zip(entries("a.xml", "a", "b.xml", "b", "c.xml", "c")));

    // readAll closes every entry stream; all three entries must still be readable
    assertEquals(3, scan(root, new FileTreeSourceProvider(".xml")).size());
  }

  @Test
  public void skipsArchivesNestedTooDeeplyAndReportsThem() throws IOException {
    final var root = tmp.getRoot().toPath();
    final var level3 = zip(entries("deep.xml", "deep"));
    final var level2 = zip(entries("level3.zip", level3, "mid.xml", "mid"));
    write(root.resolve("level1.zip"), zip(entries("level2.zip", level2, "top.xml", "top")));

    try (var sources = new FileTreeSourceProvider(".xml", 2).open(root)) {
      assertEquals(
          List.of("level1.zip!/level2.zip!/mid.xml=mid", "level1.zip!/top.xml=top"),
          readAll(sources));
      assertEquals(1, sources.problems().size());
      assertEquals("level1.zip!/level2.zip!/level3.zip", sources.problems().getFirst().location());
    }
  }

  @Test
  public void reportsFilesThatAreNoZipArchives() throws IOException {
    final var root = tmp.getRoot().toPath();
    write(root.resolve("broken.zip"), "this is not a ZIP archive");
    write(root.resolve("outer.zip"), zip(entries("inner.zip", "no ZIP either", "EQ.xml", "eq")));

    try (var sources = new FileTreeSourceProvider(".xml").open(root)) {
      assertEquals(List.of("outer.zip!/EQ.xml=eq"), readAll(sources));
      assertEquals(
          List.of("broken.zip", "outer.zip!/inner.zip"),
          sources.problems().stream().map(Problem::location).toList());
    }
  }

  @Test
  public void emptyFolderHasNoSources() throws IOException {
    assertTrue(scan(tmp.getRoot().toPath(), new FileTreeSourceProvider(".rdf")).isEmpty());
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsInvalidDepth() {
    new FileTreeSourceProvider(".xml", 0);
  }
}
