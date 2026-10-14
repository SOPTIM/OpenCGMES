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
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::error::{Error, Result};

/// Executable the engine installs as, looked up on `PATH`.
pub const BINARY_NAME: &str = "cimvocabcheck";

/// Image used by the Docker fallback; override with `CIMVOCABCHECK_DOCKER_IMAGE`.
pub const DEFAULT_IMAGE: &str = "ghcr.io/soptim/cimvocabcheck-cli:latest";

/// Where the Docker fallback mounts the working directory — the image's own `WORKDIR`.
pub const CONTAINER_WORKDIR: &str = "/work";

/// Where the Docker fallback mounts, read-only, the directory of a config file that lies above the
/// working directory and would otherwise be invisible to the engine.
pub const CONTAINER_CONFIG_DIR: &str = "/config";

/// Where the Docker fallback mounts, read-only and one numbered directory each, schema paths that
/// lie outside the working directory.
pub const CONTAINER_SCHEMA_DIR: &str = "/schemas";

/// A host path the Docker engine bind-mounts, read-only, for one call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mount {
    /// The path on the host.
    pub host: PathBuf,
    /// Where the engine sees it inside the container.
    pub container: String,
}

/// Config file names the engine discovers, in the order it prefers them.
pub const CONFIG_FILENAMES: [&str; 2] = ["opencgmes.jsonc", "opencgmes.json"];

/// Flags that name a schema source. When a call passes one, the engine does no config discovery.
const SCHEMA_SOURCE_FLAGS: [&str; 6] = ["-c", "--config", "-s", "--schema", "-e", "--endpoint"];

/// The locale a local engine is handed when the caller's would have it read file names as ASCII.
pub const UTF8_LOCALE: &str = "C.UTF-8";

/// Environment variable naming a fat JAR to run with `java -jar`.
pub const ENV_JAR: &str = "CIMVOCABCHECK_JAR";
/// Environment variable naming an executable engine.
pub const ENV_BIN: &str = "CIMVOCABCHECK_BIN";
/// Environment variable overriding the container image of the Docker fallback.
pub const ENV_IMAGE: &str = "CIMVOCABCHECK_DOCKER_IMAGE";
/// Environment variable that takes the Docker fallback out of the chain when set.
pub const ENV_NO_DOCKER: &str = "CIMVOCABCHECK_NO_DOCKER";

