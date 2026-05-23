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

package de.soptim.opencgmes.sparql.validation.cli.output;

import de.soptim.opencgmes.sparql.validation.SparqlValidationAnnotation;
import de.soptim.opencgmes.sparql.validation.SparqlValidationSeverity;

import java.io.PrintWriter;
import java.util.List;

/**
 * Formats validation results as JSON.
 *
 * <p>Output shape for a single-file result:</p>
 * <pre>{@code
 * {
 *   "summary": { "files": 1, "valid": 0, "invalid": 1 },
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
 *           "message": "Class <...> does not exist."
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class JsonFormatter {

    private final boolean verbose;
    private final PrintWriter out;

    public JsonFormatter(PrintWriter out, boolean verbose) {
        this.out     = out;
        this.verbose = verbose;
    }

    public void write(List<FileResult> results) {
        long invalid = results.stream().filter(r -> !r.valid()).count();

        out.println("{");
        out.printf("  \"summary\": { \"files\": %d, \"valid\": %d, \"invalid\": %d },%n",
                results.size(), results.size() - invalid, invalid);
        out.println("  \"results\": [");
        for (int i = 0; i < results.size(); i++) {
            writeFileResult(results.get(i), "    ");
            if (i < results.size() - 1) out.print(",");
            out.println();
        }
        out.println("  ]");
        out.println("}");
    }

    private void writeFileResult(FileResult r, String indent) {
        var filtered = r.annotations().stream()
                .filter(this::shouldInclude)
                .toList();

        out.printf("%s{%n", indent);
        out.printf("%s  \"file\": %s,%n", indent, jsonString(r.source()));
        out.printf("%s  \"valid\": %s,%n", indent, r.valid());
        out.printf("%s  \"annotations\": [%n", indent);
        for (int i = 0; i < filtered.size(); i++) {
            writeAnnotation(filtered.get(i), indent + "    ");
            if (i < filtered.size() - 1) out.print(",");
            out.println();
        }
        out.printf("%s  ]%n", indent);
        out.printf("%s}", indent);
    }

    private void writeAnnotation(SparqlValidationAnnotation a, String indent) {
        out.printf("%s{%n", indent);
        out.printf("%s  \"severity\": %s,%n", indent, jsonString(a.severity().name()));
        out.printf("%s  \"code\": %s,%n",     indent, jsonString(a.code().name()));
        if (a.line() != null)   out.printf("%s  \"line\": %d,%n",   indent, a.line());
        if (a.column() != null) out.printf("%s  \"column\": %d,%n", indent, a.column());
        if (a.term() != null && a.term().isURI()) {
            out.printf("%s  \"term\": %s,%n", indent, jsonString(a.term().getURI()));
        }
        out.printf("%s  \"message\": %s%n", indent, jsonString(a.message()));
        out.printf("%s}", indent);
    }

    private boolean shouldInclude(SparqlValidationAnnotation a) {
        return switch (a.severity()) {
            case ERROR -> true;
            case WARN  -> verbose;
            case INFO  -> verbose;
        };
    }

    /** Minimal JSON string escaping — covers all characters that must be escaped in JSON. */
    private static String jsonString(String value) {
        if (value == null) return "null";
        var sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
