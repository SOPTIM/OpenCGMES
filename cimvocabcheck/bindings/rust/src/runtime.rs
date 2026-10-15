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

use std::collections::{BTreeSet, HashMap};
use std::env;
use std::fs;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::error::{Error, Result};

/// Executable the engine installs as, looked up on `PATH`.
pub const BINARY_NAME: &str = "cimvocabcheck";

/// Image used by the Docker fallback; override with `CIMVOCABCHECK_DOCKER_IMAGE`.
pub const DEFAULT_IMAGE: &str = "ghcr.io/soptim/cimvocabcheck-cli:latest";

/// Where the Docker fallback mounts the working directory — the image's own `WORKDIR`.
pub const CONTAINER_WORKDIR: &str = "/work";

/// Where the Docker fallback mounts, read-only, what it has to reach outside the working directory:
/// a config, the schema paths it names, a schema passed in. Each goes to this directory followed by
/// its absolute host path (see [`container_path`]), so a config's relative paths lead to the same
/// places in the container as on the host.
pub const CONTAINER_HOST_DIR: &str = "/host";

/// Container paths a mount must neither be nor contain: the image's own directories and the
/// mounts this module makes.
const IMAGE_PATHS: [&str; 14] = [
    CONTAINER_WORKDIR,
    CONTAINER_HOST_DIR,
    "/opt/cimvocabcheck",
    "/usr",
    "/bin",
    "/sbin",
    "/lib",
    "/lib64",
    "/etc",
    "/tmp",
    "/var",
    "/proc",
    "/sys",
    "/dev",
];

/// Container directories nothing may be mounted inside: other mounts, and the kernel's.
const NOT_INSIDE: [&str; 5] = [
    CONTAINER_WORKDIR,
    CONTAINER_HOST_DIR,
    "/proc",
    "/sys",
    "/dev",
];

/// A host path the Docker engine bind-mounts, read-only, for one call.
#[non_exhaustive]
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mount {
    /// The path on the host.
    pub host: PathBuf,
    /// Where the engine sees it inside the container.
    pub container: String,
}

impl Mount {
    /// Mounts `host` where the engine sees it as `container`.
    pub fn new(host: impl Into<PathBuf>, container: impl Into<String>) -> Self {
        Self {
            host: host.into(),
            container: container.into(),
        }
    }
}

/// Config file names the engine discovers, in the order it prefers them.
pub const CONFIG_FILENAMES: [&str; 2] = ["opencgmes.jsonc", "opencgmes.json"];

/// Flags that name a schema source. When a call passes one, the engine does no config discovery.
const SCHEMA_SOURCE_FLAGS: [&str; 6] = ["-c", "--config", "-s", "--schema", "-e", "--endpoint"];

/// The locale a local engine is handed when the caller's would have it read file names as ASCII.
pub const UTF8_LOCALE: &str = "C.UTF-8";

/// Locales the C library always has, whatever is installed.
const BUILTIN_LOCALES: [&str; 3] = ["C", "POSIX", "C.utf8"];