/// How long [`Engine::abandon`] waits for `docker rm` before leaving the container behind.
const ABANDON_LIMIT: Duration = Duration::from_secs(30);

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
        self.command_with_mounts(args, stdin, cwd, &[])
    }

    /// [`Engine::command`], additionally mounting `mounts` read-only — the ones
    /// [`Engine::resolve_config`] and [`Engine::resolve_schema`] collected. Only the Docker engine
    /// has any.
    #[must_use]
    pub fn command_with_mounts(
        &self,
        args: &[String],
        stdin: bool,
        cwd: Option<&Path>,
        mounts: &[Mount],
    ) -> Vec<String> {
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
                // Named, so a run that has to be killed can be found and removed again.
                out.push("--name".into());
                out.push(container_name());
                if stdin {
                    out.push("-i".into());
                }
                let mount = self.mount(cwd);
                out.push("-v".into());
                out.push(format!("{}:{CONTAINER_WORKDIR}", mount.display()));
                let config = if names_schema_source(args) {
                    None
                } else {
                    config_above(&mount)
                };
                if let Some((directory, _)) = &config {
                    // The engine discovers its config upward from the working directory, which
                    // inside the container stops at the mount: a config above it would be
                    // silently missed, leaving a syntax-only check that reports every query as
                    // valid.
                    out.push("-v".into());
                    out.push(format!("{}:{CONTAINER_CONFIG_DIR}:ro", directory.display()));
                }
                for mount in mounts {
                    out.push("-v".into());
                    out.push(format!("{}:{}:ro", mount.host.display(), mount.container));
                }
                out.push("-w".into());
                out.push(CONTAINER_WORKDIR.into());
                out.push(image.clone());
                if let Some((_, name)) = config {
                    out.push("--config".into());
                    out.push(format!("{CONTAINER_CONFIG_DIR}/{name}"));
                }
            }
        }
        out.extend(args.iter().cloned());
        out
    }

    /// Rewrites a config file path for this engine.
    ///
    /// Like [`Engine::resolve_path`], except that the Docker engine does not refuse a config
    /// outside the working directory: it mounts the config's directory read-only at
    /// [`CONTAINER_CONFIG_DIR`], adding to `mounts`, so the config's own relative schema paths
    /// keep resolving — exactly as for a config the engine discovers above the working directory.
    pub fn resolve_config(
        &self,
        path: &str,
        cwd: Option<&Path>,
        mounts: &mut Vec<Mount>,
    ) -> Result<String> {
        self.resolve_or_mount(path, cwd, |host| {
            let name = host.file_name()?.to_string_lossy().into_owned();
            let directory = host.parent()?.to_path_buf();
            mounts.push(Mount {
                host: directory,
                container: CONTAINER_CONFIG_DIR.to_owned(),
            });
            Some(format!("{CONTAINER_CONFIG_DIR}/{name}"))
        })
    }

    /// Rewrites a schema file or directory path for this engine.
    ///
    /// Like [`Engine::resolve_path`], except that the Docker engine does not refuse a schema
    /// outside the working directory: it mounts it read-only under [`CONTAINER_SCHEMA_DIR`],
    /// adding to `mounts`.
    pub fn resolve_schema(
        &self,
        path: &str,
        cwd: Option<&Path>,
        mounts: &mut Vec<Mount>,
    ) -> Result<String> {
        self.resolve_or_mount(path, cwd, |host| {
            let name = host.file_name()?.to_string_lossy().into_owned();
            let container = format!("{CONTAINER_SCHEMA_DIR}/{}/{name}", mounts.len());
            mounts.push(Mount {
                host: host.to_path_buf(),
                container: container.clone(),
            });
            Some(container)
        })
    }

    /// [`Engine::resolve_path`], falling back to `mount` for an existing path outside the
    /// working directory. A path that does not exist keeps the refusal: binding it would make
    /// Docker create an empty directory in its place.
    fn resolve_or_mount(
        &self,
        path: &str,
        cwd: Option<&Path>,
        mount: impl FnOnce(&Path) -> Option<String>,
    ) -> Result<String> {
        match self.resolve_path(path, cwd) {
            Err(Error::UnreachablePath(message)) => self
                .mount(cwd)
                .join(path)
                .canonicalize()
                .ok()
                .and_then(|host| mount(&host))
                .ok_or(Error::UnreachablePath(message)),
            resolved => resolved,
        }
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
        let lexical = normalize(&if candidate.is_absolute() {
            candidate.to_path_buf()
        } else {
            mount.join(candidate)
        });
        // The mount is spelled as given, and an absolute input may go through a symlink the mount
        // does not, or the other way round; the input is inside when both resolve to one place.
        let canonical = |p: &Path| p.canonicalize().ok();
        let relative = lexical
            .strip_prefix(&mount)
            .map(Path::to_path_buf)
            .ok()
            .or_else(|| {
                let root = canonical(&mount)?;
                let inside = canonical(&lexical).or_else(|| {
                    let parent = canonical(lexical.parent()?)?;
                    Some(parent.join(lexical.file_name()?))
                })?;
                inside.strip_prefix(root).map(Path::to_path_buf).ok()
            });
        relative
            .map(
                |relative| match relative.to_string_lossy().replace('\\', "/") {
                    empty if empty.is_empty() => ".".to_owned(),
                    relative => relative,
                },
            )
            .ok_or_else(|| {
                Error::UnreachablePath(format!(
                    "{path:?} is outside the working directory {}, which is the only path the \
                     Docker engine mounts. Pass paths relative to the working directory, run from \
                     a directory that contains them, or configure a local engine \
                     ({ENV_JAR} / {ENV_BIN}).",
                    mount.display()
                ))
            })
    }

    /// The locale variable to set when spawning this engine, if the environment needs one.
    ///
    /// A local engine is a JVM, which encodes file names in the locale's charset: under a C or
    /// POSIX locale (or none, as in many containers and CI images) a non-ASCII input name cannot
    /// even be opened. The variable POSIX gives precedence to — `LC_ALL`, else `LC_CTYPE`, else
    /// `LANG` — decides; when it is C, POSIX or unset this returns the variable to set to
    /// [`UTF8_LOCALE`] (`LC_ALL` when that is what is in force, so a deliberate `LC_ALL=C` keeps
    /// everything but its charset). `env` is the child's environment, `None` meaning this
    /// process's. The Docker engine runs under the image's locale and never needs one, and
    /// Windows takes no locale from the environment.
    #[must_use]
    pub fn locale_override(
        &self,
        env: Option<&HashMap<String, String>>,
    ) -> Option<(&'static str, &'static str)> {
        if matches!(self, Self::Docker { .. }) || cfg!(windows) {
            return None;
        }
        let read = |name: &str| match env {
            Some(env) => env.get(name).cloned(),
            None => env::var(name).ok(),
        };
        let non_empty = |name: &str| read(name).filter(|value| !value.is_empty());
        let all = non_empty("LC_ALL");
        let effective = all
            .clone()
            .or_else(|| non_empty("LC_CTYPE"))
            .or_else(|| non_empty("LANG"))
            .unwrap_or_default();
        if !matches!(effective.as_str(), "" | "C" | "POSIX") {
            return None;
        }
        let variable = if all.is_some() { "LC_ALL" } else { "LC_CTYPE" };
        Some((variable, UTF8_LOCALE))
    }

    /// Cleans up after the process spawned from `argv` was killed before it finished.
    ///
    /// Only the Docker engine leaves anything behind: killing `docker run` stops the client, not
    /// the container. Best effort — the failure that caused the kill matters more.
    pub fn abandon(&self, argv: &[String], env: Option<&HashMap<String, String>>) {
        self.abandon_within(argv, env, ABANDON_LIMIT);
    }

    /// [`Engine::abandon`], giving up on the cleanup after `limit`: a Docker daemon that stopped
    /// responding is a likely reason the run was killed, and a timeout that then waits on that
    /// daemon is not a timeout.
    fn abandon_within(
        &self,
        argv: &[String],
        env: Option<&HashMap<String, String>>,
        limit: Duration,
    ) {
        let Self::Docker { docker, .. } = self else {
            return;
        };
        let Some(name) = argv
            .iter()
            .position(|arg| arg == "--name")
            .and_then(|at| argv.get(at + 1))
        else {
            return;
        };
        let mut command = Command::new(docker);
        command
            .args(["rm", "--force", name])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        if let Some(env) = env {
            command.env_clear().envs(env);
        }
        let Ok(mut child) = command.spawn() else {
            return;
        };
        let deadline = Instant::now() + limit;
        loop {
            match child.try_wait() {
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(20)),
                Ok(None) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return;
                }
                Ok(Some(_)) | Err(_) => return,
            }
        }
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
    /// the mount cannot be frozen at discovery time. Made absolute lexically rather than
    /// canonicalized: on Windows `canonicalize` yields a `\\?\` path that `docker -v` rejects.
    fn mount(&self, cwd: Option<&Path>) -> PathBuf {
        let base = match (cwd, self) {
            (Some(path), _) => path.to_path_buf(),
            (None, Self::Docker { cwd, .. }) => cwd.clone(),
            (None, _) => PathBuf::from("."),
        };
        normalize(&absolute(&base))
    }
}

