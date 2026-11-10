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

package de.soptim.opencgmes.cimxmlcli.output;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.DatasetGraph;

/**
 * Removes prefixes that would corrupt compacted JSON-LD. Every prefix becomes a term of the
 * {@code @context}; if a term is named like an IRI scheme used in the data ({@code cgmes}, {@code
 * urn}, {@code http}, ...), IRIs of that scheme are read back as compact IRIs and silently change.
 */
public final class PrefixSanitizer {

  private PrefixSanitizer() {}

  /**
   * Removes the dangerous prefixes from the prefix map of {@code dataset}: those named like the
   * scheme of an IRI in the data. With a prefix {@code urn}, for example, {@code urn:uuid:...}
   * would be read back as {@code <prefix IRI>uuid:...}. Names are compared case-insensitively; the
   * data itself is not changed.
   *
   * @param dataset the dataset whose prefix map is changed in place
   * @return the names of the removed prefixes
   */
  public static Set<String> sanitize(DatasetGraph dataset) {
    final var schemes = schemesUsedIn(dataset);
    final var removed = new HashSet<String>();
    for (var prefix : Set.copyOf(dataset.prefixes().getMapping().keySet())) {
      if (schemes.contains(prefix.toLowerCase(Locale.ROOT))) {
        dataset.prefixes().delete(prefix);
        removed.add(prefix);
      }
    }
    return removed;
  }

  private static Set<String> schemesUsedIn(DatasetGraph dataset) {
    final var schemes = new HashSet<String>();
    dataset.listGraphNodes().forEachRemaining(g -> addScheme(g, schemes));
    dataset
        .find()
        .forEachRemaining(
            q -> {
              addScheme(q.getSubject(), schemes);
              addScheme(q.getPredicate(), schemes);
              addScheme(q.getObject(), schemes);
            });
    return schemes;
  }

  private static void addScheme(Node node, Set<String> schemes) {
    if (node.isURI()) {
      final var uri = node.getURI();
      final var colon = uri.indexOf(':');
      if (colon > 0) {
        schemes.add(uri.substring(0, colon).toLowerCase(Locale.ROOT));
      }
    }
  }
}