/// The variables that name a locale: `LANG` and the categories, POSIX's and glibc's. Not every
/// `LC_` variable is one — terminals set `LC_TERMINAL`, and ssh forwards `LC_*` wholesale.
const LOCALE_VARIABLES: [&str; 14] = [
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "LC_COLLATE",
    "LC_MESSAGES",
    "LC_MONETARY",
    "LC_NUMERIC",
    "LC_TIME",
    "LC_ADDRESS",
    "LC_IDENTIFICATION",
    "LC_MEASUREMENT",
    "LC_NAME",
    "LC_PAPER",
    "LC_TELEPHONE",
];

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
///
/// Engines and their fields may be added in a minor version, so a `match` needs a `_` arm and a
/// pattern `..`; build one with [`Engine::jar`], [`Engine::binary`] or [`Engine::docker`].
#[non_exhaustive]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Engine {
    /// A fat JAR run with `java -jar`.
    #[non_exhaustive]
    Jar {
        /// The JAR to run.
        jar: PathBuf,
        /// The Java launcher to run it with.
        java: PathBuf,
    },
    /// An executable engine — the CLI's launcher script, or a native binary.
    #[non_exhaustive]
    Binary {
        /// The executable.
        path: PathBuf,
    },
    /// The published container image, with the working directory mounted at its `WORKDIR`.
    ///
    /// Inputs are therefore addressed relative to the working directory. That is what the report
    /// contract asks for anyway: the paths in a report are the paths that were passed in, and CI
    /// annotations only line up when they are repository-relative.
    #[non_exhaustive]
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
    /// A fat JAR, run with the Java launcher `java`.
    pub fn jar(jar: impl Into<PathBuf>, java: impl Into<PathBuf>) -> Self {
        Self::Jar {
            jar: jar.into(),
            java: java.into(),
        }
    }

    /// An executable engine.
    pub fn binary(path: impl Into<PathBuf>) -> Self {
        Self::Binary { path: path.into() }
    }

    /// The container `image`, run with the `docker` executable, mounting `cwd` when a call does
    /// not name a working directory of its own.
    pub fn docker(
        image: impl Into<String>,
        docker: impl Into<PathBuf>,
        cwd: impl Into<PathBuf>,
    ) -> Self {
        Self::Docker {
            image: image.into(),
            docker: docker.into(),
            cwd: cwd.into(),
        }
    }

    /// Builds the argv to spawn.
    ///
    /// Never a shell string: inputs and schema paths contain spaces. `cwd` is the directory the
    /// process will start in, which an engine that cannot see the filesystem directly has to
    /// account for.
    ///
    /// # Errors
    ///
    /// [`Error::UnreachablePath`] when the Docker engine discovers a config naming a schema path
    /// it cannot mount.
    pub fn command(&self, args: &[String], stdin: bool, cwd: Option<&Path>) -> Result<Vec<String>> {
        self.command_with_mounts(args, stdin, cwd, &[])
    }

    /// [`Engine::command`], additionally mounting `mounts` read-only — the ones
    /// [`Engine::resolve_config`] and [`Engine::resolve_schema`] collected. Only the Docker engine
    /// has any.
    ///
    /// # Errors
    ///
    /// As [`Engine::command`].
    pub fn command_with_mounts(
        &self,
        args: &[String],
        stdin: bool,
        cwd: Option<&Path>,
        mounts: &[Mount],
    ) -> Result<Vec<String>> {
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
                let workdir = self.mount(cwd);
                out.push("-v".into());
                out.push(format!("{}:{CONTAINER_WORKDIR}", workdir.display()));
                let mut mounts = mounts.to_vec();
                let mut config_flag = None;
                if let Some(config) = (!names_schema_source(args))
                    .then(|| nearest_config(&workdir))
                    .flatten()
                {
                    let seen = if config.parent() == Some(workdir.as_path()) {
                        format!("{CONTAINER_WORKDIR}/{}", file_name(&config))
                    } else {
                        // The engine discovers its config upward from the working directory,
                        // which inside the container stops at the mount: a config above it would
                        // be silently missed, leaving a syntax-only check that reports every query
                        // as valid.
                        let seen = mount_config(&config, &workdir, &mut mounts)?;
                        config_flag = Some(seen.clone());
                        seen
                    };
                    mount_config_paths(&config, &seen, &workdir, &mut mounts)?;
                }
                for mount in &mounts {
                    out.push("-v".into());
                    out.push(format!("{}:{}:ro", mount.host.display(), mount.container));
                }
                out.push("-w".into());
                out.push(CONTAINER_WORKDIR.into());
                out.push(image.clone());
                if let Some(seen) = config_flag {
                    out.push("--config".into());
                    out.push(seen);
                }
            }
        }
        out.extend(args.iter().cloned());
        Ok(out)
    }

    /// Rewrites a config file path for this engine.
    ///
    /// Like [`Engine::resolve_path`], except that the Docker engine does not refuse a config
    /// outside the working directory: it mounts the config's directory read-only under
    /// [`CONTAINER_HOST_DIR`], adding to `mounts`. Either way it also mounts each schema path the
    /// config names that the container could not otherwise reach, so the config's own relative
    /// paths resolve as they do on the host — exactly as for a config the engine discovers.
    pub fn resolve_config(
        &self,
        path: &str,
        cwd: Option<&Path>,
        mounts: &mut Vec<Mount>,
    ) -> Result<String> {
        if !matches!(self, Self::Docker { .. }) {
            return Ok(path.to_owned());
        }
        let workdir = self.mount(cwd);
        match self.resolve_path(path, cwd) {
            Ok(resolved) => {
                let seen = posix_normalize(&posix_join(CONTAINER_WORKDIR, &resolved));
                mount_config_paths(&normalize(&workdir.join(path)), &seen, &workdir, mounts)?;
                Ok(resolved)
            }
            Err(Error::UnreachablePath(message)) => {
                let host = existing(&workdir.join(path)).ok_or(Error::UnreachablePath(message))?;
                let seen = mount_config(&host, &workdir, mounts)?;
                mount_config_paths(&host, &seen, &workdir, mounts)?;
                Ok(seen)
            }
            Err(other) => Err(other),
        }
    }

    /// Rewrites a schema file or directory path for this engine.
    ///
    /// Like [`Engine::resolve_path`], except that the Docker engine does not refuse a schema
    /// outside the working directory: it mounts it read-only at its [`container_path`], adding to
    /// `mounts`. A path that does not exist keeps the refusal: binding it would make Docker create
    /// an empty directory in its place.
    pub fn resolve_schema(
        &self,
        path: &str,
        cwd: Option<&Path>,
        mounts: &mut Vec<Mount>,
    ) -> Result<String> {
        match self.resolve_path(path, cwd) {
            Err(Error::UnreachablePath(message)) => {
                let workdir = self.mount(cwd);
                let host = existing(&workdir.join(path)).ok_or(Error::UnreachablePath(message))?;
                let container = container_path(&host);
                add_mount(&host, &container, &workdir, mounts, None)?;
                Ok(container)
            }
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
    /// even be opened. Two cases need a variable set to [`UTF8_LOCALE`]. When the variable POSIX
    /// gives precedence to — `LC_ALL`, else `LC_CTYPE`, else `LANG` — is C, POSIX or unset, that
    /// one (`LC_ALL` when that is what is in force, so a deliberate `LC_ALL=C` keeps everything
    /// but its charset, else `LC_CTYPE`). When a locale the environment names is not installed,
    /// the C library falls back to C for *every* category, so then `LC_ALL`. `env` is the child's
    /// environment, `None` meaning this process's. The Docker engine runs under the image's locale
    /// and never needs one, and Windows takes no locale from the environment.
    #[must_use]
    pub fn locale_override(
        &self,
        env: Option<&HashMap<String, String>>,
    ) -> Option<(&'static str, &'static str)> {
        self.locale_override_given(env, installed_locales())
    }

    /// [`Engine::locale_override`], given what `locale -a` lists (`None`: unknown).
    fn locale_override_given(
        &self,
        env: Option<&HashMap<String, String>>,
        installed: Option<&BTreeSet<String>>,
    ) -> Option<(&'static str, &'static str)> {
        if matches!(self, Self::Docker { .. }) || cfg!(windows) {
            return None;
        }
        let vars: Vec<(String, String)> = match env {
            Some(env) => env.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
            None => env::vars_os()
                .map(|(k, v)| {
                    (
                        k.to_string_lossy().into_owned(),
                        v.to_string_lossy().into_owned(),
                    )
                })
                .collect(),
        };
        let non_empty = |name: &str| {
            vars.iter()
                .find(|(key, value)| key == name && !value.is_empty())
                .map(|(_, value)| value.clone())
        };
        let all = non_empty("LC_ALL");
        let named: Vec<&str> = match &all {
            Some(all) => vec![all.as_str()],
            None => vars
                .iter()
                .filter(|(key, value)| {
                    LOCALE_VARIABLES.contains(&key.as_str()) && !value.is_empty()
                })
                .map(|(_, value)| value.as_str())
                .collect(),
        };
        if let Some(installed) = installed {
            if named.iter().any(|name| !is_installed(name, installed)) {
                return Some(("LC_ALL", UTF8_LOCALE));
            }
        }
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

/// The locales `locale -a` lists, or `None` when that cannot be found out.
///
/// Asked once per process. Without a `locale` command (musl-based images have none, and musl reads
/// every locale as UTF-8 anyway) nothing is known, and a named locale is trusted.
fn installed_locales() -> Option<&'static BTreeSet<String>> {
    static INSTALLED: OnceLock<Option<BTreeSet<String>>> = OnceLock::new();
    INSTALLED
        .get_or_init(|| {
            let command = which("locale", &env::var("PATH").unwrap_or_default())?;
            let output = Command::new(command)
                .arg("-a")
                .stdin(Stdio::null())
                .stderr(Stdio::null())
                .output()
                .ok()
                .filter(|output| output.status.success())?;
            let listed = String::from_utf8(output.stdout).ok()?;
            Some(
                listed
                    .lines()
                    .map(str::trim)
                    .filter(|line| !line.is_empty())
                    .map(str::to_owned)
                    .collect(),
            )
        })
        .as_ref()
}

/// Whether `name` is one of `installed`, however the codeset is spelled.
fn is_installed(name: &str, installed: &BTreeSet<String>) -> bool {
    let wanted = locale_key(name);
    BUILTIN_LOCALES
        .iter()
        .copied()
        .chain(installed.iter().map(String::as_str))
        .any(|entry| locale_key(entry) == wanted)
}

/// `en_US.UTF-8` and `en_US.utf8` name one locale; this is what they have in common.
fn locale_key(name: &str) -> String {
    let Some((language, rest)) = name.split_once('.') else {
        return name.to_owned();
    };
    let (codeset, modifier) = rest
        .split_once('@')
        .map_or((rest, None), |(c, m)| (c, Some(m)));
    let codeset: String = codeset
        .chars()
        .filter(|c| *c != '-' && *c != '_')
        .flat_map(char::to_lowercase)
        .collect();
    match modifier {
        Some(modifier) => format!("{language}.{codeset}@{modifier}"),
        None => format!("{language}.{codeset}"),
    }
}

/// Where the Docker fallback mounts the absolute host path `host`: under [`CONTAINER_HOST_DIR`],
/// mirroring it, so that `..` leads to the same place on both sides. A Windows drive becomes a
/// directory of its own: `C:\repo` is `/host/C/repo`.
#[must_use]
pub fn container_path(host: &Path) -> String {
    let mut segments: Vec<String> = Vec::new();
    for component in host.components() {
        match component {
            Component::Prefix(prefix) => {
                let drive = prefix.as_os_str().to_string_lossy();
                let drive = drive.trim_start_matches(r"\\?\").trim_end_matches(':');
                segments.push(drive.to_owned());
            }
            Component::RootDir | Component::CurDir => {}
            Component::ParentDir => {
                segments.pop();
            }
            Component::Normal(name) => segments.push(name.to_string_lossy().into_owned()),
        }
    }
    let mut out = CONTAINER_HOST_DIR.to_owned();
    for segment in segments {
        out.push('/');
        out.push_str(&segment);
    }
    out
}

/// Mounts the directory of `config`, which is outside the working directory; returns where the
/// engine sees the config.
fn mount_config(config: &Path, workdir: &Path, mounts: &mut Vec<Mount>) -> Result<String> {
    let directory = config.parent().unwrap_or(config);
    let container = container_path(directory);
    add_mount(directory, &container, workdir, mounts, None)?;
    Ok(format!("{container}/{}", file_name(config)))
}

/// Mounts each schema path `config` names that the engine, reading the config at `seen`, would
/// otherwise look for in a place the container does not have.
///
/// A path that does not exist is left alone: the engine reports it, and binding it would make
/// Docker create an empty directory in its place.
fn mount_config_paths(
    config: &Path,
    seen: &str,
    workdir: &Path,
    mounts: &mut Vec<Mount>,
) -> Result<()> {
    let directory = config.parent().unwrap_or(config);
    for path in config_schema_paths(config) {
        let drive = path.as_bytes().get(1) == Some(&b':') || path.starts_with(r"\\");
        if drive || (cfg!(windows) && Path::new(&path).is_absolute()) {
            return Err(Error::UnreachablePath(format!(
                "{} names the schema path {path:?}, a Windows path the Docker engine cannot \
                 read. Make it relative to the config, or configure a local engine \
                 ({ENV_JAR} / {ENV_BIN}).",
                config.display()
            )));
        }
        let host = normalize(&directory.join(&path));
        if !host.exists() {
            continue;
        }
        let container = posix_normalize(&posix_join(posix_parent(seen), &path.replace('\\', "/")));
        let origin = format!("{} names the schema path {path:?}", config.display());
        add_mount(&host, &container, workdir, mounts, Some(&origin))?;
    }
    Ok(())
}

/// Adds `host` at `container` to `mounts`, unless a mount already shows it there.
///
/// Refuses a `container` that is one of the image's own directories, or inside a directory nothing
/// may be mounted in — except where [`container_path`] put it.
fn add_mount(
    host: &Path,
    container: &str,
    workdir: &Path,
    mounts: &mut Vec<Mount>,
    origin: Option<&str>,
) -> Result<()> {
    let existing = std::iter::once((workdir, CONTAINER_WORKDIR)).chain(
        mounts
            .iter()
            .map(|m| (m.host.as_path(), m.container.as_str())),
    );
    for (mounted, target) in existing {
        if let Ok(rest) = host.strip_prefix(mounted) {
            let rest = rest.to_string_lossy().replace('\\', "/");
            if posix_normalize(&posix_join(target, &rest)) == container {
                return Ok(());
            }
        }
    }
    let mirrored = container == container_path(host) && container != CONTAINER_HOST_DIR;
    if !mirrored && !mountable(container) {
        return Err(Error::UnreachablePath(format!(
            "{}, which the Docker engine would see at {container}, a path inside its image. Make \
             the path lead somewhere else, pass the schema itself, or configure a local engine \
             ({ENV_JAR} / {ENV_BIN}).",
            origin.map_or_else(
                || format!("{:?}", host.display().to_string()),
                str::to_owned
            )
        )));
    }
    mounts.push(Mount {
        host: host.to_path_buf(),
        container: container.to_owned(),
    });
    Ok(())
}

fn mountable(container: &str) -> bool {
    if !container.starts_with('/') || container == "/" {
        return false;
    }
    let inside = |directory: &str| container.starts_with(&format!("{directory}/"));
    !IMAGE_PATHS
        .iter()
        .any(|own| *own == container || own.starts_with(&format!("{container}/")))
        && !NOT_INSIDE.iter().any(|directory| inside(directory))
}

/// The config file the engine would discover from `directory`, if any.
fn nearest_config(directory: &Path) -> Option<PathBuf> {
    directory.ancestors().find_map(|candidate| {
        CONFIG_FILENAMES
            .iter()
            .map(|name| candidate.join(name))
            .find(|path| path.is_file())
    })
}

/// The schema paths `config` names, as written; none when it cannot be read.
///
/// A config the engine will reject anyway is the engine's to report, with its own message.
fn config_schema_paths(config: &Path) -> Vec<String> {
    let Ok(text) = fs::read_to_string(config) else {
        return Vec::new();
    };
    let Ok(document) = serde_json::from_str::<serde_json::Value>(&strip_jsonc(&text)) else {
        return Vec::new();
    };
    let Some(section) = document.get("cimvocabcheck") else {
        return Vec::new();
    };
    let mut paths: Vec<String> = section
        .get("schemas")
        .and_then(serde_json::Value::as_array)
        .map(|paths| {
            paths
                .iter()
                .filter_map(serde_json::Value::as_str)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default();
    if let Some(directory) = section
        .get("schemasDirectory")
        .and_then(serde_json::Value::as_str)
    {
        paths.push(directory.to_owned());
    }
    paths
}

/// `text` without the comments and trailing commas the engine's JSON reader allows.
fn strip_jsonc(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'"' => {
                let start = i;
                i += 1;
                while i < bytes.len() && bytes[i] != b'"' {
                    i += if bytes[i] == b'\\' { 2 } else { 1 };
                }
                i = (i + 1).min(bytes.len());
                out.push_str(&text[start..i]);
            }
            b'/' if matches!(bytes.get(i + 1), Some(b'/' | b'*')) => i = skip_comment(text, i),
            b',' => {
                let next = skip_jsonc_space(text, i + 1);
                if !matches!(bytes.get(next), Some(b']' | b'}')) {
                    out.push(',');
                }
                i += 1;
            }
            _ => {
                let char = text[i..].chars().next().unwrap_or_default();
                out.push(char);
                i += char.len_utf8().max(1);
            }
        }
    }
    out
}

/// The index just past the comment that starts at `i`.
fn skip_comment(text: &str, i: usize) -> usize {
    let (close, extra) = if text[i..].starts_with("//") {
        ("\n", 0)
    } else {
        ("*/", 2)
    };
    text[i + 2..]
        .find(close)
        .map_or(text.len(), |at| i + 2 + at + extra)
}

/// The index of the next byte of `text` from `i` that is neither space nor comment.
fn skip_jsonc_space(text: &str, mut i: usize) -> usize {
    let bytes = text.as_bytes();
    while i < bytes.len() {
        if bytes[i].is_ascii_whitespace() {
            i += 1;
        } else if bytes[i] == b'/' && matches!(bytes.get(i + 1), Some(b'/' | b'*')) {
            i = skip_comment(text, i);
        } else {
            break;
        }
    }
    i
}

/// The file name of `path`, for a container path built from it.
fn file_name(path: &Path) -> String {
    path.file_name()
        .map_or_else(String::new, |name| name.to_string_lossy().into_owned())
}

/// `path`, made absolute and with its symlinks resolved, when it exists. Without the `\\?\`
/// prefix `canonicalize` adds on Windows, which `docker -v` rejects.
fn existing(path: &Path) -> Option<PathBuf> {
    let canonical = path.canonicalize().ok()?;
    let spelled = canonical.to_string_lossy();
    Some(
        spelled
            .strip_prefix(r"\\?\")
            .map_or(canonical.clone(), PathBuf::from),
    )
}

fn posix_parent(path: &str) -> &str {
    match path.rsplit_once('/') {
        Some(("", _)) => "/",
        Some((parent, _)) => parent,
        None => ".",
    }
}

fn posix_join(base: &str, path: &str) -> String {
    if path.starts_with('/') {
        path.to_owned()
    } else {
        format!("{base}/{path}")
    }
}

/// Resolves `.` and `..` in a container path, lexically.
fn posix_normalize(path: &str) -> String {
    let mut segments: Vec<&str> = Vec::new();
    for segment in path.split('/') {
        match segment {
            "" | "." => {}
            ".." => {
                segments.pop();
            }
            other => segments.push(other),
        }
    }
    format!("/{}", segments.join("/"))
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

/// How to find an engine. Every field is optional; [`Discovery::new`] searches the real
/// environment, and each method narrows it.
///
/// ```
/// use cimvocabcheck::Discovery;
/// let options = Discovery::new().cwd("project").no_docker(true);
/// ```
#[non_exhaustive]
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

impl Discovery {
    /// Searches the real environment, in the documented order.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Uses this engine — a `.jar` is run with `java`, anything else is executed.
    #[must_use]
    pub fn engine_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.engine_path = Some(path.into());
        self
    }

    /// Reads this environment instead of the process's own.
    #[must_use]
    pub fn env(mut self, env: HashMap<String, String>) -> Self {
        self.env = Some(env);
        self
    }

    /// The working directory a Docker engine should mount.
    #[must_use]
    pub fn cwd(mut self, cwd: impl Into<PathBuf>) -> Self {
        self.cwd = Some(cwd.into());
        self
    }

    /// Takes the Docker fallback out of the chain, or puts it back.
    #[must_use]
    pub fn no_docker(mut self, no_docker: bool) -> Self {
        self.no_docker = no_docker;
        self
    }
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

    /// An environment, and the variable expected to be set to [`UTF8_LOCALE`].
    type LocaleCase<'a> = (&'a [(&'a str, &'a str)], Option<&'a str>);

    /// What `locale -a` lists on a machine with one locale beyond the built-in ones.
    fn installed() -> BTreeSet<String> {
        ["C", "C.utf8", "POSIX", "en_US.utf8"]
            .iter()
            .map(|&name| name.to_owned())
            .collect()
    }

    fn vars(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect()
    }

    #[cfg(unix)]
    #[test]
    fn a_locale_that_is_named_but_not_installed_is_replaced_for_every_category() {
        // The C library then falls back to C for every category, so only LC_ALL helps.
        let jar = Engine::Jar {
            jar: "cli.jar".into(),
            java: "java".into(),
        };
        let installed = installed();
        let cases: [LocaleCase; 7] = [
            (&[("LANG", "xx_XX.UTF-8")], Some("LC_ALL")),
            // Not every LC_ variable names a locale: terminals set this one, and ssh forwards it.
            (&[("LANG", "en_US.UTF-8"), ("LC_TERMINAL", "iTerm2")], None),
            (
                &[("LANG", "C.UTF-8"), ("LC_MESSAGES", "xx_XX.UTF-8")],
                Some("LC_ALL"),
            ),
            (&[("LC_ALL", "xx_XX.UTF-8")], Some("LC_ALL")),
            (&[("LANG", "en_US.UTF-8")], None),
            (&[("LC_ALL", ""), ("LANG", "en_US.utf8")], None),
            (&[("LC_ALL", "C"), ("LANG", "xx_XX.UTF-8")], Some("LC_ALL")),
        ];
        for (pairs, expected) in cases {
            let expected = expected.map(|variable| (variable, UTF8_LOCALE));
            let env = vars(pairs);
            assert_eq!(
                jar.locale_override_given(Some(&env), Some(&installed)),
                expected,
                "{pairs:?}"
            );
        }
    }

    #[cfg(unix)]
    #[test]
    fn a_locale_is_trusted_when_the_installed_ones_cannot_be_listed() {
        // Without a `locale` command (musl, say) nothing is known, and a named locale is kept.
        let jar = Engine::Jar {
            jar: "cli.jar".into(),
            java: "java".into(),
        };
        let env = vars(&[("LANG", "xx_XX.UTF-8")]);

        assert_eq!(jar.locale_override_given(Some(&env), None), None);
    }

    #[test]
    fn jsonc_loses_its_comments_and_trailing_commas_but_not_its_strings() {
        let text = r#"{
          // a comment
          "a": "keeps // this, and /* this */ and \" quotes,]", /* gone */
          "b": [1, 2,],
          "c": "ü",
        }"#;

        let value: serde_json::Value = serde_json::from_str(&strip_jsonc(text)).unwrap();

        assert_eq!(
            value["a"],
            r#"keeps // this, and /* this */ and " quotes,]"#
        );
        assert_eq!(value["b"], serde_json::json!([1, 2]));
        assert_eq!(value["c"], "ü");
    }

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
        let argv = engine.command(&[], false, None).unwrap();

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
