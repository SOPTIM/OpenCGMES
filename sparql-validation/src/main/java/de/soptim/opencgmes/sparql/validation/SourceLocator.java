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

package de.soptim.opencgmes.sparql.validation;

import org.apache.jena.graph.Node;

/**
 * Best-effort locator that maps an RDF term to a (line, column) inside the original query string.
 *
 * <p>Jena's algebra does not carry source positions, so we cannot do this exactly. Phase 1
 * simply searches for the full IRI in {@code <...>} form. If the term is only written as a
 * prefixed name, the locator returns {@code null}/{@code null} and the consumer can fall back
 * to the message field.</p>
 */
final class SourceLocator {

    private SourceLocator() {}

    record Location(Integer line, Integer column) {}

    static final Location UNKNOWN = new Location(null, null);

    static Location locate(String query, Node term) {
        if (query == null || term == null || !term.isURI()) return UNKNOWN;
        String iri = term.getURI();
        String needle = "<" + iri + ">";
        int idx = query.indexOf(needle);
        if (idx < 0) return UNKNOWN;
        return toLineColumn(query, idx);
    }

    private static Location toLineColumn(String text, int offset) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < offset; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new Location(line, col);
    }
}
