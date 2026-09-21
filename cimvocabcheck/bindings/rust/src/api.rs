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

//! Running the engine and turning its report into typed Rust.
//!
//! Validation is a *batch* operation here, and deliberately so: loading a CGMES profile set costs
//! around a second, while validating one more query after that costs almost nothing. Passing
//! every input to one call is the difference between a CI job that takes a second and one that
//! takes a second per file.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};

use crate::codes::CONTRACT_MAJOR;
use crate::error::{Error, Result, ToolFailure};
use crate::model::Report;
use crate::runtime::{discover, Discovery, Engine};

/// Exit code the CLI uses for "every input is clean".
pub const EXIT_CLEAN: i32 = 0;
/// Exit code the CLI uses for "at least one input has an error".
pub const EXIT_FINDINGS: i32 = 1;
/// Exit code the CLI uses for a usage or configuration failure.
pub const EXIT_USAGE: i32 = 2;

/// The name the engine reports an input under when it was read from stdin.
pub const STDIN_NAME: &str = "<stdin>";

/// Suffixes the engine reads as Turtle (SHACL shapes); anything else is read as SPARQL.
pub const TURTLE_SUFFIXES: [&str; 2] = ["ttl", "shacl"];

/// Values accepted by [`Options::strictness`].
///
/// Unlike the rule codes this is a closed set owned by the CLI rather than by the report
/// contract — an unknown value is rejected by the engine with exit code 2.
pub struct Strictness;

impl Strictness {
    /// Suppress everything except unknown-term and syntax errors.
    pub const PERMISSIVE: &'static str = "permissive";
    /// The engine's own default severities.
    pub const DEFAULT: &'static str = "default";
    /// Promote warnings to errors.
    pub const STRICT: &'static str = "strict";
    /// Promote warnings and infos to errors.
    pub const PEDANTIC: &'static str = "pedantic";
}

/// Everything a call can vary.
///
/// Built with the setters rather than a struct literal, so a new option is not a breaking change:
///
/// ```no_run
/// use cimvocabcheck::{validate, Options};
/// let report = validate(&["queries/line-segments.rq"], &Options::new().schema("profiles"))?;
/// # Ok::<(), cimvocabcheck::Error>(())
/// ```
#[derive(Clone, Debug)]
pub struct Options {
    /// RDFS profile file(s), or directories of them.
    pub schema: Vec<PathBuf>,
    /// An `opencgmes.jsonc`. Omitted, the engine discovers the nearest one above `cwd`.
    pub config: Option<PathBuf>,
    /// A SPARQL endpoint to load the schema and named-graph mapping from.
    pub endpoint: Option<String>,
    /// Fail instead of falling back to a syntax-only check when the endpoint exposes no schema.
    pub strict_endpoint: bool,
    /// Profile IRIs to restrict validation to.
    pub profiles: Vec<String>,
    /// One of [`Strictness`]; overrides the config file.
    pub strictness: Option<String>,
    /// Include WARN and INFO findings. Defaults to `true`, unlike the CLI: a library hands back
    /// everything and lets the caller filter, rather than discarding findings the caller cannot
    /// then recover.
    pub verbose: bool,
    /// An engine to use, instead of discovering one.
    pub engine: Option<Engine>,
    /// A path to a JAR or binary to use, instead of discovering one.
    pub engine_path: Option<PathBuf>,
    /// Working directory the engine runs in; inputs are resolved against it.
    pub cwd: Option<PathBuf>,
    /// Environment for the child process and for engine discovery.
    pub env: Option<HashMap<String, String>>,
    /// How long to wait before giving up on the engine.
    pub timeout: Option<Duration>,
    /// Take the Docker fallback out of the discovery chain.
    pub no_docker: bool,
    /// Further CLI flags, for options newer than this crate.
    pub extra_args: Vec<String>,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            schema: Vec::new(),
            config: None,
            endpoint: None,
            strict_endpoint: false,
            profiles: Vec::new(),
            strictness: None,
            // Not `bool::default()`: a library that silently drops warnings is a worse library.
            verbose: true,
            engine: None,
            engine_path: None,
            cwd: None,
            env: None,
            timeout: None,
            no_docker: false,
            extra_args: Vec::new(),
        }
    }
}

impl Options {
    /// The default options: every finding, a discovered engine, no timeout.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds one schema file or directory. Repeatable.
    #[must_use]
    pub fn schema(mut self, path: impl Into<PathBuf>) -> Self {
        self.schema.push(path.into());
        self
    }

