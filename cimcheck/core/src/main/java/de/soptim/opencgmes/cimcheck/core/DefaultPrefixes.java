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

package de.soptim.opencgmes.cimcheck.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in default PREFIX declarations for SPARQL queries and SPARQL Update requests.
 *
 * <p>When a query contains no PREFIX declaration for a well-known namespace
 * (e.g. {@code rdf:}, {@code cim:}), the validator injects the missing declarations
 * automatically so users do not have to repeat them in every file.</p>
 *
 * <p>The built-in set covers the standard RDF vocabularies and the main CIM 100
 * namespace.  Users can override via {@code "prefixes"} in
 * {@code .cgmes/validation.json}: an explicit object replaces the built-in set
 * entirely; use {@code {}} to disable all defaults.</p>
 */
public final class DefaultPrefixes {

    private DefaultPrefixes() {}

    /** Built-in prefix map: standard RDF vocabularies plus the CIM 100 namespace. */
    public static final Map<String, String> BUILT_IN;
    static {
        var m = new LinkedHashMap<String, String>();
        m.put("rdf",  "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        m.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        m.put("owl",  "http://www.w3.org/2002/07/owl#");
        m.put("xsd",  "http://www.w3.org/2001/XMLSchema#");
        m.put("sh",   "http://www.w3.org/ns/shacl#");
        m.put("cim",  "http://iec.ch/TC57/CIM100#");
        m.put("md",   "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        BUILT_IN = Map.copyOf(m);
    }

    /**
     * Result of a PREFIX injection: the (possibly augmented) query text and the number
     * of PREFIX lines prepended.  When nothing was injected, {@link #injectedLineCount()}
     * is zero and {@link #text()} equals the original.
     */
    public record InjectionResult(String text, int injectedLineCount) {}

    /** Matches {@code PREFIX name:} and {@code @prefix name:} (SPARQL and Turtle forms). */
    private static final Pattern DECLARED =
            Pattern.compile("(?i)(?:@?prefix)\\s+(\\w+):\\s");

    /** Matches {@code line N} position references inside Jena error messages. */
    private static final Pattern LINE_IN_MSG =
            Pattern.compile("(?i)(\\bline )(\\d+)");

    /**
     * Prepends {@code PREFIX} declarations for every prefix in {@code prefixes} that is
     * not already declared anywhere in {@code queryText}.
     *
     * <p>Detection is case-insensitive and covers both {@code PREFIX} (SPARQL) and
     * {@code @prefix} (Turtle) forms, so that SHACL Turtle files are not accidentally
     * augmented when the same code path is reused.</p>
     */
    public static InjectionResult inject(String queryText, Map<String, String> prefixes) {
        if (prefixes.isEmpty()) return new InjectionResult(queryText, 0);

        var declared = new HashSet<String>();
        Matcher m = DECLARED.matcher(queryText);
        while (m.find()) declared.add(m.group(1).toLowerCase(Locale.ROOT));

        var sb = new StringBuilder();
        int count = 0;
        for (var e : prefixes.entrySet()) {
            if (!declared.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                sb.append("PREFIX ").append(e.getKey()).append(": <")
                  .append(e.getValue()).append(">\n");
                count++;
            }
        }
        if (count == 0) return new InjectionResult(queryText, 0);
        return new InjectionResult(sb.append(queryText).toString(), count);
    }

    /**
     * Adjusts the {@code line N} references in a Jena error message by subtracting
     * {@code subtractLines}.  Used to convert positions in the augmented (prefix-injected)
     * query text back to positions in the original query text.
     *
     * <p>Line numbers are floored at 1 so they stay 1-based and valid.</p>
     */
    public static String adjustMessageLines(String message, int subtractLines) {
        if (subtractLines <= 0 || message == null) return message;
        Matcher m = LINE_IN_MSG.matcher(message);
        if (!m.find()) return message;
        var sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            int orig = Integer.parseInt(m.group(2));
            int adj  = Math.max(1, orig - subtractLines);
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + adj));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
