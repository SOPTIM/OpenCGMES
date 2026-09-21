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

//! Hand-written behaviour on the generated report types.
//!
//! Inherent impls may live in any module of the defining crate, so this file adds the
//! conveniences without the generated `model.rs` knowing anything about them.

use std::fmt;

use crate::codes::{Code, Severity};
use crate::model::{Annotation, FileResult, Report};

/// One annotation together with the input it was reported against.
///
/// The report nests annotations under their file; most callers want them flat and still need to
/// know where each came from.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Finding<'a> {
    /// The input this was reported against, exactly as it was passed to the engine.
    pub file: &'a str,
    /// The finding itself.
    pub annotation: &'a Annotation,
}

impl fmt::Display for Finding<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let a = self.annotation;
        write!(
            f,
            "{}: {}[{}] {}",
            a.location(self.file),
            a.severity,
            a.code,
            a.message
        )
    }
}

/// True when `severity` is at least as severe as `floor`.
///
/// An unknown severity sorts below INFO rather than panicking: the contract may grow, and a
/// consumer that falls over on an unrecognised value is worse than one that under-reports it.
#[must_use]
pub fn severity_at_least(severity: &str, floor: &str) -> bool {
    let rank = |value: &str| Severity::ALL.iter().rev().position(|s| *s == value);
    match (rank(severity), rank(floor)) {
        (Some(actual), Some(required)) => actual >= required,
        _ => false,
    }
}

impl Annotation {
    /// True for an ERROR-severity finding.
    #[must_use]
    pub fn is_error(&self) -> bool {
        self.severity == Severity::ERROR
    }

    /// True for a WARN-severity finding.
    #[must_use]
    pub fn is_warning(&self) -> bool {
        self.severity == Severity::WARN
    }

    /// True for an INFO-severity finding.
    #[must_use]
    pub fn is_info(&self) -> bool {
        self.severity == Severity::INFO
    }

    /// False for a code added by an engine newer than this crate.
    ///
    /// Such a finding is still a valid finding — the contract adds codes in minor versions and
    /// requires consumers to treat an unknown one as a generic finding, never as an error.
    #[must_use]
    pub fn is_known_code(&self) -> bool {
        Code::ALL.contains(&self.code.as_str())
    }

    /// Renders `file:line:col`, omitting the parts the engine could not resolve.
    #[must_use]
    pub fn location(&self, file: &str) -> String {
        match (self.line, self.column) {
            (Some(line), Some(column)) => format!("{file}:{line}:{column}"),
            (Some(line), None) => format!("{file}:{line}"),
            _ => file.to_owned(),
        }
    }
}

impl FileResult {
    /// This input's findings, each carrying the file it came from.
    pub fn findings(&self) -> impl Iterator<Item = Finding<'_>> {
        self.annotations.iter().map(move |annotation| Finding {
            file: &self.file,
            annotation,
        })
    }
}

impl Report {
    /// The contract major this document declares — what compatibility is judged on.
    #[must_use]
    pub fn contract_major(&self) -> Option<u64> {
        self.contract_version.split('.').next()?.parse().ok()
    }

    /// True when no input has an ERROR-severity finding.
    #[must_use]
    pub fn ok(&self) -> bool {
        self.results.iter().all(|result| result.valid)
    }

    /// All findings across all inputs, in the order the inputs were given.
    pub fn findings(&self) -> impl Iterator<Item = Finding<'_>> {
        self.results.iter().flat_map(FileResult::findings)
    }

    /// The findings of one severity.
    #[must_use]
    pub fn of_severity<'a>(&'a self, severity: &str) -> Vec<Finding<'a>> {
        self.findings()
            .filter(|f| f.annotation.severity == severity)
            .collect()
    }

    /// The findings of one rule code.
    #[must_use]
    pub fn of_code<'a>(&'a self, code: &str) -> Vec<Finding<'a>> {
        self.findings()
            .filter(|f| f.annotation.code == code)
            .collect()
    }

    /// Every finding at least as severe as `floor`.
    #[must_use]
    pub fn at_least<'a>(&'a self, floor: &str) -> Vec<Finding<'a>> {
        self.findings()
            .filter(|f| severity_at_least(&f.annotation.severity, floor))
            .collect()
    }

    /// The ERROR-severity findings.
    #[must_use]
    pub fn errors(&self) -> Vec<Finding<'_>> {
        self.of_severity(Severity::ERROR)
    }

    /// The WARN-severity findings.
    #[must_use]
    pub fn warnings(&self) -> Vec<Finding<'_>> {
        self.of_severity(Severity::WARN)
    }

    /// The INFO-severity findings.
    #[must_use]
    pub fn infos(&self) -> Vec<Finding<'_>> {
        self.of_severity(Severity::INFO)
    }

    /// The inputs that have at least one ERROR-severity finding.
    #[must_use]
    pub fn invalid_files(&self) -> Vec<&str> {
        self.results
            .iter()
            .filter(|result| !result.valid)
            .map(|result| result.file.as_str())
            .collect()
    }

    /// The result for one input, addressed exactly as it was passed to the engine.
    #[must_use]
    pub fn for_file(&self, file: &str) -> Option<&FileResult> {
        self.results.iter().find(|result| result.file == file)
    }
}
