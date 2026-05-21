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

package de.soptim.opencgmes.sparql.validation;

/**
 * Stable codes for {@link SparqlValidationAnnotation}s. Phase 1 only emits the codes whose
 * documentation says so; the remaining codes are reserved for the planned Phase&nbsp;3
 * semantic checks so consumers can already pattern-match against them.
 */
public enum SparqlValidationCode {
    /** Query is not syntactically valid SPARQL. */
    SYNTAX_ERROR,
    /** Class IRI was not found in the selected schema scope. */
    UNKNOWN_CLASS,
    /** Property IRI was not found in the selected schema scope. */
    UNKNOWN_PROPERTY,
    /** Reserved: term exists in another, non-selected profile (hint is attached to UNKNOWN_*). */
    TERM_EXISTS_IN_OTHER_PROFILE,
    /** A named graph is used by the query but no profiles were configured for it. */
    GRAPH_NOT_CONFIGURED,
    /** A variable predicate / class is used and cannot be validated statically. */
    UNSUPPORTED_DYNAMIC_PROPERTY,
    /** Reserved for Phase&nbsp;3: implied {@code rdf:type} of a constant subject/object. */
    QUERY_IMPLIED_TYPE,
    /** Reserved for Phase&nbsp;3: literal datatype does not match the property's range. */
    DATATYPE_MISMATCH,
    /** Reserved for Phase&nbsp;3: property is not allowed for the class it is used on. */
    PROPERTY_NOT_ALLOWED_FOR_CLASS
}
