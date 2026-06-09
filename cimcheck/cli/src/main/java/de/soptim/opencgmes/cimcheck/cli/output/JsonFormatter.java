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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final boolean verbose;
    private final PrintWriter out;

    public JsonFormatter(PrintWriter out, boolean verbose) {
        this.out     = out;
        this.verbose = verbose;
    }

    public void write(List<FileResult> results) {
        long invalid = results.stream().filter(r -> !r.valid()).count();
        var root    = new LinkedHashMap<String, Object>();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("files",   results.size());
        summary.put("valid",   results.size() - invalid);
        summary.put("invalid", invalid);
        root.put("summary", summary);
        root.put("results", results.stream().map(this::toResultMap).toList());
        try {
            MAPPER.writeValue(out, root);
            out.println();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> toResultMap(FileResult r) {
        var filtered = r.annotations().stream()
                .filter(this::shouldInclude)
                .toList();
        var map = new LinkedHashMap<String, Object>();
        map.put("file",        r.source());
        map.put("valid",       r.valid());
        map.put("annotations", filtered.stream().map(this::toAnnotationMap).toList());
        return map;
    }

    private Map<String, Object> toAnnotationMap(SparqlValidationAnnotation a) {
        var map = new LinkedHashMap<String, Object>();
        map.put("severity", a.severity().name());
        map.put("code",     a.code().name());
        if (a.line() != null)   map.put("line",   a.line());
        if (a.column() != null) map.put("column", a.column());
        if (a.term() != null && a.term().isURI()) map.put("term", a.term().getURI());
        map.put("message", a.message());
        return map;
    }

    private boolean shouldInclude(SparqlValidationAnnotation a) {
        return switch (a.severity()) {
            case ERROR -> true;
            case WARN  -> verbose;
            case INFO  -> verbose;
        };
    }
}
