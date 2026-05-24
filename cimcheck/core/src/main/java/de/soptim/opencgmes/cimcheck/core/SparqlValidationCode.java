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

/** Stable codes emitted by {@link SparqlValidationAnnotation}. */
public enum SparqlValidationCode {
    /** Query is not syntactically valid SPARQL. */
    SYNTAX_ERROR,
    /** Class IRI was not found in the selected schema scope. */
    UNKNOWN_CLASS,
    /** Property IRI was not found in the selected schema scope. */
    UNKNOWN_PROPERTY,
    /** Term exists in another, non-selected profile (hint attached to UNKNOWN_*). */
    TERM_EXISTS_IN_OTHER_PROFILE,
    /** A named graph is used by the query but no profiles were configured for it. */
    GRAPH_NOT_CONFIGURED,
    /** A variable predicate / class is used and cannot be validated statically. */
    UNSUPPORTED_DYNAMIC_PROPERTY,
    /** Subject has no explicit {@code rdf:type} but the property implies exactly one domain class. */
    QUERY_IMPLIED_TYPE,
    /** Literal object's datatype is incompatible with the property's {@code rdfs:range}. */
    DATATYPE_MISMATCH,
    /** Property is used on a subject whose type is not a subclass of any declared {@code rdfs:domain}. */
    PROPERTY_NOT_ALLOWED_FOR_CLASS,
    /** {@code sh:nodeKind} value conflicts with the property's {@code rdfs:range} in the schema. */
    NODE_KIND_INCOMPATIBLE_WITH_RANGE,
    /** {@code sh:minCount} exceeds {@code sh:maxCount} on a property shape. */
    INVALID_CARDINALITY
}