    /// Sets the config file to read.
    #[must_use]
    pub fn config(mut self, path: impl Into<PathBuf>) -> Self {
        self.config = Some(path.into());
        self
    }

    /// Loads the schema from a SPARQL endpoint.
    #[must_use]
    pub fn endpoint(mut self, url: impl Into<String>) -> Self {
        self.endpoint = Some(url.into());
        self
    }

    /// Fails rather than falling back to a syntax-only check when the endpoint has no schema.
    #[must_use]
    pub fn strict_endpoint(mut self, strict: bool) -> Self {
        self.strict_endpoint = strict;
        self
    }

    /// Restricts validation to one profile IRI. Repeatable.
    #[must_use]
    pub fn profile(mut self, iri: impl Into<String>) -> Self {
        self.profiles.push(iri.into());
        self
    }

    /// Sets the strictness level; see [`Strictness`].
    #[must_use]
    pub fn strictness(mut self, level: impl Into<String>) -> Self {
        self.strictness = Some(level.into());
        self
    }

    /// Turns WARN and INFO findings off, matching the CLI's default.
    #[must_use]
    pub fn verbose(mut self, verbose: bool) -> Self {
        self.verbose = verbose;
        self
    }

    /// Uses this engine instead of discovering one.
    #[must_use]
    pub fn engine(mut self, engine: Engine) -> Self {
        self.engine = Some(engine);
        self
    }

    /// Uses the engine at this path instead of discovering one.
    #[must_use]
    pub fn engine_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.engine_path = Some(path.into());
        self
    }

    /// Runs the engine in this directory.
    #[must_use]
    pub fn cwd(mut self, path: impl Into<PathBuf>) -> Self {
        self.cwd = Some(path.into());
        self
    }

    /// Uses this environment for the child process and for discovery.
    #[must_use]
    pub fn env(mut self, env: HashMap<String, String>) -> Self {
        self.env = Some(env);
        self
    }

    /// Gives up on the engine after this long.
    #[must_use]
    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = Some(timeout);
        self
    }

    /// Takes the Docker fallback out of the discovery chain.
    #[must_use]
    pub fn no_docker(mut self, no_docker: bool) -> Self {
        self.no_docker = no_docker;
        self
    }

    /// Passes a further flag through to the engine.
    #[must_use]
    pub fn extra_arg(mut self, arg: impl Into<String>) -> Self {
        self.extra_args.push(arg.into());
        self
    }

    fn discovery(&self) -> Discovery {
        Discovery {
            engine_path: self.engine_path.clone(),
            env: self.env.clone(),
            cwd: self.cwd.clone(),
            no_docker: self.no_docker,
        }
    }

    fn resolve_engine(&self) -> Result<Engine> {
        match &self.engine {
            Some(engine) => Ok(engine.clone()),
            None => discover(&self.discovery()),
        }
    }
}

/// Validates SPARQL queries and SHACL shapes, and returns one report for all of them.
///
/// Files ending in `.ttl`/`.shacl` are read as SHACL shapes, everything else as SPARQL. Pass
/// paths relative to [`Options::cwd`]: they appear verbatim in the report, and CI annotations
/// only line up when they are repository-relative.
///
/// Findings are data, not errors — a query with errors comes back as a report whose
/// [`Report::ok`] is false.
pub fn validate<P: AsRef<str>>(paths: &[P], options: &Options) -> Result<Report> {
    let inputs: Vec<String> = paths.iter().map(|p| p.as_ref().to_owned()).collect();
    run(&inputs, None, options)
}

/// Validates a SPARQL query held in memory, by feeding it to the engine on stdin.
///
/// The engine tells SHACL from SPARQL by file suffix and stdin has none, so shapes must come
/// from a file. The single result is reported under [`STDIN_NAME`].
pub fn validate_text(text: &str, options: &Options) -> Result<Report> {
    run(&["-".to_owned()], Some(text), options)
}

