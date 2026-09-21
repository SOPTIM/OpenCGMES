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

//! The result model: what the contract obliges a consumer to tolerate, and the ergonomics on top.

mod common;

use cimvocabcheck::{parse_report, severity_at_least, Code, Error, Severity, CONTRACT_MAJOR};
use common::report_json;

const FINDING: &str = r#"{
  "severity": "ERROR",
  "code": "UNKNOWN_CLASS",
  "line": 3,
  "column": 12,
  "term": "http://iec.ch/TC57/CIM100#ACLineSegmentt",
  "graph": "http://example.org/graph",
  "message": "Class does not exist in profile.",
  "foundInOtherProfiles": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"]
}"#;

fn one_result(annotations: &str, valid: bool) -> String {
    report_json(&format!(
        r#"{{ "file": "q.rq", "valid": {valid}, "annotations": [{annotations}] }}"#
    ))
}

#[test]
fn reads_every_documented_field() {
    let report = parse_report(&one_result(FINDING, false)).expect("a valid report");
    let annotation = &report.results[0].annotations[0];

    assert_eq!(annotation.severity, Severity::ERROR);
    assert_eq!(annotation.code, Code::UNKNOWN_CLASS);
    assert_eq!((annotation.line, annotation.column), (Some(3), Some(12)));
    assert!(annotation
        .term
        .as_deref()
        .unwrap()
        .ends_with("ACLineSegmentt"));
    assert_eq!(
        annotation.graph.as_deref(),
        Some("http://example.org/graph")
    );
    assert_eq!(annotation.found_in_other_profiles.len(), 1);
}

#[test]
fn omitted_optional_fields_are_none_not_errors() {
    let report = parse_report(&one_result(
        r#"{ "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "x" }"#,
        true,
    ))
    .expect("a valid report");
    let annotation = &report.results[0].annotations[0];

    assert!(annotation.line.is_none());
    assert!(annotation.column.is_none());
    assert!(annotation.term.is_none());
    assert!(annotation.found_in_other_profiles.is_empty());
}

#[test]
fn unknown_fields_are_kept_rather_than_rejected() {
    // The contract adds fields in minor versions; a consumer must not break on one it predates.
    let document = one_result(
        r#"{ "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "x", "futureField": 7 }"#,
        true,
    );
    let document = document.replace(
        r#""results": ["#,
        r#""futureTopLevel": {"anything": true}, "results": ["#,
    );

    let report = parse_report(&document).expect("a valid report");

    assert!(report.extra.contains_key("futureTopLevel"));
    assert_eq!(
        report.results[0].annotations[0].extra["futureField"],
        serde_json::json!(7)
    );
}

#[test]
fn unknown_code_is_a_finding_not_an_error() {
    // New rule codes ship in minor versions — an unrecognised one must still parse.
    let report = parse_report(&one_result(
        r#"{ "severity": "ERROR", "code": "A_RULE_FROM_THE_FUTURE", "message": "x" }"#,
        false,
    ))
    .expect("a valid report");
    let annotation = &report.results[0].annotations[0];

    assert_eq!(annotation.code, "A_RULE_FROM_THE_FUTURE");
    assert!(annotation.is_error());
    assert!(!annotation.is_known_code());
}

#[test]
fn known_codes_cover_the_published_enum() {
    // Only a checkout has the published schema; a packaged crate ships the generated model alone.
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../schemas/cimvocabcheck-report-1.schema.json");
    let Ok(text) = std::fs::read_to_string(&path) else {
        eprintln!("skipping: {} is not present", path.display());
        return;
    };
    let schema: serde_json::Value = serde_json::from_str(&text).expect("valid JSON");

    let published: Vec<&str> = schema["definitions"]["code"]["enum"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap())
        .collect();

    assert_eq!(Code::ALL.to_vec(), published);
}

#[test]
fn a_missing_required_field_is_a_parse_error() {
    let error = parse_report(&one_result(
        r#"{ "severity": "ERROR", "code": "SYNTAX_ERROR" }"#,
        false,
    ))
    .expect_err("a report without a message is malformed");

    assert!(matches!(error, Error::ReportParse(_)), "{error}");
    assert!(error.to_string().contains("message"), "{error}");
}

#[test]
fn an_incompatible_contract_major_is_refused() {
    let document =
        report_json("").replace(r#""contractVersion": "1.0""#, r#""contractVersion": "2.0""#);

    let error = parse_report(&document).expect_err("contract 2.x is a different document");

    assert!(error
        .to_string()
        .contains(&format!("contract {CONTRACT_MAJOR}.x")));
}

#[test]
fn a_newer_contract_minor_is_accepted() {
    let document =
        report_json("").replace(r#""contractVersion": "1.0""#, r#""contractVersion": "1.7""#);

    assert_eq!(parse_report(&document).unwrap().contract_major(), Some(1));
}

#[test]
fn output_that_is_not_json_is_a_parse_error() {
    let error = parse_report("Exception in thread \"main\"\n").expect_err("not a report");

    assert!(error.to_string().contains("not JSON"), "{error}");
}

#[test]
fn findings_are_flat_and_carry_their_file() {
    let document = report_json(&format!(
        r#"{{ "file": "a.rq", "valid": false, "annotations": [{FINDING}] }},
           {{ "file": "b.rq", "valid": true, "annotations": [
              {{ "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "w" }}] }}"#
    ));

    let report = parse_report(&document).expect("a valid report");

    assert!(!report.ok());
    assert_eq!(report.invalid_files(), vec!["a.rq"]);
    assert_eq!(
        report.findings().map(|f| f.file).collect::<Vec<_>>(),
        vec!["a.rq", "b.rq"]
    );
    assert_eq!(report.errors().len(), 1);
    assert_eq!(report.warnings().len(), 1);
    assert_eq!(report.of_code(Code::UNKNOWN_CLASS)[0].file, "a.rq");
    assert!(report.for_file("b.rq").unwrap().valid);
    assert!(report.for_file("missing.rq").is_none());
}

#[test]
fn a_finding_renders_as_a_compiler_style_line() {
    let report = parse_report(&one_result(FINDING, false)).expect("a valid report");

    assert_eq!(
        report.findings().next().unwrap().to_string(),
        "q.rq:3:12: ERROR[UNKNOWN_CLASS] Class does not exist in profile."
    );
}

#[test]
fn location_omits_positions_the_engine_could_not_resolve() {
    let report = parse_report(&one_result(
        r#"{ "severity": "ERROR", "code": "SYNTAX_ERROR", "message": "x" }"#,
        false,
    ))
    .expect("a valid report");

    assert_eq!(report.results[0].annotations[0].location("q.rq"), "q.rq");
}

#[test]
fn a_severity_floor_selects_everything_at_least_that_severe() {
    let report = parse_report(&one_result(
        &format!(
            r#"{FINDING},
               {{ "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "w" }},
               {{ "severity": "INFO", "code": "QUERY_IMPLIED_TYPE", "message": "i" }}"#
        ),
        false,
    ))
    .expect("a valid report");

    assert_eq!(report.at_least(Severity::WARN).len(), 2);
    assert_eq!(report.at_least(Severity::ERROR).len(), 1);
    assert_eq!(report.at_least(Severity::INFO).len(), 3);
}

#[test]
fn severity_ordering_tolerates_a_severity_it_does_not_know() {
    assert!(severity_at_least(Severity::ERROR, Severity::WARN));
    assert!(!severity_at_least(Severity::INFO, Severity::WARN));
    assert!(!severity_at_least("CATASTROPHE", Severity::WARN));
}
