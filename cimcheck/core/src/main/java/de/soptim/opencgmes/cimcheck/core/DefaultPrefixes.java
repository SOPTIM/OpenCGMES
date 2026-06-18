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

import de.soptim.opencgmes.cimcheck.core.schema.SchemaIndex;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jena.graph.Node;

/**
 * Built-in default PREFIX declarations for SPARQL queries and SPARQL Update requests.
 *
 * <p>When a query contains no PREFIX declaration for a well-known namespace (e.g. {@code rdf:},
 * {@code cim:}), the validator injects the missing declarations automatically so users do not have
 * to repeat them in every file.
 *
 * <p>The built-in set covers the standard RDF vocabularies and the main CIM 100 namespace. Users
 * can override via {@code "prefixes"} in {@code opencgmes.json}: an explicit object replaces the
 * built-in set entirely; use {@code {}} to disable all defaults.
 */
public final class DefaultPrefixes {

  private DefaultPrefixes() {}

  /** Built-in prefix map: standard RDF vocabularies plus the CIM 100 namespace. */
  public static final Map<String, String> BUILT_IN;

  static {
    var m = new LinkedHashMap<String, String>();
    m.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
    m.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
    m.put("owl", "http://www.w3.org/2002/07/owl#");
    m.put("xsd", "http://www.w3.org/2001/XMLSchema#");
    m.put("sh", "http://www.w3.org/ns/shacl#");
    m.put("cim", "http://iec.ch/TC57/CIM100#");
    m.put("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
    BUILT_IN = Map.copyOf(m);
  }

  /**
   * Well-known standard-vocabulary namespaces that are never the CIM domain namespace. Any IRI
   * whose namespace prefix is in this set is excluded from CIM-namespace detection.
   */
  private static final Set<String> STANDARD_NAMESPACES =
      Set.of(
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
          "http://www.w3.org/2000/01/rdf-schema#",
          "http://www.w3.org/2002/07/owl#",
          "http://www.w3.org/2001/XMLSchema#",
          "http://www.w3.org/ns/shacl#",
          "http://iec.ch/TC57/61970-552/ModelDescription/1#");

  /**
   * Scans all class and property IRIs in {@code index}, tallies their namespace prefixes (the part
   * up to and including the last {@code #}), filters out well-known standard vocabularies, and
   * returns the dominant CIM namespace if one clearly emerges.
   *
   * <p>A namespace is considered dominant when it accounts for at least ten terms <em>and</em> has
   * more than twice as many terms as the next-most-frequent non-standard namespace. This threshold
   * is intentionally high so that mixed-version schemas (e.g., CGMES 2.4.15 and 3.0 profiles loaded
   * together) correctly return {@link Optional#empty()}, leaving the {@code cim:} prefix to the
   * user's own configuration.
   */
  public static Optional<String> detectCimNamespace(SchemaIndex index) {
    var freq = new HashMap<String, Integer>();
    for (Node n : index.allClasses()) {
      tally(n, freq);
    }
    for (Node n : index.allProperties()) {
      tally(n, freq);
    }
    if (freq.isEmpty()) {
      return Optional.empty();
    }

    String best = null;
    int bestCount = 0;
    int secondCount = 0;
    for (var e : freq.entrySet()) {
      if (e.getValue() > bestCount) {
        secondCount = bestCount;
        bestCount = e.getValue();
        best = e.getKey();
      } else if (e.getValue() > secondCount) {
        secondCount = e.getValue();
      }
    }
    return (bestCount >= 10 && bestCount > secondCount * 2) ? Optional.of(best) : Optional.empty();
  }

  /**
   * Returns a copy of {@code base} with the {@code "cim"} entry overridden by the namespace
   * detected from {@code index}, or {@code base} unchanged when detection finds no clear winner or
   * the detected namespace is already the one in {@code base}.
   */
  public static Map<String, String> withDetectedCimPrefix(
      Map<String, String> base, SchemaIndex index) {
    return detectCimNamespace(index)
        .filter(ns -> !ns.equals(base.get("cim")))
        .map(
            ns -> {
              var m = new LinkedHashMap<>(base);
              m.put("cim", ns);
              return Map.<String, String>copyOf(m);
            })
        .orElse(base);
  }

  private static void tally(Node node, Map<String, Integer> freq) {
    if (!node.isURI()) {
      return;
    }
    String ns = namespace(node.getURI());
    if (ns == null || STANDARD_NAMESPACES.contains(ns)) {
      return;
    }
    freq.merge(ns, 1, Integer::sum);
  }

  private static String namespace(String uri) {
    int hash = uri.lastIndexOf('#');
    if (hash > 0) {
      return uri.substring(0, hash + 1);
    }
    int slash = uri.lastIndexOf('/');
    if (slash > 0) {
      return uri.substring(0, slash + 1);
    }
    return null;
  }

  /**
   * Result of a PREFIX injection: the (possibly augmented) query text and the number of PREFIX
   * lines prepended. When nothing was injected, {@link #injectedLineCount()} is zero and {@link
   * #text()} equals the original.
   */
  public record InjectionResult(String text, int injectedLineCount) {}

  /** Matches {@code PREFIX name:} and {@code @prefix name:} (SPARQL and Turtle forms). */
  private static final Pattern DECLARED = Pattern.compile("(?i)(?:@?prefix)\\s+(\\w+):\\s");

  /** Matches PREFIX/@prefix declarations and captures both the name and the namespace IRI. */
  private static final Pattern PREFIX_DECL =
      Pattern.compile("(?i)(?:@?prefix)\\s+(\\w+):\\s*<([^>]*)>");

  /** Matches {@code line N} position references inside Jena error messages. */
  private static final Pattern LINE_IN_MSG = Pattern.compile("(?i)(\\bline )(\\d+)");

  /**
   * Prepends {@code PREFIX} declarations for every prefix in {@code prefixes} that is not already
   * declared anywhere in {@code queryText}.
   *
   * <p>Detection is case-insensitive and covers both {@code PREFIX} (SPARQL) and {@code @prefix}
   * (Turtle) forms, so that SHACL Turtle files are not accidentally augmented when the same code
   * path is reused.
   */
  public static InjectionResult inject(String queryText, Map<String, String> prefixes) {
    if (prefixes.isEmpty()) {
      return new InjectionResult(queryText, 0);
    }

    var declared = new HashSet<String>();
    Matcher m = DECLARED.matcher(queryText);
    while (m.find()) {
      declared.add(m.group(1).toLowerCase(Locale.ROOT));
    }

    var sb = new StringBuilder();
    int count = 0;
    for (var e : prefixes.entrySet()) {
      if (!declared.contains(e.getKey().toLowerCase(Locale.ROOT))) {
        sb.append("PREFIX ").append(e.getKey()).append(": <").append(e.getValue()).append(">\n");
        count++;
      }
    }
    if (count == 0) {
      return new InjectionResult(queryText, 0);
    }
    return new InjectionResult(sb.append(queryText).toString(), count);
  }

  /**
   * Extracts all {@code PREFIX}/{@code @prefix} declarations from {@code text} and returns them as
   * a prefix-name → namespace-IRI map. Both SPARQL ({@code PREFIX name: <iri>}) and Turtle
   * ({@code @prefix name: <iri>}) forms are recognised. Malformed declarations (missing {@code
   * <...>}) are silently skipped.
   *
   * <p>Useful for clients that need to resolve prefixed names in partially-valid documents without
   * a full parse.
   */
  public static Map<String, String> declaredPrefixes(String text) {
    if (text == null) {
      return Map.of();
    }
    var result = new LinkedHashMap<String, String>();
    Matcher m = PREFIX_DECL.matcher(text);
    while (m.find()) {
      result.put(m.group(1), m.group(2));
    }
    return Map.copyOf(result);
  }

  /**
   * Adjusts the {@code line N} references in a Jena error message by subtracting {@code
   * subtractLines}. Used to convert positions in the augmented (prefix-injected) query text back to
   * positions in the original query text.
   *
   * <p>Line numbers are floored at 1 so they stay 1-based and valid.
   */
  public static String adjustMessageLines(String message, int subtractLines) {
    if (subtractLines <= 0 || message == null) {
      return message;
    }
    Matcher m = LINE_IN_MSG.matcher(message);
    if (!m.find()) {
      return message;
    }
    var sb = new StringBuilder();
    m.reset();
    while (m.find()) {
      int orig = Integer.parseInt(m.group(2));
      int adj = Math.max(1, orig - subtractLines);
      m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + adj));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