/// Returns the engine's own version string, for example `"1.4.2"`.
///
/// `"unknown"` means the engine runs from a checkout rather than a packaged artifact — the
/// version is stamped into the JAR manifest at package time.
pub fn tool_version(options: &Options) -> Result<String> {
    let engine = options.resolve_engine()?;
    let args = vec!["--version".to_owned()];
    let output = spawn(&engine, &args, None, options)?;
    if output.status != Some(EXIT_CLEAN) {
        return Err(Error::tool(ToolFailure {
            message: "the engine failed to report its version.".to_owned(),
            argv: engine.command(&args, false, options.cwd.as_deref()),
            status: output.status,
            stdout: output.stdout,
            stderr: output.stderr,
        }));
    }
    let first = output.stdout.lines().next().unwrap_or_default().trim();
    Ok(first.split_once(' ').map_or_else(
        || first.to_owned(),
        |(_, version)| version.trim().to_owned(),
    ))
}

/// Assembles the engine's argv tail. Exposed so a caller can see exactly what will be run.
#[must_use]
pub fn build_args(inputs: &[String], options: &Options) -> Vec<String> {
    let mut args = vec!["--format".to_owned(), "json".to_owned()];
    if options.verbose {
        args.push("--verbose".to_owned());
    }
    if let Some(config) = &options.config {
        args.push("--config".to_owned());
        args.push(config.display().to_string());
    }
    for schema in &options.schema {
        args.push("--schema".to_owned());
        args.push(schema.display().to_string());
    }
    if let Some(endpoint) = &options.endpoint {
        args.push("--endpoint".to_owned());
        args.push(endpoint.clone());
    }
    if options.strict_endpoint {
        args.push("--strict-endpoint".to_owned());
    }
    for profile in &options.profiles {
        args.push("--profile".to_owned());
        args.push(profile.clone());
    }
    if let Some(strictness) = &options.strictness {
        args.push("--strictness".to_owned());
        args.push(strictness.clone());
    }
    args.extend(options.extra_args.iter().cloned());
    // Everything after "--" is an input, so a file whose name begins with "-" is not read as a
    // flag.
    args.push("--".to_owned());
    args.extend(inputs.iter().cloned());
    args
}

/// Parses a `--format json` document, rejecting one from an incompatible contract major.
pub fn parse_report(stdout: &str) -> Result<Report> {
    parse_with(stdout, &[], None, "")
}

// ---- Plumbing ---------------------------------------------------------------------------------

fn run(inputs: &[String], stdin_text: Option<&str>, options: &Options) -> Result<Report> {
    let engine = options.resolve_engine()?;
    let cwd = options.cwd.as_deref();

    let mut resolved = Vec::with_capacity(inputs.len());
    for input in inputs {
        resolved.push(engine.resolve_path(input, cwd)?);
    }
    let mut effective = options.clone();
    effective.schema = options
        .schema
        .iter()
        .map(|p| {
            engine
                .resolve_path(&p.display().to_string(), cwd)
                .map(PathBuf::from)
        })
        .collect::<Result<Vec<_>>>()?;
    effective.config = match &options.config {
        Some(config) => Some(PathBuf::from(
            engine.resolve_path(&config.display().to_string(), cwd)?,
        )),
        None => None,
    };

    let args = build_args(&resolved, &effective);
    let command = engine.command(&args, stdin_text.is_some(), cwd);
    let output = spawn(&engine, &args, stdin_text, options)?;

    match output.status {
        Some(EXIT_USAGE) => Err(Error::tool(ToolFailure {
            message: "the engine rejected the request (usage or configuration error); no report \
                      was produced."
                .to_owned(),
            argv: command,
            status: output.status,
            stdout: output.stdout,
            stderr: output.stderr,
        })),
        Some(EXIT_CLEAN | EXIT_FINDINGS) => {
            parse_with(&output.stdout, &command, output.status, &output.stderr)
        }
        other => Err(Error::tool(ToolFailure {
            message: format!(
                "the engine exited with {}, which is outside its documented exit codes \
                 (0 clean, 1 findings, 2 usage).",
                other.map_or_else(|| "a signal".to_owned(), |code| code.to_string())
            ),
            argv: command,
            status: output.status,
            stdout: output.stdout,
            stderr: output.stderr,
        })),
    }
}

