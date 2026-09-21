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

//! The command line this crate builds, and how it reacts to what comes back.
//!
//! These drive a scripted stand-in rather than the real engine, so they pin the contract between
//! the crate and *any* conforming engine — including the exit codes and the failure modes a real
//! one is hard to provoke into.

#![cfg(unix)]

mod common;

use std::time::Duration;

use cimvocabcheck::{
    build_args, tool_version, validate, validate_text, Engine, Error, Options, Strictness,
};
use common::{exclusive, report_json, TempDir};

/// An engine that ignores its arguments and returns what the test told it to.
fn scripted(dir: &TempDir, name: &str, stdout: &str, stderr: &str, code: i32) -> Engine {
    let body = format!(
        "cat <<'REPORT'\n{stdout}\nREPORT\nprintf '%s' {} >&2\nexit {code}",
        shell_quote(stderr)
    );
    Engine::Binary {
        path: dir.script(name, &body),
    }
}

fn shell_quote(text: &str) -> String {
    format!("'{}'", text.replace('\'', r"'\''"))
}

/// An engine that writes its own argv to stdout, so a test can assert on the command line.
fn echoing(dir: &TempDir, name: &str) -> Engine {
    Engine::Binary {
        path: dir.script(name, r#"for a in "$@"; do printf '%s\n' "$a"; done"#),
    }
}

fn argv_of(engine: &Engine, options: &Options) -> Vec<String> {
    let output = std::process::Command::new(match engine {
        Engine::Binary { path } => path,
        _ => unreachable!("the echoing engine is always a binary"),
    })
    .args(build_args(&["q.rq".to_owned()], options))
    .output()
    .expect("run the echoing engine");
    String::from_utf8_lossy(&output.stdout)
        .lines()
        .map(str::to_owned)
        .collect()
}

#[test]
fn the_report_format_and_every_option_reach_the_command_line() {
    let args = build_args(
        &["a.rq".to_owned(), "b.rq".to_owned()],
        &Options::new()
            .schema("profiles")
            .schema("extra.rdf")
            .config("opencgmes.jsonc")
            .endpoint("http://localhost:3030/ds/query")
            .strict_endpoint(true)
            .profile("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0")
            .strictness(Strictness::STRICT),
    );

    assert_eq!(&args[..2], ["--format", "json"]);
    assert!(args.contains(&"--verbose".to_owned()));
    assert_eq!(args.iter().filter(|a| *a == "--schema").count(), 2);
    assert_eq!(
        args[args.iter().position(|a| a == "--config").unwrap() + 1],
        "opencgmes.jsonc"
    );
    assert_eq!(
        args[args.iter().position(|a| a == "--strictness").unwrap() + 1],
        "strict"
    );
    assert!(args.contains(&"--strict-endpoint".to_owned()));
    assert_eq!(&args[args.len() - 3..], ["--", "a.rq", "b.rq"]);
}

#[test]
fn inputs_are_separated_from_options_so_a_leading_dash_is_still_a_file() {
    let args = build_args(&["-weird-name.rq".to_owned()], &Options::new());

    assert_eq!(
        args[args.iter().position(|a| a == "--").unwrap() + 1],
        "-weird-name.rq"
    );
}

#[test]
fn warnings_are_requested_by_default_because_a_library_should_not_drop_findings() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = echoing(&dir, "echoing");

    assert!(argv_of(&engine, &Options::new()).contains(&"--verbose".to_owned()));
}

#[test]
fn verbose_can_be_turned_off_to_match_the_cli_default() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = echoing(&dir, "echoing");

    assert!(!argv_of(&engine, &Options::new().verbose(false)).contains(&"--verbose".to_owned()));
}

#[test]
fn unknown_options_can_be_forwarded_without_a_crate_release() {
    let args = build_args(
        &["q.rq".to_owned()],
        &Options::new().extra_arg("--future-flag").extra_arg("value"),
    );

    assert_eq!(
        args[args.iter().position(|a| a == "--future-flag").unwrap() + 1],
        "value"
    );
}

