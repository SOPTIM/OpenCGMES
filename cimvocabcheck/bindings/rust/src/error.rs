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

//! What can go wrong — which is never "the query had findings".
//!
//! Findings are data: a query with errors comes back as a [`Report`](crate::Report) whose
//! [`ok`](crate::Report::ok) is false. These variants are for an engine that could not be run,
//! or that ran and produced something this crate cannot trust.

use std::fmt;
use std::path::PathBuf;

/// The result type every fallible call in this crate returns.
pub type Result<T> = std::result::Result<T, Error>;

/// Everything this crate reports as a failure.
#[derive(Debug)]
#[non_exhaustive]
pub enum Error {
    /// No engine could be discovered. Carries every location that was searched, in order.
    EngineNotFound {
        /// What to do about it.
        message: String,
        /// Where the search looked, in order.
        searched: Vec<String>,
    },

    /// A configured engine path does not exist.
    EngineMissing {
        /// Where the path came from — an argument, or the name of an environment variable.
        origin: String,
        /// The path that is not there.
        path: PathBuf,
    },

    /// The engine ran but did not deliver a usable report.
    ///
    /// Covers a usage or configuration failure (exit code 2) and any exit code outside the
    /// documented `0`/`1`/`2`.
    Tool(Box<ToolFailure>),

    /// The engine's output was not a report of the contract major this crate targets.
    ReportParse(Box<ToolFailure>),

    /// The engine did not finish in time.
    Timeout(Box<ToolFailure>),

    /// The engine could not be started, or its output could not be read.
    Io {
        /// What was being attempted.
        context: String,
        /// The underlying failure.
        source: std::io::Error,
    },

    /// An input path cannot be reached by the selected engine.
    UnreachablePath(String),
}

/// What an engine did when it failed, kept together so a caller can log all of it.
#[derive(Debug, Default)]
pub struct ToolFailure {
    /// A sentence describing the failure.
    pub message: String,
    /// The exact command that was run.
    pub argv: Vec<String>,
    /// The process exit code, when it exited normally.
    pub status: Option<i32>,
    /// Everything the engine wrote to stdout.
    pub stdout: String,
    /// Everything the engine wrote to stderr.
    pub stderr: String,
}

impl Error {
    pub(crate) fn tool(failure: ToolFailure) -> Self {
        Self::Tool(Box::new(failure))
    }

    pub(crate) fn report_parse(failure: ToolFailure) -> Self {
        Self::ReportParse(Box::new(failure))
    }

    pub(crate) fn timeout(failure: ToolFailure) -> Self {
        Self::Timeout(Box::new(failure))
    }

    pub(crate) fn io(context: impl Into<String>, source: std::io::Error) -> Self {
        Self::Io {
            context: context.into(),
            source,
        }
    }

    /// The failure detail an engine printed, when there is one.
    #[must_use]
    pub fn failure(&self) -> Option<&ToolFailure> {
        match self {
            Self::Tool(f) | Self::ReportParse(f) | Self::Timeout(f) => Some(f),
            _ => None,
        }
    }
}

impl fmt::Display for ToolFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.message)?;
        let detail = if self.stderr.trim().is_empty() {
            self.stdout.trim()
        } else {
            self.stderr.trim()
        };
        if !detail.is_empty() {
            write!(f, "\n{detail}")?;
        }
        Ok(())
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EngineNotFound { message, searched } => {
                write!(f, "{message}")?;
                if !searched.is_empty() {
                    write!(f, "\nSearched, in order:")?;
                    for (i, where_) in searched.iter().enumerate() {
                        write!(f, "\n  {}. {where_}", i + 1)?;
                    }
                }
                Ok(())
            }
            Self::EngineMissing { origin, path } => {
                write!(
                    f,
                    "{origin} points at {}, which does not exist.",
                    path.display()
                )
            }
            Self::Tool(failure) | Self::ReportParse(failure) | Self::Timeout(failure) => {
                write!(f, "{failure}")
            }
            Self::Io { context, source } => write!(f, "{context}: {source}"),
            Self::UnreachablePath(message) => write!(f, "{message}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}
