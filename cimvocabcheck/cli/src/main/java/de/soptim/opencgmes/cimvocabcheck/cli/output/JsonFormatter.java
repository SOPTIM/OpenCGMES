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
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats validation results as JSON.
 *
 * <p>This is the report other languages bind to; its shape is published as a JSON Schema under
 * {@code cimvocabcheck/schemas/} and versioned by {@code contractVersion} (see {@link
 * ReportContract}). Fields are added, never renamed or removed, within a contract major — so
 * consumers must ignore fields and codes they do not know.
 *
 * <p>Output shape for a single-file result:
 *
 * <pre>{@code
 * {
 *   "contractVersion": "1.0",
 *   "tool": { "name": "cimvocabcheck", "version": "1.2.3" },
 *   "summary": { "files": 1, "valid": 0, "invalid": 1, "errors": 1, "warnings": 0, "infos": 0 },
 *   "results": [
 *     {
 *       "file": "query.rq",
 *       "valid": false,
 *       "annotations": [
 *         {
 *           "severity": "ERROR",
 *           "code": "UNKNOWN_CLASS",
 *           "line": 3,
 *           "column": 12,
 *           "term": "http://iec.ch/TC57/CIM100#Foo",
 *           "message": "Class <...> does not exist.",
 *           "foundInOtherProfiles": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"]
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>The {@code summary} counts describe what this document contains — they are taken after the
 * {@code --verbose} filter, so a consumer never sees a count it cannot account for in {@code
 * results}. Whether a file is {@code valid} is decided before filtering and is unaffected.
 */
public final class JsonFormatter {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final boolean verbose;
  private final PrintWriter out;

  /** Creates a formatter writing to {@code out}; {@code verbose} includes extra detail. */
  public JsonFormatter(PrintWriter out, boolean verbose) {
    this.out = out;
    this.verbose = verbose;
  }

  /** Writes {@code results} as a single JSON document. */
  public void write(List<FileResult> results) {
    List<FileResult> reported = results.stream().map(this::filtered).toList();
    long invalid = reported.stream().filter(r -> !r.valid()).count();

    var summary = new LinkedHashMap<String, Object>();
    summary.put("files", reported.size());
    summary.put("valid", reported.size() - invalid);
    summary.put("invalid", invalid);
    summary.put("errors", reported.stream().mapToLong(FileResult::errorCount).sum());
    summary.put("warnings", reported.stream().mapToLong(FileResult::warnCount).sum());
    summary.put("infos", reported.stream().mapToLong(FileResult::infoCount).sum());

    var tool = new LinkedHashMap<String, Object>();
    tool.put("name", ReportContract.TOOL_NAME);
    tool.put("version", ToolVersion.current());

    var root = new LinkedHashMap<String, Object>();
    root.put("contractVersion", ReportContract.VERSION);
    root.put("tool", tool);
    root.put("summary", summary);
    root.put("results", reported.stream().map(JsonFormatter::toResultMap).toList());
    try {
      out.println(MAPPER.writeValueAsString(root));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns {@code r} with only the annotations this report includes. */
  private FileResult filtered(FileResult r) {
    return new FileResult(
        r.source(), r.valid(), r.annotations().stream().filter(this::shouldInclude).toList());
  }

  private static Map<String, Object> toResultMap(FileResult r) {
    var map = new LinkedHashMap<String, Object>();
    map.put("file", r.source());
    map.put("valid", r.valid());
    map.put("annotations", r.annotations().stream().map(JsonFormatter::toAnnotationMap).toList());
    return map;
  }

  private static Map<String, Object> toAnnotationMap(SparqlValidationAnnotation a) {
    var map = new LinkedHashMap<String, Object>();
    map.put("severity", a.severity().name());
    map.put("code", a.code().name());
    if (a.line() != null) {
      map.put("line", a.line());
    }
    if (a.column() != null) {
      map.put("column", a.column());
    }
    if (a.term() != null && a.term().isURI()) {
      map.put("term", a.term().getURI());
    }
    if (a.graph() != null && a.graph().isURI()) {
      map.put("graph", a.graph().getURI());
    }
    map.put("message", a.message());
    // The "you may have the wrong profile in scope" hint the editors surface on the underline.
    if (!a.foundInOtherProfiles().isEmpty()) {
      map.put(
          "foundInOtherProfiles", a.foundInOtherProfiles().stream().map(VersionIri::iri).toList());
    }
    return map;
  }

  private boolean shouldInclude(SparqlValidationAnnotation a) {
    return switch (a.severity()) {
      case ERROR -> true;
      case WARN -> verbose;
      case INFO -> verbose;
    };
  }
}