#[test]
fn findings_are_data_not_an_error() {
    let _guard = exclusive();
    // Exit code 1 means "the input has errors", which is a report, not a tool failure.
    let dir = TempDir::new();
    let document = report_json(
        r#"{ "file": "q.rq", "valid": false, "annotations":
             [{ "severity": "ERROR", "code": "SYNTAX_ERROR", "message": "boom" }] }"#,
    );
    let engine = scripted(&dir, "findings", &document, "", 1);

    let report = validate(&["q.rq"], &Options::new().engine(engine)).expect("a report");

    assert!(!report.ok());
    assert_eq!(report.errors().len(), 1);
}

#[test]
fn a_usage_error_is_raised_with_the_engines_own_complaint() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = scripted(
        &dir,
        "usage",
        "",
        "Error: Unknown strictness level 'bogus'.",
        2,
    );

    let error = validate(
        &["q.rq"],
        &Options::new().engine(engine).strictness("bogus"),
    )
    .expect_err("exit 2 is a tool failure");

    assert!(
        error.to_string().contains("Unknown strictness level"),
        "{error}"
    );
    assert_eq!(error.failure().unwrap().status, Some(2));
}

#[test]
fn an_undocumented_exit_code_is_reported_as_an_engine_failure() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = scripted(&dir, "crash", "", "Exception in thread \"main\"", 137);

    let error = validate(&["q.rq"], &Options::new().engine(engine)).expect_err("137 is a crash");

    assert!(
        error
            .to_string()
            .contains("outside its documented exit codes"),
        "{error}"
    );
}

#[test]
fn unparseable_output_carries_what_the_engine_actually_printed() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = scripted(
        &dir,
        "garbage",
        "not a report at all",
        "warning: something",
        0,
    );

    let error = validate(&["q.rq"], &Options::new().engine(engine)).expect_err("not a report");

    let failure = error.failure().expect("a tool failure");
    assert!(failure.stdout.contains("not a report at all"));
    assert!(failure.stderr.contains("something"));
}

#[test]
fn a_timeout_is_its_own_error() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = Engine::Binary {
        path: dir.script("slow", "sleep 5"),
    };

    let error = validate(
        &["q.rq"],
        &Options::new()
            .engine(engine)
            .timeout(Duration::from_millis(300)),
    )
    .expect_err("the engine never finishes");

    assert!(matches!(error, Error::Timeout(_)), "{error}");
}

#[test]
fn an_engine_that_cannot_be_started_is_reported_clearly() {
    let engine = Engine::Binary {
        path: "/nowhere/does-not-exist".into(),
    };

    let error = validate(&["q.rq"], &Options::new().engine(engine)).expect_err("nothing to run");

    assert!(error.to_string().contains("could not start"), "{error}");
}

#[test]
fn tool_version_reports_the_engine_not_the_crate() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = scripted(&dir, "versioned", "cimvocabcheck 1.4.2", "", 0);

    assert_eq!(
        tool_version(&Options::new().engine(engine)).unwrap(),
        "1.4.2"
    );
}

#[test]
fn text_is_validated_through_stdin() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = Engine::Binary {
        // Echo the query back inside a report, proving it arrived on stdin.
        path: dir.script(
            "stdin",
            r#"read -r line
cat <<REPORT
{"contractVersion":"1.0","tool":{"name":"cimvocabcheck","version":"$line"},
 "summary":{"files":0,"valid":0,"invalid":0,"errors":0,"warnings":0,"infos":0},"results":[]}
REPORT"#,
        ),
    };

    let report = validate_text("SELECT-MARKER", &Options::new().engine(engine)).expect("a report");

    assert_eq!(report.tool.version, "SELECT-MARKER");
}

#[test]
fn the_child_runs_in_the_requested_working_directory() {
    let _guard = exclusive();
    let dir = TempDir::new();
    let engine = Engine::Binary {
        path: dir.script(
            "cwd",
            r#"cat <<REPORT
{"contractVersion":"1.0","tool":{"name":"cimvocabcheck","version":"$(pwd -P)"},
 "summary":{"files":0,"valid":0,"invalid":0,"errors":0,"warnings":0,"infos":0},"results":[]}
REPORT"#,
        ),
    };
    let elsewhere = TempDir::new();

    let report = validate(
        &["q.rq"],
        &Options::new().engine(engine).cwd(elsewhere.path()),
    )
    .expect("a report");

    assert_eq!(report.tool.version, elsewhere.path().display().to_string());
}
