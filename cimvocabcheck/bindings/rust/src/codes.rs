//    Copyright (c) 2026 SOPTIM AG
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
//    SPDX-License-Identifier: Apache-2.0

// -----------------------------------------------------------------------------------------
// GENERATED FILE - DO NOT EDIT.
// Produced from cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json
// by cimvocabcheck/bindings/codegen/generate_models.py.
// Conveniences belong in ergonomics.rs, which survives regeneration.
// -----------------------------------------------------------------------------------------

/// Report contract major this binding targets; any report declaring it is accepted.
pub const CONTRACT_MAJOR: u64 = 1;

/// Canonical URL of the schema these types were generated from.
pub const SCHEMA_ID: &str = concat!(
    "https://raw.githubusercontent.com/SOPTIM/OpenCGMES/main/cimvocabcheck/schemas/",
    "cimvocabcheck-report-1.schema.json",
);

/// Severity after --strictness has been applied.
/// Constants rather than an enum: the contract adds members in minor versions and obliges a
/// consumer to tolerate one it does not know.
pub struct Severity;

impl Severity {
    /// The `ERROR` severity.
    pub const ERROR: &'static str = "ERROR";

    /// The `WARN` severity.
    pub const WARN: &'static str = "WARN";

    /// The `INFO` severity.
    pub const INFO: &'static str = "INFO";

    /// Every severity this binding knows, in schema order.
    pub const ALL: [&'static str; 3] = [Self::ERROR, Self::WARN, Self::INFO];
}

/// Stable identifier of the rule that triggered — the key automation should switch on. The
/// catalogue is documented at <https://opencgmes.soptim.de/cimvocabcheck/validation-checks>. New
/// codes are added in minor contract versions, so consumers must treat an unrecognised code as a
/// generic finding rather than an error.
/// Constants rather than an enum: the contract adds members in minor versions and obliges a
/// consumer to tolerate one it does not know.
pub struct Code;

impl Code {
    /// The `SYNTAX_ERROR` code.
    pub const SYNTAX_ERROR: &'static str = "SYNTAX_ERROR";

    /// The `UNKNOWN_CLASS` code.
    pub const UNKNOWN_CLASS: &'static str = "UNKNOWN_CLASS";

    /// The `UNKNOWN_PROPERTY` code.
    pub const UNKNOWN_PROPERTY: &'static str = "UNKNOWN_PROPERTY";

    /// The `UNKNOWN_VOCABULARY_TERM` code.
    pub const UNKNOWN_VOCABULARY_TERM: &'static str = "UNKNOWN_VOCABULARY_TERM";

    /// The `GRAPH_NOT_CONFIGURED` code.
    pub const GRAPH_NOT_CONFIGURED: &'static str = "GRAPH_NOT_CONFIGURED";

    /// The `UNSUPPORTED_DYNAMIC_PROPERTY` code.
    pub const UNSUPPORTED_DYNAMIC_PROPERTY: &'static str = "UNSUPPORTED_DYNAMIC_PROPERTY";

    /// The `QUERY_IMPLIED_TYPE` code.
    pub const QUERY_IMPLIED_TYPE: &'static str = "QUERY_IMPLIED_TYPE";

    /// The `DATATYPE_MISMATCH` code.
    pub const DATATYPE_MISMATCH: &'static str = "DATATYPE_MISMATCH";

    /// The `PROPERTY_NOT_ALLOWED_FOR_CLASS` code.
    pub const PROPERTY_NOT_ALLOWED_FOR_CLASS: &'static str = "PROPERTY_NOT_ALLOWED_FOR_CLASS";

    /// The `NODE_KIND_INCOMPATIBLE_WITH_RANGE` code.
    pub const NODE_KIND_INCOMPATIBLE_WITH_RANGE: &'static str = "NODE_KIND_INCOMPATIBLE_WITH_RANGE";

    /// The `DATATYPE_INCOMPATIBLE_WITH_RANGE` code.
    pub const DATATYPE_INCOMPATIBLE_WITH_RANGE: &'static str = "DATATYPE_INCOMPATIBLE_WITH_RANGE";

    /// The `CLASS_INCOMPATIBLE_WITH_RANGE` code.
    pub const CLASS_INCOMPATIBLE_WITH_RANGE: &'static str = "CLASS_INCOMPATIBLE_WITH_RANGE";

    /// The `INVALID_CARDINALITY` code.
    pub const INVALID_CARDINALITY: &'static str = "INVALID_CARDINALITY";

    /// The `INVALID_ENUM_VALUE` code.
    pub const INVALID_ENUM_VALUE: &'static str = "INVALID_ENUM_VALUE";

    /// The `INVALID_VALUE_RANGE` code.
    pub const INVALID_VALUE_RANGE: &'static str = "INVALID_VALUE_RANGE";

    /// The `UNKNOWN_TERM_IN_EXPRESSION` code.
    pub const UNKNOWN_TERM_IN_EXPRESSION: &'static str = "UNKNOWN_TERM_IN_EXPRESSION";

    /// The `CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY` code.
    pub const CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY: &'static str =
        "CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY";

    /// The `PROJECTED_VARIABLE_UNBOUND` code.
    pub const PROJECTED_VARIABLE_UNBOUND: &'static str = "PROJECTED_VARIABLE_UNBOUND";

    /// The `UNUSED_VARIABLE` code.
    pub const UNUSED_VARIABLE: &'static str = "UNUSED_VARIABLE";

    /// Every code this binding knows, in schema order.
    pub const ALL: [&'static str; 19] = [
        Self::SYNTAX_ERROR,
        Self::UNKNOWN_CLASS,
        Self::UNKNOWN_PROPERTY,
        Self::UNKNOWN_VOCABULARY_TERM,
        Self::GRAPH_NOT_CONFIGURED,
        Self::UNSUPPORTED_DYNAMIC_PROPERTY,
        Self::QUERY_IMPLIED_TYPE,
        Self::DATATYPE_MISMATCH,
        Self::PROPERTY_NOT_ALLOWED_FOR_CLASS,
        Self::NODE_KIND_INCOMPATIBLE_WITH_RANGE,
        Self::DATATYPE_INCOMPATIBLE_WITH_RANGE,
        Self::CLASS_INCOMPATIBLE_WITH_RANGE,
        Self::INVALID_CARDINALITY,
        Self::INVALID_ENUM_VALUE,
        Self::INVALID_VALUE_RANGE,
        Self::UNKNOWN_TERM_IN_EXPRESSION,
        Self::CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY,
        Self::PROJECTED_VARIABLE_UNBOUND,
        Self::UNUSED_VARIABLE,
    ];
}
