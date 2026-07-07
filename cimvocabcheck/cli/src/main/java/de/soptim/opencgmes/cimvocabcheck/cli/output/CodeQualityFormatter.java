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
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationSeverity;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats validation results as a <a href="https://docs.gitlab.com/ci/testing/code_quality/">GitLab
 * / CodeClimate Code Quality</a> report.
 *
 * <p>The output is a single, top-level JSON array (not the wrapped object {@link JsonFormatter}
 * produces) — one element per finding — written to a file that the pipeline declares as an {@code
 * artifacts:reports:codequality} artifact. GitLab then renders the findings in the merge request.
 *
 * <pre>{@code
 * [
 *   {
 *     "description": "Class <...> does not exist in profile [Equipment].",
 *     "check_name": "UNKNOWN_CLASS",
 *     "severity": "major",
 *     "fingerprint": "a1b2c3...",
 *     "location": { "path": "shapes/equipment.ttl", "lines": { "begin": 3 } }
 *   }
 * ]
 * }</pre>
 *
 * <p>{@code location.path} is taken verbatim from the validated file argument (a leading {@code ./}
 * is stripped). For the findings to line up with the merge-request diff, the CLI must therefore be
 * invoked with paths relative to the repository root.
 */
public final class CodeQualityFormatter {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final boolean verbose;
  private final PrintWriter out;

  /** Creates a formatter writing to {@code out}; {@code verbose} includes WARN/INFO findings. */
  public CodeQualityFormatter(PrintWriter out, boolean verbose) {
    this.out = out;
    this.verbose = verbose;
  }

  /** Writes {@code results} as a single Code Quality JSON array. */
  public void write(List<FileResult> results) {
    var issues = new ArrayList<Map<String, Object>>();
    // Counts how many findings sharing the same identity have already been emitted, so genuinely
    // duplicate findings get distinct fingerprints — independent of the order findings appear in.
    var occurrences = new HashMap<String, Integer>();
    for (FileResult r : results) {
      String path = stripDotSlash(r.source());
      for (SparqlValidationAnnotation a : r.annotations()) {
        if (!shouldInclude(a)) {
          continue;
        }
        String identity = identity(path, a);
        int occurrence = occurrences.merge(identity, 1, Integer::sum) - 1;
        String fingerprint = sha256Hex(identity + ' ' + occurrence);
        issues.add(toIssue(path, a, fingerprint));
      }
    }
    try {
      out.println(MAPPER.writeValueAsString(issues));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, Object> toIssue(
      String path, SparqlValidationAnnotation a, String fingerprint) {
    int beginLine = a.line() != null ? a.line() : 1;

    var lines = new LinkedHashMap<String, Object>();
    lines.put("begin", beginLine);
    var location = new LinkedHashMap<String, Object>();
    location.put("path", path);
    location.put("lines", lines);

    var issue = new LinkedHashMap<String, Object>();
    issue.put("description", a.message());
    issue.put("check_name", a.code().name());
    issue.put("severity", severityOf(a.severity()));
    issue.put("fingerprint", fingerprint);
    issue.put("location", location);
    return issue;
  }

  /** Maps a validation severity onto the GitLab Code Quality severity scale. */
  private static String severityOf(SparqlValidationSeverity severity) {
    return switch (severity) {
      case ERROR -> "major";
      case WARN -> "minor";
      case INFO -> "info";
    };
  }

  /**
   * The stable identity of a finding: file, rule, position and offending term. Deliberately
   * excludes the rendered message — its profile list is not emitted in a deterministic order, so
   * folding it into the fingerprint would make the same finding churn between otherwise-identical
   * runs and break GitLab's ability to track it over time.
   */
  private static String identity(String path, SparqlValidationAnnotation a) {
    String term = a.term() != null && a.term().isURI() ? a.term().getURI() : "";
    return String.join(
        " ", path, a.code().name(), String.valueOf(a.line()), String.valueOf(a.column()), term);
  }

  private static String sha256Hex(String input) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    var sb = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  private static String stripDotSlash(String path) {
    return path.startsWith("./") ? path.substring(2) : path;
  }

  private boolean shouldInclude(SparqlValidationAnnotation a) {
    return switch (a.severity()) {
      case ERROR -> true;
      case WARN -> verbose;
      case INFO -> verbose;
    };
  }
}
