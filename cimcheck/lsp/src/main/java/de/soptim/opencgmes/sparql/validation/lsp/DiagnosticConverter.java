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

package de.soptim.opencgmes.sparql.validation.lsp;

import de.soptim.opencgmes.sparql.validation.SparqlValidationAnnotation;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/** Converts a {@link SparqlValidationAnnotation} to an LSP {@link Diagnostic}. */
final class DiagnosticConverter {

    private DiagnosticConverter() {}

    static Diagnostic convert(SparqlValidationAnnotation a) {
        // Our API uses 1-based line/column; LSP uses 0-based.
        int line = a.line()   != null ? a.line()   - 1 : 0;
        int col  = a.column() != null ? a.column() - 1 : 0;

        // Compute end column: for URI terms the token is <URI>, otherwise advance by 1.
        int endCol;
        if (a.term() != null && a.term().isURI()) {
            endCol = col + a.term().getURI().length() + 2; // +2 for the angle brackets
        } else {
            endCol = col + 1;
        }

        Range range = new Range(new Position(line, col), new Position(line, endCol));

        DiagnosticSeverity severity = switch (a.severity()) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARN  -> DiagnosticSeverity.Warning;
            case INFO  -> DiagnosticSeverity.Information;
        };

        Diagnostic d = new Diagnostic(range, a.message(), severity, "sparql-validate");
        d.setCode(Either.forLeft(a.code().name()));
        return d;
    }
}