/// The config file the engine would discover from `directory`, if it lies above it: its directory
/// and file name.
///
/// `None` when the nearest config is inside `directory` (the engine finds that one itself) or
/// when there is none at all.
fn config_above(directory: &Path) -> Option<(PathBuf, &'static str)> {
    for candidate in directory.ancestors() {
        for name in CONFIG_FILENAMES {
            if candidate.join(name).is_file() {
                return (candidate != directory).then(|| (candidate.to_path_buf(), name));
            }
        }
    }
    None
}

/// Whether `args` choose a schema source themselves, so no config is discovered.
fn names_schema_source(args: &[String]) -> bool {
    for arg in args {
        if arg == "--" {
            return false;
        }
        let flag = if arg.starts_with("--") {
            arg.split('=').next().unwrap_or_default()
        } else {
            arg.get(..2).unwrap_or_default()
        };
        if SCHEMA_SOURCE_FLAGS.contains(&flag) {
            return true;
        }
    }
    false
}

/// `path` made absolute against the current directory, without touching the filesystem.
fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() {
        return path.to_path_buf();
    }
    env::current_dir().map_or_else(|_| path.to_path_buf(), |cwd| cwd.join(path))
}

/// A container name unique to this run.
fn container_name() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_nanos());
    format!(
        "cimvocabcheck-{}-{nanos}-{}",
        std::process::id(),
        COUNTER.fetch_add(1, Ordering::Relaxed)
    )
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
    // Checked against this process's directory, so pinned to it: the engine may run in another.
    let absolute = absolute(path);
    let path = absolute.as_path();
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
///
/// On Windows the name is tried with the usual executable extensions first, as `cmd` does.
fn which(name: &str, path_var: &str) -> Option<PathBuf> {
    let extensions: &[&str] = if cfg!(windows) {
        &[".exe", ".cmd", ".bat", ""]
    } else {
        &[""]
    };
    env::split_paths(path_var)
        .filter(|directory| !directory.as_os_str().is_empty())
        .flat_map(|directory| {
            extensions
                .iter()
                .map(move |extension| directory.join(format!("{name}{extension}")))
        })
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

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn abandoning_a_run_gives_up_on_a_docker_that_does_not_answer() {
        use std::os::unix::fs::PermissionsExt;

        let dir = env::temp_dir().join(format!("cimvocabcheck-abandon-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let docker = dir.join("docker");
        std::fs::write(&docker, "#!/bin/sh\nexec sleep 30\n").unwrap();
        std::fs::set_permissions(&docker, std::fs::Permissions::from_mode(0o755)).unwrap();
        let engine = Engine::Docker {
            image: "cvc:test".into(),
            docker,
            cwd: dir.clone(),
        };
        let argv = engine.command(&[], false, None);

        let started = Instant::now();
        engine.abandon_within(&argv, None, Duration::from_millis(200));

        assert!(
            started.elapsed() < Duration::from_secs(10),
            "{:?}",
            started.elapsed()
        );
        let _ = std::fs::remove_dir_all(dir);
    }
}
