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

package de.soptim.opencgmes.cimvocabcheck.core;

import java.util.Set;
import org.apache.jena.graph.Node;

/**
 * Curated CGMES model-header extension terms that live inside a closed standard namespace but are
 * <em>not</em> genuine terms of that vocabulary.
 *
 * <p>The IEC 61970-552 header (and its 61970-600-2 successor) coins difference-model terms directly
 * under the {@code rdf:} namespace — {@code rdf:Statements}, {@code rdf:Statements.subject}, {@code
 * rdf:Statements.predicate} and {@code rdf:Statements.object}. These appear in official ENTSO-E
 * header shapes inside {@code sh:path} sequences and {@code sh:in} value lists. They are not part
 * of the W3C RDF vocabulary, so {@link StandardVocabulary#isKnownTerm} rightly returns {@code
 * false} for them; treating them as plain closed-namespace typos, however, would produce false
 * positives on every conformant header profile.
 *
 * <p>This class records them as a small, explicit extension set so the term resolver can classify
 * them as header extensions and accept them, while still reporting real typos such as {@code
 * rdf:Statement.subjekt} in the same namespace.
 */
public final class HeaderVocabulary {

  private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

  /** The 61970-552 / 61970-600-2 header terms coined under the {@code rdf:} namespace. */
  private static final Set<String> TERMS =
      Set.of(
          RDF_NS + "Statements",
          RDF_NS + "Statements.subject",
          RDF_NS + "Statements.predicate",
          RDF_NS + "Statements.object");

  private HeaderVocabulary() {}

  /** Returns whether {@code node} is a curated CGMES header extension term. */
  public static boolean isHeaderTerm(Node node) {
    return node != null && node.isURI() && TERMS.contains(node.getURI());
  }
}
