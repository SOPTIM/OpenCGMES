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

//! Validate SPARQL queries and SHACL shapes against CIM/CGMES schema profiles.
//!
//! This crate is a **binding, not an engine**. The analysis — SPARQL parsing, the algebra walk,
//! the CGMES profile model, SHACL — is one Apache Jena implementation shared by every consumer,
//! so a finding you get here is the same finding your editor, your CI job and a colleague's
//! Python script get. What this crate adds is finding that engine, building its command line,
//! and giving you its published report as typed Rust.
//!
//! ```no_run
//! use cimvocabcheck::{validate, Options};
//!
//! let report = validate(
//!     &["queries/line-segments.rq", "shapes/equipment.ttl"],
//!     &Options::new().schema("profiles"),
//! )?;
//!
//! for finding in report.errors() {
//!     println!("{finding}");
//! }
//! # Ok::<(), cimvocabcheck::Error>(())
//! ```
//!
//! Findings are **data, not errors**: a file with errors comes back as a report whose
//! [`Report::ok`] is false. [`Error`] is reserved for "the engine could not run, or could not be
//! trusted".
//!
//! # Batch
//!
//! Loading a CGMES profile set costs about a second; validating one more query after that costs
//! almost nothing. [`validate`] therefore takes a slice — spawning the engine per file turns a
//! one-second job into a one-second-per-file job.
//!
//! # Finding an engine
//!
//! See [`runtime::discover`] for the search order and the environment variables that steer it.
//!
//! # Compatibility
//!
//! This crate accepts any engine declaring the same report contract **major**
//! ([`CONTRACT_MAJOR`]). Within a major the contract only ever adds, so unknown fields are kept
//! in each type's `extra` map and unknown rule codes are accepted —
//! [`Annotation::is_known_code`] tells you which.

#![forbid(unsafe_code)]

mod api;
mod codes;
mod ergonomics;
mod error;
mod model;
pub mod runtime;

pub use api::{
    build_args, is_turtle_input, parse_report, tool_version, validate, validate_text, Options,
    Strictness, EXIT_CLEAN, EXIT_FINDINGS, EXIT_USAGE, STDIN_NAME, TURTLE_SUFFIXES,
};
pub use codes::{Code, Severity, CONTRACT_MAJOR, SCHEMA_ID};
pub use ergonomics::{severity_at_least, Finding};
pub use error::{Error, Result, ToolFailure};
pub use model::{Annotation, FileResult, Report, Summary, Tool};
pub use runtime::{discover, Discovery, Engine};
