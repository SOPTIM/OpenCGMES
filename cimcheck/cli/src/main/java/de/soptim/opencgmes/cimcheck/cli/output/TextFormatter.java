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

package de.soptim.opencgmes.cimcheck.cli.output;

import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;

import java.io.PrintWriter;
import java.util.List;

/**
 * Formats validation results as compiler-style diagnostics, e.g.:
 * <pre>
 * query.rq:3:12: error: [UNKNOWN_CLASS] Class &lt;...&gt; does not exist in profile [Equipment/1.0].
 * query.rq:5:8: warning: [DATATYPE_MISMATCH] Literal "abc" for &lt;...&gt;.
 * </pre>
 */
public final class TextFormatter {

    private final boolean verbose;
    private final PrintWriter out;

    public TextFormatter(PrintWriter out, boolean verbose) {
        this.out     = out;
        this.verbose = verbose;
    }

    /** Writes all file results, then a summary line. */
    public void write(List<FileResult> results) {
        for (FileResult r : results) {
            writeFile(r);
        }
        writeSummary(results);
    }

    private void writeFile(FileResult r) {
        boolean anyPrinted = false;
        for (SparqlValidationAnnotation a : r.annotations()) {
            if (!shouldPrint(a)) continue;
            anyPrinted = true;
            out.println(formatLine(r.source(), a));
        }
        // Blank line between files when there are annotations.
        if (anyPrinted) out.println();
    }

    private String formatLine(String source, SparqlValidationAnnotation a) {
        var sb = new StringBuilder();

        // Location: source:line:col or source if no position info.
        sb.append(source);
        if (a.line() != null) {
            sb.append(':').append(a.line());
            if (a.column() != null) sb.append(':').append(a.column());
        }
        sb.append(": ");

        // Severity label.
        sb.append(switch (a.severity()) {
            case ERROR -> "error";
            case WARN  -> "warning";
            case INFO  -> "info";
        });
        sb.append(": [").append(a.code().name()).append("] ");
        sb.append(a.message());

        return sb.toString();
    }

    private void writeSummary(List<FileResult> results) {
        long errors   = results.stream().mapToLong(FileResult::errorCount).sum();
        long warnings = results.stream().mapToLong(FileResult::warnCount).sum();
        int  total    = results.size();
        long invalid  = results.stream().filter(r -> !r.valid()).count();

        if (total == 1) {
            // Single-file: compact summary.
            if (errors == 0 && warnings == 0) {
                out.println(results.get(0).source() + ": OK");
            } else {
                out.printf("%s: %d error(s), %d warning(s)%n",
                        results.get(0).source(), errors, warnings);
            }
        } else {
            // Multi-file summary.
            out.printf("──────────────────────────────────────────%n");
            out.printf("%d file(s): %d valid, %d invalid — %d error(s), %d warning(s)%n",
                    total, total - invalid, invalid, errors, warnings);
        }
    }

    private boolean shouldPrint(SparqlValidationAnnotation a) {
        return switch (a.severity()) {
            case ERROR -> true;
            case WARN  -> verbose;
            case INFO  -> verbose;
        };
    }
}
