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
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort locator that maps an RDF term to a (line, column) inside the original query string.
 *
 * <p>Jena's algebra doesn't carry source positions, so we search the original text instead. The
 * locator tries — in order — the three forms a SPARQL query can use to refer to a term:</p>
 * <ol>
 *   <li>{@code <full-IRI>} — the most precise form, tried first.</li>
 *   <li>{@code prefix:local} — using every prefix declaration in the query's
 *       {@link PrefixMapping} whose namespace IRI is a prefix of the target IRI.</li>
 *   <li>The SPARQL shorthand {@code a} — only when the target is {@code rdf:type}.</li>
 * </ol>
 *
 * <p>The earliest match across all three forms wins. If the term appears multiple times, the
 * first occurrence is returned.</p>
 *
 * <p>The {@code a} keyword detection is regex-based and intentionally conservative: it only
 * matches {@code a} that is preceded by start-of-text / whitespace / {@code .} / {@code ;}
 * and followed by a predicate-position token. False positives inside string literals are
 * possible but rare in practice — the locator is best-effort.</p>
 */
public final class SourceLocator {

    private static final String RDF_TYPE_URI = RDF.type.getURI();

    /** {@code a} token followed by whitespace and a predicate-position start char. */
    private static final Pattern A_KEYWORD =
            Pattern.compile("a\\s+[<\\w?$\\[(]");

    private SourceLocator() {}

    public record Location(Integer line, Integer column) {}

    public static final Location UNKNOWN = new Location(null, null);

    /** Convenience: locate without prefix-mapping context (full-IRI form only). */
    public static Location locate(String query, Node term) {
        return locate(query, term, null);
    }

    /**
     * Best-effort locate that considers all three SPARQL term forms.
     *
     * @param query     original query text, may be {@code null}
     * @param term      the URI node to find, may be {@code null}
     * @param prefixes  prefix map from the parsed {@link org.apache.jena.query.Query}, may be {@code null}
     */
    public static Location locate(String query, Node term, PrefixMapping prefixes) {
        if (query == null || term == null || !term.isURI()) return UNKNOWN;
        String iri = term.getURI();
        int best = Integer.MAX_VALUE;

        // 1. <full-IRI> form
        int idx = query.indexOf("<" + iri + ">");
        if (idx >= 0 && idx < best) best = idx;

        // 2. prefix:local form
        if (prefixes != null) {
            for (var e : prefixes.getNsPrefixMap().entrySet()) {
                String ns = e.getValue();
                if (ns == null || ns.isEmpty()) continue;
                if (!iri.startsWith(ns)) continue;
                String local = iri.substring(ns.length());
                // Local name must be a legal PN_LOCAL start; very loose check: non-empty and not
                // starting with '/' or '#' (those mean we matched a non-namespace prefix by
                // accident, e.g. matching "http://" prefix against the full IRI).
                if (local.isEmpty()) continue;
                if (local.charAt(0) == '/' || local.charAt(0) == '#') continue;
                String prefixed = e.getKey() + ":" + local;
                int hit = findWholeToken(query, prefixed);
                if (hit >= 0 && hit < best) best = hit;
            }
        }

        // 3. 'a' keyword for rdf:type
        if (RDF_TYPE_URI.equals(iri)) {
            int aIdx = findAKeyword(query);
            if (aIdx >= 0 && aIdx < best) best = aIdx;
        }

        return best == Integer.MAX_VALUE ? UNKNOWN : toLineColumn(query, best);
    }

    /**
     * Find the first occurrence of {@code token} that stands as a whole token — i.e. is neither
     * preceded nor followed by a name-continuation character.
     *
     * <p>Without this, the property name {@code cim:ACLineSegment.r} would match inside
     * {@code cim:ACLineSegment.resistance} and the locator would point at the wrong line.</p>
     */
    private static int findWholeToken(String text, String token) {
        int from = 0;
        int idx;
        while ((idx = text.indexOf(token, from)) >= 0) {
            boolean okBefore = idx == 0 || !isNameChar(text.charAt(idx - 1));
            int after = idx + token.length();
            boolean okAfter = after >= text.length() || !isNameChar(text.charAt(after));
            if (okBefore && okAfter) return idx;
            from = idx + 1;
        }
        return -1;
    }

    /** Characters that may continue a SPARQL prefixed name (loose superset of PN_CHARS). */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == '%';
    }

    /**
     * Find the first {@code a} keyword that's preceded by start-of-text, whitespace, {@code .}
     * or {@code ;}. Returns -1 if none found.
     */
    private static int findAKeyword(String query) {
        Matcher m = A_KEYWORD.matcher(query);
        while (m.find()) {
            int start = m.start();
            if (start == 0) return start;
            char prev = query.charAt(start - 1);
            if (Character.isWhitespace(prev) || prev == '.' || prev == ';') return start;
        }
        return -1;
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
