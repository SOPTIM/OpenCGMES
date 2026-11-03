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
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationSeverity;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

/**
 * Pins the machine-readable report contract that non-Java consumers bind to: the JSON document's
 * envelope and self-consistency, the SARIF log's shape, and the published JSON Schema staying in
 * step with the codes the engine can actually emit.
 *
 * <p>Every fixture runs against an empty config, i.e. in syntax-only mode, so the contract is
 * exercised without depending on a profile library being present.
 */
public class ReportContractTest {

  /** The contract version the CLI is expected to declare; bump deliberately, never by accident. */
  private static final String CONTRACT_VERSION = "1.0";

  private static final String SCHEMA_FILE =
      "cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json";

  /** The key SARIF publishes the shared finding fingerprint under. */
  private static final String FINGERPRINT_KEY = "cimvocabcheckFinding/v1";

  /** Produces exactly one ERROR — a SPARQL parse failure. */
  private static final String BROKEN_QUERY = "SELEEECT * WHERE { ?s ?p ?o }";

  /** Parses, but binds variables it never uses: WARN-severity findings, no error. */
  private static final String WARNING_QUERY = "SELECT ?s WHERE { ?s ?p ?o }";

  /** Clean in syntax-only mode — every variable is projected. */
  private static final String CLEAN_QUERY = "SELECT * WHERE { ?s ?p ?o }";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path config;

  @Before
  public void writeEmptyConfig() throws Exception {
    config = write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
  }

  // ---- JSON report -------------------------------------------------------------------------

  @Test
  public void jsonReportDeclaresTheContractAndTheEngineThatProducedIt() throws Exception {
    JsonNode report = MAPPER.readTree(run("--format", "json", write("q.rq", BROKEN_QUERY)));

    assertEquals(CONTRACT_VERSION, report.path("contractVersion").asText());
    assertEquals("cimvocabcheck", report.path("tool").path("name").asText());
    assertFalse(
        "tool.version must always be reported: " + report,
        report.path("tool").path("version").asText().isEmpty());
  }

  @Test
  public void jsonSummaryCountsExactlyWhatTheDocumentContains() throws Exception {
    JsonNode report =
        MAPPER.readTree(
            run(
                "--format",
                "json",
                "--verbose",
                write("broken.rq", BROKEN_QUERY),
                write("warning.rq", WARNING_QUERY)));

    JsonNode summary = report.path("summary");
    List<JsonNode> annotations = annotationsOf(report);

    assertEquals(2, summary.path("files").asInt());
    assertEquals(1, summary.path("invalid").asInt());
    assertEquals(1, summary.path("valid").asInt());
    assertEquals(countBySeverity(annotations, "ERROR"), summary.path("errors").asInt());
    assertEquals(countBySeverity(annotations, "WARN"), summary.path("warnings").asInt());
    assertEquals(countBySeverity(annotations, "INFO"), summary.path("infos").asInt());
    assertTrue("the fixture must produce WARN findings", summary.path("warnings").asInt() > 0);
  }

  @Test
  public void jsonSummaryFollowsTheVerboseFilter() throws Exception {
    JsonNode report = MAPPER.readTree(run("--format", "json", write("warning.rq", WARNING_QUERY)));

    assertEquals(
        "a non-verbose report holds no WARN findings",
        0,
        report.path("summary").path("warnings").asInt());
    assertEquals(0, countBySeverity(annotationsOf(report), "WARN"));
    assertTrue(
        "a file with only warnings stays valid",
        report.path("results").get(0).path("valid").asBoolean());
  }

  // ---- published JSON Schema ---------------------------------------------------------------

  @Test
  public void publishedSchemaEnumeratesExactlyTheCodesTheEngineCanEmit() throws Exception {
    JsonNode schema = MAPPER.readTree(Files.readString(schemaFile(), StandardCharsets.UTF_8));

    assertEquals(
        "the published schema must list every SparqlValidationCode, and no others",
        names(SparqlValidationCode.values()),
        enumValues(schema, "code"));
    assertEquals(names(SparqlValidationSeverity.values()), enumValues(schema, "severity"));
  }

  // ---- SARIF report ------------------------------------------------------------------------

