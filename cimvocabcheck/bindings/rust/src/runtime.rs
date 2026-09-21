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

//! Finding the engine to run.
//!
//! This crate ships no engine; it drives the CIMVocabCheck CLI. [`discover`] looks in a fixed
//! order — an explicitly configured path, the environment, `PATH`, and finally Docker — and
//! reports every place it looked when it comes up empty, because "not found" is the failure a
//! user actually has to fix.

use std::collections::HashMap;
use std::env;
use std::path::{Component, Path, PathBuf};

use crate::error::{Error, Result};

/// Executable the engine installs as, looked up on `PATH`.
pub const BINARY_NAME: &str = "cimvocabcheck";

/// Image used by the Docker fallback; override with `CIMVOCABCHECK_DOCKER_IMAGE`.
pub const DEFAULT_IMAGE: &str = "ghcr.io/soptim/cimvocabcheck-cli:latest";

/// Where the Docker fallback mounts the working directory — the image's own `WORKDIR`.
pub const CONTAINER_WORKDIR: &str = "/work";

/// Environment variable naming a fat JAR to run with `java -jar`.
pub const ENV_JAR: &str = "CIMVOCABCHECK_JAR";
/// Environment variable naming an executable engine.
pub const ENV_BIN: &str = "CIMVOCABCHECK_BIN";
/// Environment variable overriding the container image of the Docker fallback.
pub const ENV_IMAGE: &str = "CIMVOCABCHECK_DOCKER_IMAGE";
/// Environment variable that takes the Docker fallback out of the chain when set.
pub const ENV_NO_DOCKER: &str = "CIMVOCABCHECK_NO_DOCKER";

/// A way to invoke CIMVocabCheck.
///
/// The variants differ only in how a command line is assembled; everything above this layer sees
/// one type and one report format.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Engine {
    /// A fat JAR run with `java -jar`.
    Jar {
        /// The JAR to run.
        jar: PathBuf,
        /// The Java launcher to run it with.
        java: PathBuf,
    },
    /// An executable engine — the CLI's launcher script, or a native binary.
    Binary {
        /// The executable.
        path: PathBuf,
    },
    /// The published container image, with the working directory mounted at its `WORKDIR`.
    ///
    /// Inputs are therefore addressed relative to the working directory. That is what the report
    /// contract asks for anyway: the paths in a report are the paths that were passed in, and CI
    /// annotations only line up when they are repository-relative.
    Docker {
        /// The image to run.
        image: String,
        /// The `docker` executable.
        docker: PathBuf,
        /// The directory mounted when a call does not name one of its own.
        cwd: PathBuf,
    },
}

impl Engine {
    /// Builds the argv to spawn.
    ///
    /// Never a shell string: inputs and schema paths contain spaces. `cwd` is the directory the
    /// process will start in, which an engine that cannot see the filesystem directly has to
    /// account for.
    #[must_use]
    pub fn command(&self, args: &[String], stdin: bool, cwd: Option<&Path>) -> Vec<String> {
        let mut out = Vec::with_capacity(args.len() + 8);
        match self {
            Self::Jar { jar, java } => {
                out.push(java.display().to_string());
                out.push("-jar".into());
                out.push(jar.display().to_string());
            }
            Self::Binary { path } => out.push(path.display().to_string()),
            Self::Docker { image, docker, .. } => {
                out.push(docker.display().to_string());
                out.push("run".into());
                out.push("--rm".into());
                if stdin {
                    out.push("-i".into());
                }
                out.push("-v".into());
                out.push(format!("{}:{CONTAINER_WORKDIR}", self.mount(cwd).display()));
                out.push("-w".into());
                out.push(CONTAINER_WORKDIR.into());
                out.push(image.clone());
            }
        }
        out.extend(args.iter().cloned());
        out
    }

    /// Rewrites one path for this engine. Only the Docker engine needs to.
    pub fn resolve_path(&self, path: &str, cwd: Option<&Path>) -> Result<String> {
        let Self::Docker { .. } = self else {
            return Ok(path.to_owned());
        };
        if path == "-" {
            return Ok(path.to_owned());
        }
        let mount = self.mount(cwd);
        let candidate = Path::new(path);
        let absolute = if candidate.is_absolute() {
            candidate.to_path_buf()
        } else {
            mount.join(candidate)
        };
        normalize(&absolute)
            .strip_prefix(&mount)
            .map(|relative| relative.to_string_lossy().replace('\\', "/"))
            .map_err(|_| {
                Error::UnreachablePath(format!(
                    "{path:?} is outside the working directory {}, which is the only path the \
                     Docker engine mounts. Pass paths relative to the working directory, run from \
                     a directory that contains them, or configure a local engine \
                     ({ENV_JAR} / {ENV_BIN}).",
                    mount.display()
                ))
            })
    }

    /// A one-line description, for diagnostics and error messages.
    #[must_use]
    pub fn describe(&self) -> String {
        match self {
            Self::Jar { jar, java } => format!("{} -jar {}", java.display(), jar.display()),
            Self::Binary { path } => path.display().to_string(),
            Self::Docker { image, docker, cwd } => format!(
                "{} run {image} (mounting {})",
                docker.display(),
                cwd.display()
            ),
        }
    }

