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

//! The report document, as Rust types.
//!
//! Unknown fields are collected into `extra` rather than rejected: the contract only
//! ever adds within a major version, so a field this crate has not been regenerated
//! for is still meaningful to a caller that knows about it.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// One finding. Position and term are present whenever the engine could resolve them.
#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Annotation {
    /// Severity after --strictness has been applied. See [`Severity`](crate::Severity) for the
    /// values known to this binding.
    pub severity: String,

    /// Stable identifier of the rule that triggered — the key automation should switch on. The
    /// catalogue is documented at <https://opencgmes.soptim.de/cimvocabcheck/validation-checks>.
    /// New codes are added in minor contract versions, so consumers must treat an unrecognised code
    /// as a generic finding rather than an error. See [`Code`](crate::Code) for the values known to
    /// this binding.
    pub code: String,

    /// Human-readable rendering. Not stable across releases — key automation off "code", never off
    /// this text.
    pub message: String,

    /// 1-based line in the input file. Findings inside embedded SPARQL (sh:select / sh:ask) are
    /// mapped back to their line in the Turtle source. Absent when the engine could not resolve a
    /// position.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub line: Option<u64>,

    /// 1-based column, counted in UTF-16 code units. Absent when unresolved.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub column: Option<u64>,

    /// The offending RDF term (class IRI, property IRI, …). Absent when the finding is not about a
    /// single term.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub term: Option<String>,

    /// Named graph the finding occurred in, under per-graph profile scoping. Absent for
    /// default-graph context.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub graph: Option<String>,

    /// For UNKNOWN_CLASS / UNKNOWN_PROPERTY: profiles in which the term DOES exist. A hint that the
    /// wrong profile is in scope rather than a typo. Absent when empty.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub found_in_other_profiles: Vec<String>,

    /// Fields the engine emitted that this binding does not know about.
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// One validated input and the findings reported against it.
#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileResult {
    /// The input as it was passed on the command line, or "<stdin>" for input read from stdin.
    /// Invoke the CLI with repository-relative paths for findings to line up with a source tree.
    pub file: String,

    /// False when the input has at least one ERROR-severity finding after strictness is applied.
    /// Independent of --verbose.
    pub valid: bool,

    /// Findings for this input; empty when it is clean, or when --verbose is off and only WARN/INFO
    /// findings were produced.
    pub annotations: Vec<Annotation>,

    /// Fields the engine emitted that this binding does not know about.
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// Which engine produced the report.
#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Tool {
    /// Always "cimvocabcheck".
    pub name: String,

    /// Released tool version, or "unknown" when the engine runs from a checkout rather than a
    /// packaged JAR.
    pub version: String,

    /// Fields the engine emitted that this binding does not know about.
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// Counts over this document. The finding counts are taken AFTER the --verbose filter, so they
/// always account for exactly what "results" contains; "valid"/"invalid" are decided before
/// filtering and are unaffected by it.
#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Summary {
    /// Number of validated inputs.
    pub files: u64,

    /// Inputs with no ERROR-severity finding.
    pub valid: u64,

    /// Inputs with at least one ERROR-severity finding.
    pub invalid: u64,

    /// ERROR-severity findings in this document.
    pub errors: u64,

    /// WARN-severity findings in this document.
    pub warnings: u64,

    /// INFO-severity findings in this document.
    pub infos: u64,

    /// Fields the engine emitted that this binding does not know about.
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// The document CIMVocabCheck writes with --format json. This is the published integration
/// contract: non-Java consumers generate their result types from this schema. Within contract major
/// version 1, fields, output formats and diagnostic codes are only ever ADDED — nothing is renamed
/// or removed — so a consumer must ignore properties and enum members it does not know. Breaking
/// changes require a new major (a new file, cimvocabcheck-report-2.schema.json). The SARIF report
/// (--format sarif) follows the OASIS SARIF 2.1.0 schema instead and is not described here.
#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    /// Version of this report contract, "<major>.<minor>" — independent of the tool version. A
    /// consumer should accept any report whose major matches the one it was written against.
    pub contract_version: String,

    /// Which engine produced the report.
    pub tool: Tool,

    /// Counts over this document. The finding counts are taken AFTER the --verbose filter, so they
    /// always account for exactly what "results" contains; "valid"/"invalid" are decided before
    /// filtering and are unaffected by it.
    pub summary: Summary,

    /// One entry per validated input, in the order the inputs were given.
    pub results: Vec<FileResult>,

    /// Fields the engine emitted that this binding does not know about.
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}