  @Test
  public void sarifLogDeclaresARulePerReportedCodeAndMapsSeverities() throws Exception {
    JsonNode log =
        MAPPER.readTree(
            run(
                "--format",
                "sarif",
                "--verbose",
                write("broken.rq", BROKEN_QUERY),
                write("warning.rq", WARNING_QUERY)));

    assertEquals("2.1.0", log.path("version").asText());
    JsonNode sarifRun = log.path("runs").get(0);
    assertEquals("cimvocabcheck", sarifRun.path("tool").path("driver").path("name").asText());

    List<String> ruleIds = new ArrayList<>();
    for (JsonNode rule : sarifRun.path("tool").path("driver").path("rules")) {
      ruleIds.add(rule.path("id").asText());
    }
    assertEquals("a rule is declared once", ruleIds.size(), Set.copyOf(ruleIds).size());

    var reportedCodes = new TreeSet<String>();
    for (JsonNode result : sarifRun.path("results")) {
      String ruleId = result.path("ruleId").asText();
      reportedCodes.add(ruleId);
      assertEquals(
          "ruleIndex must address the declaring rule",
          ruleId,
          ruleIds.get(result.path("ruleIndex").asInt()));
      assertEquals(
          "SYNTAX_ERROR is an error, an unused variable is a warning",
          "SYNTAX_ERROR".equals(ruleId) ? "error" : "warning",
          result.path("level").asText());
      assertFalse(
          "every result carries the shared fingerprint",
          result.path("partialFingerprints").path(FINGERPRINT_KEY).asText().isEmpty());

      JsonNode location = result.path("locations").get(0).path("physicalLocation");
      assertFalse(
          "every result locates a file",
          location.path("artifactLocation").path("uri").asText().isEmpty());
      assertTrue("startLine is 1-based", location.path("region").path("startLine").asInt() >= 1);
    }
    assertEquals(new TreeSet<>(ruleIds), reportedCodes);
    assertTrue(reportedCodes.contains("SYNTAX_ERROR"));
  }

  @Test
  public void sarifLogIsStillWellFormedWhenNothingWasFound() throws Exception {
    JsonNode log = MAPPER.readTree(run("--format", "sarif", write("q.rq", CLEAN_QUERY)));
    JsonNode sarifRun = log.path("runs").get(0);

    assertEquals("2.1.0", log.path("version").asText());
    assertTrue("a clean run must still emit a results array", sarifRun.path("results").isArray());
    assertEquals(0, sarifRun.path("results").size());
    assertEquals(0, sarifRun.path("tool").path("driver").path("rules").size());
  }

  @Test
  public void sarifAndCodeQualityAgreeOnAFindingsIdentity() throws Exception {
    Path broken = write("broken.rq", BROKEN_QUERY);
    Path warning = write("warning.rq", WARNING_QUERY);

    JsonNode sarif = MAPPER.readTree(run("--format", "sarif", "--verbose", broken, warning));
    JsonNode codeQuality =
        MAPPER.readTree(run("--format", "codequality", "--verbose", broken, warning));

    var sarifFingerprints = new TreeSet<String>();
    for (JsonNode result : sarif.path("runs").get(0).path("results")) {
      sarifFingerprints.add(result.path("partialFingerprints").path(FINGERPRINT_KEY).asText());
    }
    var codeQualityFingerprints = new TreeSet<String>();
    for (JsonNode issue : codeQuality) {
      codeQualityFingerprints.add(issue.path("fingerprint").asText());
    }

    assertFalse(sarifFingerprints.isEmpty());
    assertEquals(
        "one finding has one identity in every report format",
        codeQualityFingerprints,
        sarifFingerprints);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static List<JsonNode> annotationsOf(JsonNode report) {
    var annotations = new ArrayList<JsonNode>();
    for (JsonNode result : report.path("results")) {
      result.path("annotations").forEach(annotations::add);
    }
    return annotations;
  }

  private static int countBySeverity(List<JsonNode> annotations, String severity) {
    return (int)
        annotations.stream().filter(a -> severity.equals(a.path("severity").asText())).count();
  }

  private static Set<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> enumValues(JsonNode schema, String definition) {
    var values = new TreeSet<String>();
    schema.path("definitions").path(definition).path("enum").forEach(v -> values.add(v.asText()));
    return values;
  }

  /** Locates the published schema from wherever the build put the working directory. */
  private static Path schemaFile() {
    Path start = Path.of("").toAbsolutePath();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(SCHEMA_FILE);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("Could not find " + SCHEMA_FILE + " above " + start);
  }

  private Path write(String name, String content) throws Exception {
    Path p = tmp.newFile(name).toPath();
    Files.writeString(p, content);
    return p;
  }

  /** Runs the CLI against the empty config; {@code Path} arguments are passed as their path. */
  private String run(Object... args) {
    var argv = new ArrayList<String>(List.of("--config", config.toString()));
    Arrays.stream(args).map(String::valueOf).forEach(argv::add);

    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try {
      new CommandLine(new ValidateCommand()).execute(argv.toArray(new String[0]));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
