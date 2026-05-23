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

import java.util.ArrayList;
import java.util.List;
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
 * <p>The earliest match across all three forms wins. If the term appears multiple times,
 * {@link #locate} returns the first occurrence; {@link #locateWithHint} picks the occurrence
 * that appears closest (by character offset) to a given <em>hint</em> node — typically the
 * subject or object from the same triple pattern.</p>
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
        List<Integer> offsets = findAllTermOffsets(query, term, prefixes);
        return offsets.isEmpty() ? UNKNOWN : toLineColumn(query, offsets.get(0));
    }

    /**
     * Like {@link #locate} but uses {@code hint} (typically the subject node from the same
     * triple pattern) to prefer the occurrence of {@code term} that appears closest in the
     * source text to that hint.
     *
     * <p>The hint may be a SPARQL variable (looked up as {@code ?name} / {@code $name}), a URI
     * node (looked up as full IRI or prefixed form), or a blank node (not searchable — falls
     * back to first occurrence). When the hint is {@code null}, or no occurrences of the hint
     * can be found in the text, the method falls back to the first occurrence of {@code term}.</p>
     *
     * @param query     original query text, may be {@code null}
     * @param term      the URI predicate to locate, may be {@code null}
     * @param prefixes  prefix map from the parsed query, may be {@code null}
     * @param hint      a node from the same triple that disambiguates which occurrence to return
     */
    public static Location locateWithHint(
            String query, Node term, PrefixMapping prefixes, Node hint) {
        if (query == null || term == null || !term.isURI()) return UNKNOWN;

        List<Integer> termOffsets = findAllTermOffsets(query, term, prefixes);
        if (termOffsets.isEmpty()) return UNKNOWN;
        if (termOffsets.size() == 1 || hint == null) return toLineColumn(query, termOffsets.get(0));

        List<Integer> hintOffsets = findAllNodeOffsets(query, hint, prefixes);
        if (hintOffsets.isEmpty()) return toLineColumn(query, termOffsets.get(0));

        // Prefer a term occurrence that lies on the same source line as any hint occurrence.
        // This handles the typical case where subject and predicate share a line.
        for (int hOff : hintOffsets) {
            int hLine = lineOf(query, hOff);
            for (int tOff : termOffsets) {
                if (lineOf(query, tOff) == hLine) return toLineColumn(query, tOff);
            }
        }

        // Fallback: no same-line co-occurrence — pick by minimum character distance.
        int best = termOffsets.get(0);
        int bestDist = minDist(best, hintOffsets);
        for (int off : termOffsets) {
            int d = minDist(off, hintOffsets);
            if (d < bestDist) {
                bestDist = d;
                best = off;
            }
        }
        return toLineColumn(query, best);
    }

    // ---- offset collection -----------------------------------------------------------------

    private static List<Integer> findAllTermOffsets(String query, Node term, PrefixMapping prefixes) {
        String iri = term.getURI();
        var offsets = new ArrayList<Integer>();
        collectAllFullIri(query, iri, offsets);
        if (prefixes != null) {
            for (var e : prefixes.getNsPrefixMap().entrySet()) {
                String ns = e.getValue();
                if (ns == null || ns.isEmpty() || !iri.startsWith(ns)) continue;
                String local = iri.substring(ns.length());
                // Local name must be non-empty and not start with '/' or '#' (which would mean we
                // matched a non-namespace prefix against the full IRI, e.g. "http://").
                if (local.isEmpty() || local.charAt(0) == '/' || local.charAt(0) == '#') continue;
                collectAllWholeToken(query, e.getKey() + ":" + local, offsets);
            }
        }
        if (RDF_TYPE_URI.equals(iri)) collectAllAKeyword(query, offsets);
        offsets.sort(Integer::compare);
        return offsets;
    }

    /**
     * Collects text offsets for {@code hint}: variables as {@code ?name}/{@code $name},
     * URI nodes as full-IRI or prefixed form. Blank nodes and literals are not searchable.
     */
    private static List<Integer> findAllNodeOffsets(String query, Node hint, PrefixMapping prefixes) {
        var offsets = new ArrayList<Integer>();
        if (hint.isVariable()) {
            String name = hint.getName();
            collectAllWholeToken(query, "?" + name, offsets);
            collectAllWholeToken(query, "$" + name, offsets);
        } else if (hint.isURI()) {
            String iri = hint.getURI();
            collectAllFullIri(query, iri, offsets);
            if (prefixes != null) {
                for (var e : prefixes.getNsPrefixMap().entrySet()) {
                    String ns = e.getValue();
                    if (ns == null || ns.isEmpty() || !iri.startsWith(ns)) continue;
                    String local = iri.substring(ns.length());
                    if (local.isEmpty() || local.charAt(0) == '/' || local.charAt(0) == '#') continue;
                    collectAllWholeToken(query, e.getKey() + ":" + local, offsets);
                }
            }
        }
        // Blank nodes, literals: not reliably searchable in source text
        offsets.sort(Integer::compare);
        return offsets;
    }

    private static void collectAllFullIri(String text, String iri, List<Integer> out) {
        String needle = "<" + iri + ">";
        int from = 0, idx;
        while ((idx = text.indexOf(needle, from)) >= 0) {
            if (!isInComment(text, idx)) out.add(idx);
            from = idx + 1;
        }
    }

    private static void collectAllWholeToken(String text, String token, List<Integer> out) {
        int from = 0, idx;
        while ((idx = text.indexOf(token, from)) >= 0) {
            boolean okBefore = idx == 0 || !isNameChar(text.charAt(idx - 1));
            int after = idx + token.length();
            boolean okAfter = after >= text.length() || !isNameChar(text.charAt(after));
            if (okBefore && okAfter && !isInComment(text, idx)) out.add(idx);
            from = idx + 1;
        }
    }

    private static void collectAllAKeyword(String query, List<Integer> out) {
        Matcher m = A_KEYWORD.matcher(query);
        while (m.find()) {
            int start = m.start();
            if (isInComment(query, start)) continue;
            if (start == 0) { out.add(start); continue; }
            char prev = query.charAt(start - 1);
            if (Character.isWhitespace(prev) || prev == '.' || prev == ';') out.add(start);
        }
    }

    private static int minDist(int offset, List<Integer> candidates) {
        int min = Integer.MAX_VALUE;
        for (int c : candidates) {
            int d = Math.abs(offset - c);
            if (d < min) min = d;
        }
        return min;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    // ---- shared utilities ------------------------------------------------------------------

    /** Characters that may continue a SPARQL prefixed name (loose superset of PN_CHARS). */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == '%';
    }

    /**
     * Returns {@code true} when {@code index} falls inside a SPARQL {@code #} line comment.
     *
     * <p>Scans from the start of the line containing {@code index} and tracks single-quoted,
     * double-quoted, and {@code <...>} IRI-bracket states so that a {@code #} inside a string
     * literal or an IRI (e.g. {@code <http://example.org/ns#term>}) does not falsely trigger.</p>
     */
    private static boolean isInComment(String text, int index) {
        int lineStart = index;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;

        boolean inSingle = false;
        boolean inDouble = false;
        boolean inIri    = false;
        for (int i = lineStart; i < index; i++) {
            char c = text.charAt(i);
            if (inIri) {
                if (c == '>') inIri = false;
            } else if (c == '\\' && (inSingle || inDouble)) {
                i++; // skip escaped character inside a string
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '<' && !inSingle && !inDouble) {
                inIri = true;
            } else if (c == '#' && !inSingle && !inDouble) {
                return true;
            }
        }
        return false;
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