    /// The directory to mount: the one the call runs in, else the engine's own.
    ///
    /// An engine is discovered once and may then be used from several working directories, so
    /// the mount cannot be frozen at discovery time.
    fn mount(&self, cwd: Option<&Path>) -> PathBuf {
        let base = match (cwd, self) {
            (Some(path), _) => path.to_path_buf(),
            (None, Self::Docker { cwd, .. }) => cwd.clone(),
            (None, _) => PathBuf::from("."),
        };
        base.canonicalize().unwrap_or_else(|_| normalize(&base))
    }
}

/// Resolves `.` and `..` lexically; unlike `canonicalize` it does not require the path to exist.
fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// How to find an engine. Every field is optional; [`Discovery::default`] searches the real
/// environment.
#[derive(Clone, Debug, Default)]
pub struct Discovery {
    /// An explicit engine path — a `.jar` is run with `java`, anything else is executed.
    pub engine_path: Option<PathBuf>,
    /// The environment to read, instead of this process's own.
    pub env: Option<HashMap<String, String>>,
    /// The working directory a Docker engine should mount.
    pub cwd: Option<PathBuf>,
    /// Set to take the Docker fallback out of the chain.
    pub no_docker: bool,
}

/// Returns the engine to run, searching in the documented order.
///
/// The order is deliberate: an explicit choice beats the environment, the environment beats
/// whatever happens to be installed, and Docker is the last resort because it is the slowest and
/// the only one that can reach the network to fetch itself.
pub fn discover(options: &Discovery) -> Result<Engine> {
    let get = |name: &str| -> Option<String> {
        match &options.env {
            Some(env) => env.get(name).cloned(),
            None => env::var(name).ok(),
        }
    };
    let path_var = get("PATH").unwrap_or_default();
    let mut searched: Vec<String> = Vec::new();

    if let Some(path) = &options.engine_path {
        return engine_for_path(path, "the engine passed to this call", &get);
    }

    for name in [ENV_JAR, ENV_BIN] {
        let value = get(name);
        searched.push(format!(
            "${name} ({})",
            value
                .as_deref()
                .filter(|v| !v.is_empty())
                .unwrap_or("unset")
        ));
        if let Some(value) = value.filter(|v| !v.is_empty()) {
            return engine_for_path(Path::new(&value), &format!("${name}"), &get);
        }
    }

    searched.push(format!("{BINARY_NAME:?} on PATH"));
    if let Some(path) = which(BINARY_NAME, &path_var) {
        return Ok(Engine::Binary { path });
    }

    let image = get(ENV_IMAGE)
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| DEFAULT_IMAGE.to_owned());
    let disabled = options.no_docker || truthy(get(ENV_NO_DOCKER).as_deref());
    searched.push(format!(
        "the Docker image {image} ({})",
        if disabled {
            "disabled"
        } else {
            "requires 'docker' on PATH"
        }
    ));
    if !disabled {
        if let Some(docker) = which("docker", &path_var) {
            return Ok(Engine::Docker {
                image,
                docker,
                cwd: options.cwd.clone().unwrap_or_else(|| PathBuf::from(".")),
            });
        }
    }

    Err(Error::EngineNotFound {
        message: format!(
            "no CIMVocabCheck engine found. Install the CLI and put it on PATH, point {ENV_JAR} \
             at a fat JAR, or make Docker available for the container fallback."
        ),
        searched,
    })
}

fn engine_for_path(
    path: &Path,
    origin: &str,
    get: &impl Fn(&str) -> Option<String>,
) -> Result<Engine> {
    if !path.exists() {
        return Err(Error::EngineMissing {
            origin: origin.to_owned(),
            path: path.to_path_buf(),
        });
    }
    if path
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
    {
        return Ok(Engine::Jar {
            jar: path.to_path_buf(),
            java: find_java(get)?,
        });
    }
    Ok(Engine::Binary {
        path: path.to_path_buf(),
    })
}

/// Locates a Java launcher for the JAR engines: `JAVA_HOME` first, then `PATH`.
pub(crate) fn find_java(get: &impl Fn(&str) -> Option<String>) -> Result<PathBuf> {
    let executable = if cfg!(windows) { "java.exe" } else { "java" };
    if let Some(home) = get("JAVA_HOME").filter(|v| !v.is_empty()) {
        let candidate = Path::new(&home).join("bin").join(executable);
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    which("java", &get("PATH").unwrap_or_default()).ok_or_else(|| Error::EngineNotFound {
        message: "a CIMVocabCheck JAR was selected but no Java launcher was found. The JAR engine \
                  needs a JRE 21 or newer; install one, or set JAVA_HOME."
            .to_owned(),
        searched: Vec::new(),
    })
}

/// A minimal `which`: the first entry of `path_var` holding an executable file called `name`.
fn which(name: &str, path_var: &str) -> Option<PathBuf> {
    env::split_paths(path_var)
        .map(|directory| directory.join(name))
        .find(|candidate| is_executable(candidate))
}

#[cfg(unix)]
fn is_executable(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    path.metadata()
        .is_ok_and(|meta| meta.is_file() && meta.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn is_executable(path: &Path) -> bool {
    path.is_file()
}

/// Reads an on/off environment variable the way a shell user expects.
fn truthy(value: Option<&str>) -> bool {
    value.is_some_and(|v| {
        !matches!(
            v.trim().to_ascii_lowercase().as_str(),
            "" | "0" | "false" | "no"
        )
    })
}
