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

package de.soptim.opencgmes.cimvocabcheck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

/**
 * Integration tests for {@link ValidateCommand}, driving it through picocli against temporary
 * files. These cover the syntax-only fallback: embedded-SPARQL findings must carry real Turtle line
 * numbers in the Code Quality report, and the {@code standardVocabulary} opt-out must be honoured.
 */
public class ValidateCommandTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String SHAPES_BROKEN_EMBEDDED =
      "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
          + "@prefix ex: <http://example.org/> .\n"
          + "\n"
          + "ex:Shape a sh:NodeShape ;\n"
          + "    sh:sparql [ sh:select \"\"\"\n"
          + "SELEEECT ?this WHERE { ?this ?p ?o }\n" // Turtle line 6 (1-based)
          + "\"\"\" ] .\n";

  private static final String SHAPES_VOCAB_TYPO =
      "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
          + "@prefix ex: <http://example.org/> .\n"
          + "\n"
          + "ex:Shape a sh:NodeShape ;\n"
          + "    sh:taaargetClass ex:Foo .\n";

  @Test
  public void codeQualityReportsRealTurtleLineForEmbeddedSyntaxError() throws Exception {
    Path config = write("opencgmes.json", "{\"cimvocabcheck\": {}}");
    Path shapes = write("shapes.ttl", SHAPES_BROKEN_EMBEDDED);

    String out = run("--config", config.toString(), "--format", "codequality", shapes.toString());

    JsonNode findings = new ObjectMapper().readTree(out);
    JsonNode syntax = null;
    for (JsonNode f : findings) {
      if ("SYNTAX_ERROR".equals(f.path("check_name").asText())) {
        syntax = f;
      }
    }
    assertTrue("expected a SYNTAX_ERROR finding, got: " + out, syntax != null);
    int begin = syntax.path("location").path("lines").path("begin").asInt();
    assertEquals("embedded finding must point at the SELEEECT line, not line 1", 6, begin);
  }

  @Test
  public void standardVocabularyIgnoreSuppressesVocabTypoInSyntaxOnly() throws Exception {
    Path config =
        write("opencgmes.json", "{\"cimvocabcheck\": {\"standardVocabulary\": \"ignore\"}}");
    Path shapes = write("shapes.ttl", SHAPES_VOCAB_TYPO);

    String out = run("--config", config.toString(), "--format", "json", shapes.toString());
    assertFalse(
        "standardVocabulary=ignore must suppress the vocabulary typo: " + out,
        out.contains("UNKNOWN_VOCABULARY_TERM"));
  }

  @Test
  public void standardVocabularyDefaultReportsVocabTypoInSyntaxOnly() throws Exception {
    Path config = write("opencgmes.json", "{\"cimvocabcheck\": {}}");
    Path shapes = write("shapes.ttl", SHAPES_VOCAB_TYPO);

    String out = run("--config", config.toString(), "--format", "json", shapes.toString());
    assertTrue(
        "the vocabulary typo must be reported by default: " + out,
        out.contains("UNKNOWN_VOCABULARY_TERM"));
  }

  // ---- syntax-only SPARQL, valid input, output formats ------------------------------------

  @Test
  public void sparqlSyntaxOnly_validQuery_exitsZero() throws Exception {
    Path config = write("opencgmes.json", "{\"cimvocabcheck\": {}}");
    Path query = write("q.rq", "SELECT * WHERE { ?s ?p ?o }");
    assertEquals(0, exitOf("--config", config.toString(), query.toString()));
  }

  @Test
  public void sparqlSyntaxOnly_brokenQuery_reportsSyntaxError() throws Exception {
    Path config = write("opencgmes.json", "{\"cimvocabcheck\": {}}");
    Path query = write("q.rq", "SELEEECT * WHERE { ?s ?p ?o }");
    String out = run("--config", config.toString(), "--format", "json", query.toString());
    assertTrue("broken query must report a syntax error: " + out, out.contains("SYNTAX_ERROR"));
    assertEquals(1, exitOf("--config", config.toString(), query.toString()));
  }

  @Test
  public void validTurtle_exitsZero() throws Exception {
    Path config = write("opencgmes.json", "{\"cimvocabcheck\": {}}");
    Path shapes =
        write(
            "ok.ttl",
            "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <http://example.org/> .\n"
                + "ex:S a sh:NodeShape .\n");
    assertEquals(0, exitOf("--config", config.toString(), shapes.toString()));
  }

  @Test
  public void textFormat_reportsFinding() throws Exception {
    Path config = write("opencgmes.json", "{\"cimvocabcheck\": {}}");
    Path shapes = write("shapes.ttl", SHAPES_BROKEN_EMBEDDED);
    // Default (text) format, verbose to include all findings.
    String out = run("--config", config.toString(), "--verbose", shapes.toString());
    assertTrue("text output must mention the syntax error: " + out, out.contains("SYNTAX_ERROR"));
  }

  @Test
  public void unreadableInput_returnsUsageError() throws Exception {
    Path config = write("opencgmes.json", "{\"cimvocabcheck\": {}}");
    int exit = exitOf("--config", config.toString(), tmp.getRoot() + "/does-not-exist.ttl");
    assertEquals(2, exit);
  }

  // ---- helpers ----------------------------------------------------------------------------

  private Path write(String name, String content) throws Exception {
    Path p = tmp.newFile(name).toPath();
    Files.writeString(p, content);
    return p;
  }

  private static String run(String... args) {
    PrintStream originalOut = System.out;
    var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      new CommandLine(new ValidateCommand()).execute(args);
    } finally {
      System.setOut(originalOut);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static int exitOf(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    System.setOut(sink);
    System.setErr(sink);
    try {
      return new CommandLine(new ValidateCommand()).execute(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
