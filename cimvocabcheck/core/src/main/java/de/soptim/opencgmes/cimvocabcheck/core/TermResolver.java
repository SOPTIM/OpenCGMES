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

import de.soptim.opencgmes.cimvocabcheck.core.schema.SchemaIndex;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.apache.jena.graph.Node;

/**
 * Single source of truth for the question "what kind of term is this URI?".
 *
 * <p>The SHACL shape analyzer and the SPARQL query validator both need to decide, for a URI used in
 * class or property position, whether it is a genuine CIM term, a standard-vocabulary term, an open
 * annotation term, a header extension, a locally declared helper, a closed-vocabulary typo, or
 * simply unknown. Historically that decision was a hand-ordered {@code if}-chain copied — with
 * subtle divergences — into four places. This resolver centralises the ordering so every caller
 * classifies terms the same way.
 *
 * <p>The classification order is:
 *
 * <ol>
 *   <li>{@link Classification#HEADER_EXTENSION} — a curated CGMES header term (see {@link
 *       HeaderVocabulary}); accepted even though it lives in a closed namespace.
 *   <li>{@link Classification#KNOWN_STANDARD} — a genuine term of a closed W3C vocabulary
 *       (rdf/rdfs/owl/sh).
 *   <li>{@link Classification#VOCAB_TYPO} — an unknown term in a closed W3C namespace (e.g. {@code
 *       sh:IRII}).
 *   <li>{@link Classification#OPEN_NAMESPACE} — any term in an open annotation/datatype namespace
 *       (xsd, dcterms, skos, the IEC extensions, …).
 *   <li>{@link Classification#LOCAL_DEF} — a URI the analysed document declares itself.
 *   <li>CIM existence, role-dependent: {@link Classification#CIM_CLASS} / {@link
 *       Classification#CIM_PROPERTY} when the schema knows the term in the requested role, {@link
 *       Classification#ENUM_MEMBER} when it is an enumeration value, otherwise {@link
 *       Classification#UNKNOWN}.
 * </ol>
 */
public final class TermResolver {

  /** The kind a caller expects a term to be, which selects the CIM existence check. */
  public enum Role {
    CLASS,
    PROPERTY
  }

  /** The classification of a URI node. See {@link TermResolver} for the decision order. */
  public enum Classification {
    /** A genuine term of a closed W3C vocabulary (rdf/rdfs/owl/sh). */
    KNOWN_STANDARD,
    /** An unknown term in a closed W3C namespace — a vocabulary typo. */
    VOCAB_TYPO,
    /** A term in an open annotation/datatype namespace, accepted wholesale. */
    OPEN_NAMESPACE,
    /** A curated CGMES header extension term (e.g. {@code rdf:Statements.subject}). */
    HEADER_EXTENSION,
    /** A URI the analysed document declares itself. */
    LOCAL_DEF,
    /** A class known to the CIM schema in the requested scope. */
    CIM_CLASS,
    /** A property known to the CIM schema in the requested scope. */
    CIM_PROPERTY,
    /** An enumeration value (member), not a class or property. */
    ENUM_MEMBER,
    /** Unknown to every index. */
    UNKNOWN
  }

  private final SchemaIndex schemaIndex;

  /** Creates a resolver backed by {@code schemaIndex} for the CIM existence checks. */
  public TermResolver(SchemaIndex schemaIndex) {
    this.schemaIndex = Objects.requireNonNull(schemaIndex, "schemaIndex");
  }

  /**
   * Classifies {@code node} in the closed/open/header vocabularies alone, without consulting a
   * schema. Returns {@code null} when the term belongs to none of them (i.e. it is a domain/CIM
   * term or a locally declared one that only a schema-aware {@link #classify} can categorise).
   *
   * <p>Suitable for schema-independent vocabulary-typo checking.
   */
  public static Classification vocabularyClassification(Node node) {
    if (node == null || !node.isURI()) {
      return null;
    }
    if (HeaderVocabulary.isHeaderTerm(node)) {
      return Classification.HEADER_EXTENSION;
    }
    if (StandardVocabulary.isKnownTerm(node)) {
      return Classification.KNOWN_STANDARD;
    }
    if (StandardVocabulary.isClosedNamespace(node)) {
      return Classification.VOCAB_TYPO;
    }
    if (ExemptVocabulary.isOpenNamespace(node)) {
      return Classification.OPEN_NAMESPACE;
    }
    return null;
  }

  /**
   * Classifies {@code node} used in the given {@code role}, consulting the closed/open/header
   * vocabularies, the caller's {@code localDefs}, and finally the CIM schema in {@code scope}.
   *
   * @param localDefs URIs the analysed document declares itself; may be {@code null}
   */
  public Classification classify(
      Node node, Role role, Collection<VersionIri> scope, Set<Node> localDefs) {
    if (node == null || !node.isURI()) {
      return Classification.UNKNOWN;
    }
    Classification vocab = vocabularyClassification(node);
    if (vocab != null) {
      return vocab;
    }
    if (localDefs != null && localDefs.contains(node)) {
      return Classification.LOCAL_DEF;
    }
    boolean exists =
        role == Role.CLASS
            ? schemaIndex.classExists(node, scope)
            : schemaIndex.propertyExists(node, scope);
    if (exists) {
      return role == Role.CLASS ? Classification.CIM_CLASS : Classification.CIM_PROPERTY;
    }
    if (schemaIndex.enumMemberExists(node, null)) {
      return Classification.ENUM_MEMBER;
    }
    return Classification.UNKNOWN;
  }

  /**
   * Returns whether a classification means the term is acceptable in the requested role (no
   * annotation should be emitted). The two "matching" CIM roles are handled by the caller, which
   * knows whether it asked about a class or a property.
   */
  public static boolean isAccepted(Classification c, Role role) {
    return switch (c) {
      case KNOWN_STANDARD, OPEN_NAMESPACE, HEADER_EXTENSION, LOCAL_DEF -> true;
      case CIM_CLASS -> role == Role.CLASS;
      case CIM_PROPERTY -> role == Role.PROPERTY;
      case VOCAB_TYPO, ENUM_MEMBER, UNKNOWN -> false;
    };
  }
}
