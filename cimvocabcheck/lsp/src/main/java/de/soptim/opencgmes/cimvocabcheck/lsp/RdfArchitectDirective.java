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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code # [rdfarchitect=...]} magic comment: validate this document against the model
 * held in a running RDFArchitect, rather than against the workspace's schema files.
 *
 * <pre>{@code
 * # [rdfarchitect=http://localhost:3000/?snapshot=ffPKWuq2hw8WKBRn5VwEOA]
 * }</pre>
 *
 * <p>Deliberately a directive of its own rather than a value of {@code # [endpoint=...]}: the
 * endpoint directive is SPARQL Notebook's, and it executes queries against whatever it names, so an
 * RDFArchitect URL there would break the notebook. An unknown directive is just a comment to it.
 *
 * <p>When both are present, {@code rdfarchitect} wins — it is the more specific statement about
 * where the <em>schema</em> comes from, while {@code endpoint} also says where queries run.
 */
final class RdfArchitectDirective {

  /** Marks a resolved schema source as an RDFArchitect link, for {@link SchemaManager}. */
  static final String SCHEME = "rdfarchitect:";

  private RdfArchitectDirective() {}

  /** {@code # [rdfarchitect=<value>]} on its own line; the value has no spaces or {@code ]}. */
  private static final Pattern PATTERN =
      Pattern.compile("(?m)^\\s*#\\s*\\[\\s*rdfarchitect\\s*=\\s*([^\\]\\s]+)\\s*\\]");

  /** Returns the first RDFArchitect link declared in {@code text}, or empty if none is present. */
  static Optional<String> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher m = PATTERN.matcher(text);
    return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
  }
}
