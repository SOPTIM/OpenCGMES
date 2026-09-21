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

package de.soptim.opencgmes.cimvocabcheck.cli.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.soptim.opencgmes.cimvocabcheck.cli.ToolVersion;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationSeverity;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Formats validation results as a <a
 * href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html">SARIF 2.1.0</a> log — the
 * interchange format GitHub code scanning, Azure DevOps and most IDE result viewers consume.
 *
 * <p>One run is emitted, whose driver declares a rule per code actually reported; each finding
 * becomes a result carrying its own {@code level}, so promoting severities with {@code
 * --strictness} is reflected in the log without redefining the rules.
 *
 * <p>A clean run still emits a well-formed log with an empty {@code results} array — uploading it
 * is what clears previously reported alerts, so the file must be written either way.
 *
 * <pre>{@code
 * {
 *   "version": "2.1.0",
 *   "runs": [
 *     {
 *       "tool": {
 *         "driver": { "name": "cimvocabcheck", "rules": [ { "id": "UNKNOWN_CLASS", ... } ] }
 *       },
 *       "results": [ { "ruleId": "UNKNOWN_CLASS", "level": "error", ... } ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Artifact URIs are the validated file arguments verbatim (a leading {@code ./} is stripped) and
 * are relative to {@code %SRCROOT%}, so — as with the Code Quality report — the CLI must be invoked
 * with paths relative to the repository root for findings to line up with the source tree.
 */
public final class SarifFormatter {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private static final String SARIF_VERSION = "2.1.0";
  private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

  /** Namespaced key under which the shared finding fingerprint is published. */
  private static final String FINGERPRINT_KEY = "cimvocabcheckFinding/v1";

  private final boolean verbose;
  private final PrintWriter out;

  /** Creates a formatter writing to {@code out}; {@code verbose} includes WARN/INFO findings. */
  public SarifFormatter(PrintWriter out, boolean verbose) {
    this.out = out;
    this.verbose = verbose;
  }

  /** Writes {@code results} as a single SARIF log. */
  public void write(List<FileResult> results) {
    var fingerprints = new Fingerprints();
    // Rules are declared in first-seen order; ruleIndex is the position in that list.
    var rules = new LinkedHashSet<SparqlValidationCode>();
    var sarifResults = new ArrayList<Map<String, Object>>();

    for (FileResult r : results) {
      String path = Fingerprints.normalizePath(r.source());
      for (SparqlValidationAnnotation a : r.annotations()) {
        if (!shouldInclude(a)) {
          continue;
        }
        rules.add(a.code());
        sarifResults.add(toResult(path, a, ruleIndex(rules, a.code()), fingerprints));
      }
    }

    try {
      out.println(MAPPER.writeValueAsString(log(rules, sarifResults)));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, Object> log(
      Set<SparqlValidationCode> rules, List<Map<String, Object>> results) {
    var driver = new LinkedHashMap<String, Object>();
    driver.put("name", ReportContract.TOOL_NAME);
    driver.put("version", ToolVersion.current());
    driver.put("informationUri", ReportContract.INFORMATION_URI);
    driver.put("rules", rules.stream().map(SarifFormatter::toRule).toList());

    var run = new LinkedHashMap<String, Object>();
    run.put("tool", Map.of("driver", driver));
    // Jena reports columns as Java char offsets, which are UTF-16 code units — not the SARIF
    // default of Unicode code points.
    run.put("columnKind", "utf16CodeUnits");
    run.put("results", results);

    var log = new LinkedHashMap<String, Object>();
    log.put("$schema", SARIF_SCHEMA);
    log.put("version", SARIF_VERSION);
    log.put("runs", List.of(run));
    return log;
  }

  private static Map<String, Object> toRule(SparqlValidationCode code) {
    var rule = new LinkedHashMap<String, Object>();
    rule.put("id", code.name());
    rule.put("name", code.name());
    rule.put("helpUri", ReportContract.RULES_URI);
    return rule;
  }

  private static Map<String, Object> toResult(
      String path, SparqlValidationAnnotation a, int ruleIndex, Fingerprints fingerprints) {
    var region = new LinkedHashMap<String, Object>();
    region.put("startLine", a.line() != null ? a.line() : 1);
    if (a.column() != null) {
      region.put("startColumn", a.column());
    }

    var artifact = new LinkedHashMap<String, Object>();
    artifact.put("uri", path);
    artifact.put("uriBaseId", "%SRCROOT%");

    var physicalLocation = new LinkedHashMap<String, Object>();
    physicalLocation.put("artifactLocation", artifact);
    physicalLocation.put("region", region);

    var result = new LinkedHashMap<String, Object>();
    result.put("ruleId", a.code().name());
    result.put("ruleIndex", ruleIndex);
    result.put("level", levelOf(a.severity()));
    result.put("message", Map.of("text", a.message()));
    result.put("locations", List.of(Map.of("physicalLocation", physicalLocation)));
    result.put("partialFingerprints", Map.of(FINGERPRINT_KEY, fingerprints.of(path, a)));
    return result;
  }

  /** Maps a validation severity onto the SARIF result level scale. */
  private static String levelOf(SparqlValidationSeverity severity) {
    return switch (severity) {
      case ERROR -> "error";
      case WARN -> "warning";
      case INFO -> "note";
    };
  }

  private static int ruleIndex(Set<SparqlValidationCode> rules, SparqlValidationCode code) {
    int index = 0;
    for (SparqlValidationCode declared : rules) {
      if (declared == code) {
        return index;
      }
      index++;
    }
    throw new IllegalStateException("Rule not declared: " + code);
  }

  private boolean shouldInclude(SparqlValidationAnnotation a) {
    return switch (a.severity()) {
      case ERROR -> true;
      case WARN -> verbose;
      case INFO -> verbose;
    };
  }
}