fn parse_with(stdout: &str, argv: &[String], status: Option<i32>, stderr: &str) -> Result<Report> {
    let failure = |message: String| {
        Error::report_parse(ToolFailure {
            message,
            argv: argv.to_vec(),
            status,
            stdout: stdout.to_owned(),
            stderr: stderr.to_owned(),
        })
    };

    let document: serde_json::Value = serde_json::from_str(stdout)
        .map_err(|e| failure(format!("the engine's output was not JSON ({e}).")))?;
    let declared = document.get("contractVersion").and_then(|v| v.as_str());
    let major = declared.and_then(|v| v.split('.').next());
    if major != Some(CONTRACT_MAJOR.to_string().as_str()) {
        return Err(failure(format!(
            "this binding speaks report contract {CONTRACT_MAJOR}.x but the engine produced \
             {declared:?}. A contract major renames or removes fields, so upgrade the binding to \
             match the engine."
        )));
    }
    serde_json::from_value(document).map_err(|e| {
        failure(format!(
            "the engine's report did not match the contract ({e})."
        ))
    })
}

struct Output {
    status: Option<i32>,
    stdout: String,
    stderr: String,
}

/// Spawns the engine, draining both pipes on their own threads so a large report cannot deadlock
/// the child, and enforcing `timeout` by polling rather than by pulling in a dependency.
fn spawn(
    engine: &Engine,
    args: &[String],
    stdin_text: Option<&str>,
    options: &Options,
) -> Result<Output> {
    let command_line = engine.command(args, stdin_text.is_some(), options.cwd.as_deref());
    let (program, rest) = command_line
        .split_first()
        .expect("an engine always has a program");

    let mut command = Command::new(program);
    command
        .args(rest)
        .stdin(if stdin_text.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if let Some(cwd) = &options.cwd {
        command.current_dir(cwd);
    }
    if let Some(env) = &options.env {
        command.env_clear().envs(env);
    }

    let mut child = command.spawn().map_err(|e| {
        Error::io(
            format!("could not start the engine ({})", engine.describe()),
            e,
        )
    })?;

    if let (Some(text), Some(mut pipe)) = (stdin_text, child.stdin.take()) {
        let owned = text.to_owned();
        std::thread::spawn(move || {
            // A BrokenPipe here means the engine stopped reading, which its exit code explains.
            let _ = pipe.write_all(owned.as_bytes());
        });
    }

    let stdout = drain(child.stdout.take());
    let stderr = drain(child.stderr.take());

    let status = match options.timeout {
        None => child
            .wait()
            .map_err(|e| Error::io("waiting for the engine", e))?
            .code(),
        Some(limit) => {
            let deadline = Instant::now() + limit;
            loop {
                match child
                    .try_wait()
                    .map_err(|e| Error::io("waiting for the engine", e))?
                {
                    Some(status) => break status.code(),
                    None if Instant::now() >= deadline => {
                        let _ = child.kill();
                        let _ = child.wait();
                        return Err(Error::timeout(ToolFailure {
                            message: format!(
                                "the engine did not finish within {:.1}s.",
                                limit.as_secs_f64()
                            ),
                            argv: command_line,
                            status: None,
                            // Bounded, not blocking: a grandchild the engine spawned can hold
                            // the pipe open long after the engine itself was killed, and a
                            // timeout that waits for it is not a timeout.
                            stdout: collect(&stdout, Some(GRACE)),
                            stderr: collect(&stderr, Some(GRACE)),
                        }));
                    }
                    None => std::thread::sleep(Duration::from_millis(20)),
                }
            }
        }
    };

    Ok(Output {
        status,
        stdout: collect(&stdout, None),
        stderr: collect(&stderr, None),
    })
}

/// How long a killed engine's output is still waited for before it is given up on.
const GRACE: Duration = Duration::from_millis(200);

/// Reads one pipe to the end on its own thread, so a large report cannot fill the pipe buffer
/// and deadlock the child against a parent that is waiting for it to exit.
fn drain<R: Read + Send + 'static>(pipe: Option<R>) -> Receiver<String> {
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        let mut buffer = Vec::new();
        if let Some(mut pipe) = pipe {
            let _ = pipe.read_to_end(&mut buffer);
        }
        let _ = sender.send(String::from_utf8_lossy(&buffer).into_owned());
    });
    receiver
}

fn collect(pipe: &Receiver<String>, limit: Option<Duration>) -> String {
    match limit {
        None => pipe.recv().unwrap_or_default(),
        Some(limit) => pipe.recv_timeout(limit).unwrap_or_default(),
    }
}

/// True when the engine reads this input as Turtle rather than SPARQL.
#[must_use]
pub fn is_turtle_input(path: &Path) -> bool {
    path.extension().is_some_and(|extension| {
        TURTLE_SUFFIXES
            .iter()
            .any(|suffix| extension.eq_ignore_ascii_case(suffix))
    })
}
