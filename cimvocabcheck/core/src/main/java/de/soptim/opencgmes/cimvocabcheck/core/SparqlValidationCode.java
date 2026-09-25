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

/** Stable codes emitted by {@link SparqlValidationAnnotation}. */
public enum SparqlValidationCode {
  /** Query is not syntactically valid SPARQL. */
  SYNTAX_ERROR,
  /** Class IRI was not found in the selected schema scope. */
  UNKNOWN_CLASS,
  /** Property IRI was not found in the selected schema scope. */
  UNKNOWN_PROPERTY,
  /**
   * Term in a closed standard vocabulary ({@code rdf}/{@code rdfs}/{@code owl}/{@code sh}) that the
   * vocabulary does not define — almost always a typo (e.g. {@code rdf:typ}).
   */
  UNKNOWN_VOCABULARY_TERM,
  /** A named graph is used by the query but no profiles were configured for it. */
  GRAPH_NOT_CONFIGURED,
  /** A variable predicate / class is used and cannot be validated statically. */
  UNSUPPORTED_DYNAMIC_PROPERTY,
  /** Subject has no explicit {@code rdf:type} but the property implies exactly one domain class. */
  QUERY_IMPLIED_TYPE,
  /** Literal object's datatype is incompatible with the property's {@code rdfs:range}. */
  DATATYPE_MISMATCH,
  /**
   * Property is used on a subject whose type is not a subclass of any declared {@code rdfs:domain}.
   */
  PROPERTY_NOT_ALLOWED_FOR_CLASS,
  /** {@code sh:nodeKind} value conflicts with the property's {@code rdfs:range} in the schema. */
  NODE_KIND_INCOMPATIBLE_WITH_RANGE,
  /**
   * {@code sh:datatype} is used on a property whose {@code rdfs:range} is a class (object
   * property).
   */
  DATATYPE_INCOMPATIBLE_WITH_RANGE,
  /**
   * {@code sh:class} is used on a property whose {@code rdfs:range} is a literal datatype (datatype
   * property).
   */
  CLASS_INCOMPATIBLE_WITH_RANGE,
  /** {@code sh:minCount} exceeds {@code sh:maxCount} on a property shape. */
  INVALID_CARDINALITY,
  /**
   * Object IRI of a property whose {@code rdfs:range} is an enumeration is not one of that
   * enumeration's members — almost always a typo (e.g. {@code cim:WindGenUnitKind.offshroe}).
   */
  INVALID_ENUM_VALUE,
  /**
   * A value-range constraint is self-contradictory — e.g. {@code sh:minInclusive} exceeds {@code
   * sh:maxInclusive} — so the property shape can never be satisfied.
   */
  INVALID_VALUE_RANGE,
  /**
   * A constant IRI used in a {@code FILTER}, {@code VALUES} or {@code BIND} expression is unknown
   * to every schema index (class, property and enumeration member). Reported as a warning because
   * such a constant can legitimately be an instance IRI the schema does not track.
   */
  UNKNOWN_TERM_IN_EXPRESSION,
  /**
   * A property whose CIM {@code cims:multiplicity} allows zero values is matched in the query's
   * mandatory clause. Because a triple pattern outside {@code OPTIONAL} is an inner join, every
   * solution whose subject does not carry that optional attribute is silently dropped. Reported as
   * a hint because requiring an optional property is often intentional.
   */
  PROPERTY_MAY_BE_ABSENT,
  /**
   * A SHACL {@code sh:minCount}/{@code sh:maxCount} is incompatible with the property's declared
   * CIM {@code cims:multiplicity} — e.g. requiring more values than the schema's upper bound
   * allows, so the constraint can never be satisfied against conformant data.
   */
  CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY,
  /**
   * A variable in the result surface (explicit {@code SELECT} list, {@code CONSTRUCT} template,
   * {@code DESCRIBE} list) that appears nowhere in the query body ({@code WHERE}/{@code
   * BIND}/{@code VALUES}) — it is unbound in every result, almost always a typo (e.g. {@code ?nmae}
   * vs {@code ?name}).
   */
  PROJECTED_VARIABLE_UNBOUND,
  /**
   * A variable bound in a triple pattern, {@code BIND} or {@code VALUES} that is never projected,
   * filtered on, ordered/grouped by, or otherwise reused — a typo or a leftover from a query edit.
   */
  UNUSED_VARIABLE
}
