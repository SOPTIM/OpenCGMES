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

//! Shared test scaffolding. Deliberately dependency-free — the crate's whole claim is that a
//! wrapper needs almost nothing, and that should hold for its tests too.

#![allow(dead_code)]

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};

/// A query with one unused variable: parses, and produces WARN findings but no error.
pub const WARNING_QUERY: &str = "SELECT ?s WHERE { ?s ?p ?o }";

/// Fails to parse: exactly one ERROR.
pub const BROKEN_QUERY: &str = "SELEEECT * WHERE { ?s ?p ?o }";

/// Clean in syntax-only mode.
pub const CLEAN_QUERY: &str = "SELECT * WHERE { ?s ?p ?o }";

/// Set in CI so a missing or outdated engine fails the run instead of quietly skipping it.
pub const REQUIRE_ENGINE: &str = "CIMVOCABCHECK_TESTS_REQUIRE_ENGINE";

static COUNTER: AtomicU32 = AtomicU32::new(0);

static SPAWN: Mutex<()> = Mutex::new(());

/// Serializes tests that write a script and then execute it.
///
/// Forking from a multithreaded process duplicates every open descriptor into the child, so a
/// script another thread is still writing can be held open for writing by that child — and Linux
/// refuses to `execve` a file that is open for writing (`ETXTBSY`). Nothing here is a race in the
/// crate; it is an artefact of running such tests in parallel threads of one process.
pub fn exclusive() -> MutexGuard<'static, ()> {
    SPAWN.lock().unwrap_or_else(PoisonError::into_inner)
}

/// A directory that deletes itself, so the suite needs no temp-file crate.
pub struct TempDir(PathBuf);

impl TempDir {
    pub fn new() -> Self {
        let unique = format!(
            "cimvocabcheck-test-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        );
        let path = std::env::temp_dir().join(unique);
        fs::create_dir_all(&path).expect("create a temp directory");
        Self(
            path.canonicalize()
                .expect("canonicalize the temp directory"),
        )
    }

    pub fn path(&self) -> &Path {
        &self.0
    }

    /// Writes an input and returns its name, relative — the way reports are meant to be produced.
    pub fn write(&self, name: &str, text: &str) -> String {
        let target = self.0.join(name);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).expect("create the parent directory");
        }
        fs::write(&target, text).expect("write the input");
        name.to_owned()
    }

    /// A project directory with a config that names no schema, i.e. syntax-only validation.
    pub fn workspace() -> Self {
        let dir = Self::new();
        dir.write("opencgmes.jsonc", "{\"cimvocabcheck\": {}}");
        dir
    }

    /// Writes an executable that behaves like an engine, for tests that must not need a JVM.
    #[cfg(unix)]
    pub fn script(&self, name: &str, body: &str) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;
        let path = self.0.join(name);
        fs::write(&path, format!("#!/bin/sh\n{body}\n")).expect("write the script");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).expect("make it executable");
        path
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// A minimal well-formed report document, for tests that must not spawn anything.
pub fn report_json(results: &str) -> String {
    format!(
        r#"{{
          "contractVersion": "1.0",
          "tool": {{ "name": "cimvocabcheck", "version": "1.2.3" }},
          "summary": {{ "files": 0, "valid": 0, "invalid": 0,
                        "errors": 0, "warnings": 0, "infos": 0 }},
          "results": [{results}]
        }}"#
    )
}

/// An environment with nothing an engine could be discovered from.
pub fn bare_env() -> HashMap<String, String> {
    HashMap::from([("PATH".to_owned(), String::new())])
}
