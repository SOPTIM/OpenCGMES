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
 * Non-standard terms the IEC 61970-552 model header coins inside the closed {@code rdf:} namespace.
 *
 * <p>The 552 header RDFS (and the official ENTSO-E header shapes generated from it) defines {@code
 * rdf:Statements}, {@code rdf:Statements.subject}, {@code rdf:Statements.predicate} and {@code
 * rdf:Statements.object}. They are not part of the W3C RDF vocabulary: they are an artefact of the
 * UML-to-RDFS generator, which could not express the intended standard reification terms. They also
 * never occur in instance data — CIMXML difference models carry plain triples inside {@code
 * rdf:parseType="Statements"} containers — so a query pattern or shape path naming one can never
 * match.
 *
 * <p>Reporting them as closed-namespace typos would be the wrong diagnosis on every unmodified
 * header profile, so they get their own {@link SparqlValidationCode#NON_STANDARD_HEADER_TERM}
 * warning instead. Real typos in the same namespace (e.g. {@code rdf:Statement.subjekt}) are still
 * reported as {@link SparqlValidationCode#UNKNOWN_VOCABULARY_TERM}.
 */
public final class HeaderVocabulary {

  private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

  private static final Set<String> TERMS =
      Set.of(
          RDF_NS + "Statements",
          RDF_NS + "Statements.subject",
          RDF_NS + "Statements.predicate",
          RDF_NS + "Statements.object");

  private HeaderVocabulary() {}

  /** Returns whether {@code node} is one of the non-standard 552 header terms. */
  public static boolean isHeaderTerm(Node node) {
    return node != null && node.isURI() && TERMS.contains(node.getURI());
  }

  /** The {@link SparqlValidationCode#NON_STANDARD_HEADER_TERM} message for {@code term}. */
  public static String message(Node term) {
    return "<"
        + term.getURI()
        + "> is not an RDF term; it exists only in the IEC 61970-552 header RDFS. Difference"
        + " models carry plain triples inside rdf:parseType=\"Statements\", so no data uses this"
        + " term and this can never match.";
  }
}
