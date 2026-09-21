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

//! End-to-end against a real engine.
//!
//! The scripted tests pin what the crate sends and how it reacts; these pin that a real engine
//! actually answers that way. They run in syntax-only mode so they need no CGMES profile
//! library, and they skip when the machine has no engine at all.

mod common;

use cimvocabcheck::{
    tool_version, validate, validate_text, Code, Error, Options, Severity, STDIN_NAME,
};
use common::{TempDir, BROKEN_QUERY, CLEAN_QUERY, REQUIRE_ENGINE, WARNING_QUERY};

/// A discovered engine that speaks this crate's report contract, or `None` to skip.
///
/// Discovery can land on an engine too old for the contract — most plausibly the published
/// container image, which lags a change made in this repository. That is not a bug in the crate
/// and must not read as one, so the engine is probed and the test skips.
/// `CIMVOCABCHECK_TESTS_REQUIRE_ENGINE` turns the skip into a failure; CI sets it.
fn engine() -> Option<Options> {
    let options = Options::new();
    match validate_text(CLEAN_QUERY, &options) {
        Ok(_) => Some(options),
        Err(error) => {
            let reason = match &error {
                Error::EngineNotFound { .. } => format!("no engine available: {error}"),
                Error::ReportParse(_) => {
                    format!("the discovered engine does not speak the report contract: {error}")
                }
                _ => format!("the discovered engine could not be run: {error}"),
            };
            assert!(
                std::env::var_os(REQUIRE_ENGINE).is_none(),
                "{reason} ({REQUIRE_ENGINE} is set)"
            );
            eprintln!("skipping: {reason}");
            None
        }
    }
}

/// Runs `body` in a fresh syntax-only workspace, or skips when there is no engine.
fn with_engine(body: impl FnOnce(&TempDir, Options)) {
    let Some(options) = engine() else { return };
    let workspace = TempDir::workspace();
    let options = options.cwd(workspace.path());
    body(&workspace, options);
}

#[test]
fn a_clean_query_produces_a_clean_report() {
    with_engine(|workspace, options| {
        let input = workspace.write("clean.rq", CLEAN_QUERY);

        let report = validate(&[input], &options).expect("a report");

        assert!(report.ok());
        assert_eq!(report.summary.files, 1);
        assert_eq!(report.contract_major(), Some(1));
        assert_eq!(report.tool.name, "cimvocabcheck");
        assert_eq!(report.findings().count(), 0);
    });
}

#[test]
fn a_broken_query_comes_back_as_findings() {
    with_engine(|workspace, options| {
        let input = workspace.write("broken.rq", BROKEN_QUERY);

        let report = validate(&[input], &options).expect("a report");

        assert!(!report.ok());
        assert_eq!(report.invalid_files(), vec!["broken.rq"]);
        assert_eq!(report.errors()[0].annotation.code, Code::SYNTAX_ERROR);
        assert_eq!(report.errors()[0].file, "broken.rq");
    });
}

#[test]
fn every_input_is_validated_in_one_run() {
    // The whole point of the batch API: one schema load for all of them.
    with_engine(|workspace, options| {
        let inputs = vec![
            workspace.write("clean.rq", CLEAN_QUERY),
            workspace.write("broken.rq", BROKEN_QUERY),
            workspace.write("warning.rq", WARNING_QUERY),
        ];

        let report = validate(&inputs, &options).expect("a report");

        assert_eq!(
            report
                .results
                .iter()
                .map(|r| r.file.clone())
                .collect::<Vec<_>>(),
            inputs
        );
        assert_eq!(report.summary.files, 3);
        assert_eq!(report.summary.invalid, 1);
    });
}

#[test]
fn the_summary_accounts_for_exactly_what_the_report_contains() {
    with_engine(|workspace, options| {
        let inputs = vec![
            workspace.write("broken.rq", BROKEN_QUERY),
            workspace.write("warning.rq", WARNING_QUERY),
        ];

        let report = validate(&inputs, &options).expect("a report");

        assert_eq!(report.summary.errors, report.errors().len() as u64);
        assert_eq!(report.summary.warnings, report.warnings().len() as u64);
        assert_eq!(report.summary.infos, report.infos().len() as u64);
        assert!(
            report.summary.warnings > 0,
            "the fixture must produce WARN findings"
        );
    });
}

#[test]
fn warnings_are_dropped_when_the_caller_asks_for_the_cli_default() {
    with_engine(|workspace, options| {
        let input = workspace.write("warning.rq", WARNING_QUERY);

        let verbose = validate(std::slice::from_ref(&input), &options).expect("a report");
        let quiet = validate(&[input], &options.clone().verbose(false)).expect("a report");

        assert!(!verbose.warnings().is_empty());
        assert!(quiet.warnings().is_empty());
        assert_eq!(quiet.summary.warnings, 0);
    });
}

#[test]
fn strictness_promotes_warnings_to_errors() {
    with_engine(|workspace, options| {
        let input = workspace.write("warning.rq", WARNING_QUERY);

        let default = validate(std::slice::from_ref(&input), &options).expect("a report");
        let strict = validate(&[input], &options.clone().strictness("strict")).expect("a report");

        assert!(default.ok());
        assert!(!strict.ok());
        assert!(strict
            .findings()
            .all(|f| f.annotation.severity == Severity::ERROR));
    });
}

#[test]
fn a_query_in_memory_is_validated_over_stdin() {
    with_engine(|_workspace, options| {
        let report = validate_text(BROKEN_QUERY, &options).expect("a report");

        assert!(!report.ok());
        assert_eq!(report.results[0].file, STDIN_NAME);
    });
}

#[test]
fn a_path_with_a_space_survives() {
    // The reason this crate never builds a shell string.
    with_engine(|workspace, options| {
        let input = workspace.write("my queries/a b.rq", CLEAN_QUERY);

        let report = validate(&[input], &options).expect("a report");

        assert!(report.ok());
        assert_eq!(report.results[0].file, "my queries/a b.rq");
    });
}

#[test]
fn a_shacl_shape_file_is_validated_as_turtle() {
    with_engine(|workspace, options| {
        let input = workspace.write("shapes.ttl", "this is not turtle at all\n");

        let report = validate(&[input], &options).expect("a report");

        assert!(!report.ok());
    });
}

#[test]
fn a_configuration_error_is_raised_rather_than_returned() {
    with_engine(|workspace, options| {
        let input = workspace.write("clean.rq", CLEAN_QUERY);

        let error = validate(&[input], &options.clone().strictness("bogus"))
            .expect_err("the engine rejects an unknown strictness");

        assert!(
            error.to_string().to_lowercase().contains("strictness"),
            "{error}"
        );
    });
}

#[test]
fn the_crate_reports_the_engine_version() {
    with_engine(|_workspace, options| {
        assert!(!tool_version(&options).expect("a version").is_empty());
    });
}
