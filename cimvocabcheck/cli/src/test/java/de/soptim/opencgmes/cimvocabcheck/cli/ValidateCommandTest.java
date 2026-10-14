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

package de.soptim.opencgmes.cimvocabcheck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
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

  /** The smallest file the schema loader accepts as a CIM profile: one class, cim:Breaker. */
  private static final String TINY_PROFILE =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
          + " xmlns:cim=\"http://iec.ch/TC57/CIM100#\" xmlns:dcat=\"http://www.w3.org/ns/dcat#\""
          + " xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
          + "  <rdf:Description rdf:about=\"http://example.org/Tiny#Ontology\">\n"
          + "    <rdf:type rdf:resource=\"http://www.w3.org/2002/07/owl#Ontology\"/>\n"
          + "    <dcat:keyword>TN</dcat:keyword>\n"
          + "    <owl:versionIRI rdf:resource=\"http://example.org/Tiny/1.0\"/>\n"
          + "  </rdf:Description>\n"
          + "  <rdf:Description rdf:about=\"http://iec.ch/TC57/CIM100#Breaker\">\n"
          + "    <rdf:type rdf:resource=\"http://www.w3.org/2000/01/rdf-schema#Class\"/>\n"
          + "  </rdf:Description>\n"
          + "</rdf:RDF>\n";

  @Test
  public void codeQualityReportsRealTurtleLineForEmbeddedSyntaxError() throws Exception {
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
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
        write("opencgmes.jsonc", "{\"cimvocabcheck\": {\"standardVocabulary\": \"ignore\"}}");
    Path shapes = write("shapes.ttl", SHAPES_VOCAB_TYPO);

    String out = run("--config", config.toString(), "--format", "json", shapes.toString());
    assertFalse(
        "standardVocabulary=ignore must suppress the vocabulary typo: " + out,
        out.contains("UNKNOWN_VOCABULARY_TERM"));
  }

  @Test
  public void standardVocabularyDefaultReportsVocabTypoInSyntaxOnly() throws Exception {
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
    Path shapes = write("shapes.ttl", SHAPES_VOCAB_TYPO);

    String out = run("--config", config.toString(), "--format", "json", shapes.toString());
    assertTrue(
        "the vocabulary typo must be reported by default: " + out,
        out.contains("UNKNOWN_VOCABULARY_TERM"));
  }

  @Test
  public void anUnreadableInputIsReportedWithoutCostingTheRestOfTheBatch() throws Exception {
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
    Path query = write("q.rq", "SELECT * WHERE { ?s ?p ?o }");
    String missing = tmp.getRoot().toPath().resolve("missing.rq").toString();
    String[] args = {"--config", config.toString(), "--format", "json", query.toString(), missing};

    JsonNode results = new ObjectMapper().readTree(run(args)).path("results");

    assertEquals(1, exitOf(args));
    assertEquals(2, results.size());
    assertTrue("the readable input is still validated", results.get(0).path("valid").asBoolean());
    JsonNode unreadable = results.get(1);
    assertEquals(missing, unreadable.path("file").asText());
    assertFalse(unreadable.path("valid").asBoolean());
    assertEquals("UNREADABLE_INPUT", unreadable.path("annotations").get(0).path("code").asText());
    assertEquals("ERROR", unreadable.path("annotations").get(0).path("severity").asText());
  }

  @Test
  public void anUnreadableInputFailsEvenAPermissiveRun() throws Exception {
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
    String missing = tmp.getRoot().toPath().resolve("missing.rq").toString();

    assertEquals(1, exitOf("--config", config.toString(), "--strictness", "permissive", missing));
  }

  // ---- syntax-only SPARQL, valid input, output formats ------------------------------------

  @Test
  public void sparqlSyntaxOnly_validQuery_exitsZero() throws Exception {
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
    Path query = write("q.rq", "SELECT * WHERE { ?s ?p ?o }");
    assertEquals(0, exitOf("--config", config.toString(), query.toString()));
  }

  @Test
  public void sparqlSyntaxOnly_brokenQuery_reportsSyntaxError() throws Exception {
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
    Path query = write("q.rq", "SELEEECT * WHERE { ?s ?p ?o }");
    String out = run("--config", config.toString(), "--format", "json", query.toString());
    assertTrue("broken query must report a syntax error: " + out, out.contains("SYNTAX_ERROR"));
    assertEquals(1, exitOf("--config", config.toString(), query.toString()));
  }

  @Test
  public void validTurtle_exitsZero() throws Exception {
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
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
    Path config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
    Path shapes = write("shapes.ttl", SHAPES_BROKEN_EMBEDDED);
    // Default (text) format, verbose to include all findings.
    String out = run("--config", config.toString(), "--verbose", shapes.toString());
    assertTrue("text output must mention the syntax error: " + out, out.contains("SYNTAX_ERROR"));
  }

  /**
   * A schema directory with one parseable profile and one that isn't must still validate (using the
   * good profile), and must print a visible warning naming the skipped file — previously this was
   * only logged via SLF4J and invisible on the CLI.
   */
  @Test
  public void mixedGoodAndBadSchemaFiles_printsVisibleSkipWarning() throws Exception {
    Path schemaDir = tmp.newFolder("schemas").toPath();
    Files.writeString(schemaDir.resolve("good.rdf"), MINIMAL_GOOD_CIM16_RDF);
    Files.writeString(schemaDir.resolve("bad.rdf"), MINIMAL_BAD_CIM16_RDF);
    Path query = write("q.rq", "SELECT * WHERE { ?s ?p ?o }");

    String err = runCapturingStderr("--schema", schemaDir.toString(), query.toString());

    assertTrue(
        "stderr must warn about the skipped file: " + err,
        err.contains("schema loaded with warnings") && err.contains("bad.rdf"));
  }

  private static final String MINIMAL_GOOD_CIM16_RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.shortName">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">TST</cims:isFixed>
        </rdf:Description>
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.entsoeURI">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/TestProfile/1</cims:isFixed>
        </rdf:Description>
      </rdf:RDF>
      """;

  private static final String MINIMAL_BAD_CIM16_RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
        <rdfs:Class rdf:about="http://iec.ch/TC57/2013/CIM-schema-cim16#Foo"/>
      </rdf:RDF>
      """;

  @Test
  public void anInputNameThePlatformCannotEncodeIsAReadErrorNotACrash() {
    // A NUL is unrepresentable everywhere; a non-ASCII name under a C locale fails the same way.
    String input = "q\u0000.rq";

    assertEquals(1, exitOf("--format", "json", input));
    String out = run("--format", "json", input);
    assertTrue(out, out.contains("UNREADABLE_INPUT") && out.contains("file name encoding"));
  }

  @Test
  public void aCrashExitsWithTheToolFailureCodeNotTheFindingsCode() throws Exception {
    // Exit 1 means "the report has errors"; a crash produced no report.
    CommandLine commandLine = Main.commandLine();
    var errors = new ByteArrayOutputStream();
    commandLine.setErr(new PrintWriter(errors, true, StandardCharsets.UTF_8));

    int code =
        commandLine
            .getExecutionExceptionHandler()
            .handleExecutionException(
                new IllegalStateException("boom"), commandLine, commandLine.parseArgs("q.rq"));

    assertEquals(2, code);
    assertTrue(errors.toString(StandardCharsets.UTF_8).contains("boom"));
  }

  @Test
  public void anAutoDiscoveredConfigResolvesItsPathsAgainstItsOwnDirectory() throws Exception {
    // Run from a subdirectory: the config is found above it, and "profiles" means the directory
    // next to the config, not one next to wherever the CLI happens to run.
    write("opencgmes.jsonc", "{\"cimvocabcheck\": {\"schemasDirectory\": \"profiles\"}}");
    tmp.newFolder("profiles");
    write("profiles/tiny.rdf", TINY_PROFILE);
    tmp.newFolder("queries");
    write("queries/q.rq", "PREFIX cim: <http://iec.ch/TC57/CIM100#> SELECT * { ?b a cim:Breakr }");

    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Main.class.getName(),
                "--format",
                "json",
                "q.rq")
            .directory(tmp.getRoot().toPath().resolve("queries").toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertEquals("the schema must load and the typo be reported: " + out, 1, process.waitFor());
    assertTrue(out, out.contains("UNKNOWN_CLASS"));
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
      Main.commandLine().execute(args);
    } finally {
      System.setOut(originalOut);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static String runCapturingStderr(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    var outSink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    var errBuffer = new ByteArrayOutputStream();
    System.setOut(outSink);
    System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
    try {
      Main.commandLine().execute(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private static int exitOf(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    System.setOut(sink);
    System.setErr(sink);
    try {
      return Main.commandLine().execute(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
