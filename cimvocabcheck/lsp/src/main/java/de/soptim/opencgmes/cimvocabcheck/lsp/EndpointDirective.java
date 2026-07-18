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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the SPARQL Notebook endpoint magic comment from a query.
 *
 * <p>SPARQL Notebook (Zazuko) configures the query target with a leading comment, e.g.
 *
 * <pre>{@code
 * # [endpoint=https://lindas.admin.ch/query]
 * # [endpoint=./relative/path/file.ttl]
 * # [endpoint=./rdf/{a,b}.ttl]
 * }</pre>
 *
 * <p>Several directives (or a glob pattern) declare a union of local files. Because the directive
 * is a {@code #} comment it is ignored by the SPARQL parser; CIMVocabCheck reads it only to decide
 * <em>which</em> schema to validate the query against (the schema is assumed to be loaded into the
 * endpoint, not the live instance data).
 */
final class EndpointDirective {

  private EndpointDirective() {}

  /** {@code # [endpoint=<value>]} on its own line; the value has no spaces or {@code ]}. */
  private static final Pattern PATTERN =
      Pattern.compile("(?m)^\\s*#\\s*\\[\\s*endpoint\\s*=\\s*([^\\]\\s]+)\\s*\\]");

  /** Returns the first endpoint declared in {@code text}, or empty if none is present. */
  static Optional<String> parse(String text) {
    List<String> all = parseAll(text);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }

  /** Returns every endpoint declared in {@code text}, in order; empty when none is present. */
  static List<String> parseAll(String text) {
    if (text == null) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    Matcher m = PATTERN.matcher(text);
    while (m.find()) {
      values.add(m.group(1).trim());
    }
    return List.copyOf(values);
  }
}
