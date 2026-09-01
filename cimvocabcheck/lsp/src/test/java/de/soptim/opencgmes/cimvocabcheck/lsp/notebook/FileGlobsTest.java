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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileGlobsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void plainPathsAreNotPatterns() {
    assertFalse(FileGlobs.isPattern("./rdf/a.ttl"));
    assertFalse(FileGlobs.isPattern("model.xml"));
    assertFalse(FileGlobs.isPattern("https://example.org/query"));
    assertFalse(FileGlobs.isPattern(null));
  }

  @Test
  public void globMetacharactersMakeAPattern() {
    assertTrue(FileGlobs.isPattern("./rdf/*.ttl"));
    assertTrue(FileGlobs.isPattern("./rdf/{a,b}.ttl"));
    assertTrue(FileGlobs.isPattern("file?.ttl"));
    assertTrue(FileGlobs.isPattern("file[ab].ttl"));
  }

  @Test
  public void starMatchesFilesInOneDirectorySorted() throws IOException {
    Path base = tmp.getRoot().toPath();
    Path b = write(base, "rdf/b.ttl");
    Path a = write(base, "rdf/a.ttl");
    write(base, "rdf/c.nt");
    write(base, "rdf/deeper/d.ttl"); // a single * must not cross directories

    assertEquals(List.of(a, b), FileGlobs.expand("./rdf/*.ttl", base));
  }

  @Test
  public void bracesSelectAlternatives() throws IOException {
    Path base = tmp.getRoot().toPath();
    Path a = write(base, "rdf/a.ttl");
    Path b = write(base, "rdf/b.ttl");
    write(base, "rdf/c.ttl");

    assertEquals(List.of(a, b), FileGlobs.expand("./rdf/{a,b}.ttl", base));
  }

  @Test
  public void doubleStarCrossesDirectories() throws IOException {
    Path base = tmp.getRoot().toPath();
    Path top = write(base, "rdf/a.ttl");
    Path deep = write(base, "rdf/deeper/d.ttl");

    assertEquals(List.of(top, deep), FileGlobs.expand("rdf/**.ttl", base));
  }

  @Test
  public void absolutePatternsIgnoreTheBase() throws IOException {
    Path base = tmp.getRoot().toPath();
    Path a = write(base, "rdf/a.ttl");

    assertEquals(List.of(a), FileGlobs.expand(base + "/rdf/*.ttl", Path.of("/nonexistent")));
  }

  @Test
  public void noMatchesAndMissingDirectoriesYieldEmpty() throws IOException {
    Path base = tmp.getRoot().toPath();
    write(base, "rdf/a.ttl");

    assertEquals(List.of(), FileGlobs.expand("./rdf/*.owl", base));
    assertEquals(List.of(), FileGlobs.expand("./nope/*.ttl", base));
    assertEquals(List.of(), FileGlobs.expand("./rdf/*.ttl", null));
  }

  @Test
  public void directoriesThemselvesNeverMatch() throws IOException {
    Path base = tmp.getRoot().toPath();
    Files.createDirectories(base.resolve("rdf/sub.ttl")); // a directory with a matching name
    Path a = write(base, "rdf/a.ttl");

    assertEquals(List.of(a), FileGlobs.expand("./rdf/*.ttl", base));
  }

  private static Path write(Path base, String relative) throws IOException {
    Path file = base.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "# data\n");
    return file;
  }
}
