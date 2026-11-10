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

package de.soptim.opencgmes.cimxmlcli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

public class Cimxml2JsonLdCommandTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();
  private String schema;
  private String model;
  private String output;

  @Before
  public void setUp() throws IOException {
    schema = tmp.newFolder("rdfs").getPath();
    model = tmp.newFolder("igm").getPath();
    output = new File(tmp.getRoot(), "out.jsonld").getPath();
  }

  private int run(String... args) {
    final var cmd = new CommandLine(new Cimxml2JsonLdCommand());
    cmd.setOut(new PrintWriter(out, true));
    cmd.setErr(new PrintWriter(err, true));
    return cmd.execute(args);
  }

  @Test
  public void helpListsAllOptions() {
    assertEquals(0, run("--help"));
    final var help = out.toString();
    for (String option :
        new String[] {"--schema", "--model", "--output", "--compact", "--quiet", "--verbose"}) {
      assertTrue("help mentions " + option, help.contains(option));
    }
  }

  @Test
  public void shortHelpOptionWorks() {
    assertEquals(0, run("-h"));
    assertTrue(out.toString().startsWith("Usage: cimxml2jsonld"));
  }

  @Test
  public void compactHasShortForm() {
    assertEquals(0, run("--help"));
    assertTrue(out.toString(), out.toString().contains("-c, --compact"));
  }

  @Test
  public void versionComesFromBuild() {
    assertEquals(0, run("--version"));
    final var version = out.toString().trim();
    assertTrue(version, version.startsWith("cimxml2jsonld "));
    assertTrue(version, !version.contains("${"));
  }

  @Test
  public void missingRequiredOptionIsUsageError() {
    assertEquals(2, run("-s", schema, "-m", model));
    assertTrue(err.toString(), err.toString().contains("--output"));
  }

  @Test
  public void nonExistingSchemaFolderIsUsageError() {
    assertEquals(2, run("-s", schema + "-missing", "-m", model, "-o", output));
    assertTrue(err.toString(), err.toString().contains("--schema"));
  }

  @Test
  public void fileAsModelFolderIsUsageError() throws IOException {
    final var file = tmp.newFile("igm.xml").getPath();
    assertEquals(2, run("-s", schema, "-m", file, "-o", output));
    assertTrue(err.toString(), err.toString().contains("--model"));
  }

  @Test
  public void writesTheDataset() {
    TestData.copyMiniRdfs(Path.of(schema));
    TestData.copyMiniModels(Path.of(model));

    assertEquals(0, run("-s", schema, "-m", model, "-o", output));
    assertTrue(Files.isRegularFile(Path.of(output)));
    final var text = err.toString();
    assertTrue(text, text.contains("[ 1] EQ     <- MINI_EQ.xml"));
    assertTrue(text, text.contains("Done: " + output));
    assertEquals("", out.toString());
  }

  @Test
  public void quietPrintsNothingOnSuccess() {
    TestData.copyMiniRdfs(Path.of(schema));
    TestData.copyMiniModels(Path.of(model));

    assertEquals(0, run("-q", "-s", schema, "-m", model, "-o", output));
    assertEquals("", err.toString());
  }

  @Test
  public void invalidOutputExtensionIsUsageError() {
    assertEquals(2, run("-s", schema, "-m", model, "-o", output + ".ttl"));
    assertTrue(err.toString(), err.toString().contains("must end with .jsonld or .jsonld.gz"));
  }

  @Test
  public void listsAllProblems() {
    TestData.copyMiniModels(Path.of(model));

    assertEquals(1, run("-s", schema, "-m", model, "-o", output));
    final var text = err.toString();
    assertTrue(text, text.contains("problem(s) found, nothing was written:"));
    assertTrue(Files.notExists(Path.of(output)));
    assertTrue(text, text.contains("contains no .rdf files"));
    assertTrue(text, text.contains("MINI_EQ.xml: no RDFS profile in the schema folder matches"));
    assertTrue(text, text.contains("    hint: Add the RDFS file(s)"));
  }

  @Test
  public void recognizesWrappedOutOfMemoryErrors() {
    final var wrapped = new IllegalStateException("writing", new OutOfMemoryError("heap"));
    assertTrue(Cimxml2JsonLdCommand.causedByOutOfMemory(wrapped));
    assertFalse(Cimxml2JsonLdCommand.causedByOutOfMemory(new IllegalStateException("other")));
  }

  @Test
  public void quietAndVerboseCannotBeCombined() {
    assertEquals(2, run("-s", schema, "-m", model, "-o", output, "-q", "-v"));
    assertTrue(err.toString(), err.toString().contains("--quiet and --verbose"));
  }
}
