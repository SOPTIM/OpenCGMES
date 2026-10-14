#    Copyright (c) 2026 SOPTIM AG
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#
#    SPDX-License-Identifier: Apache-2.0

"""Finding the engine to run.

This package ships no engine of its own; it drives the CIMVocabCheck CLI. :func:`discover` looks
for one in a fixed order — an explicitly configured path, the environment, ``PATH``, an artifact
bundled into this package, and finally Docker — and reports every place it looked when it comes up
empty, because "not found" is the failure mode a user actually has to fix.
"""

from __future__ import annotations

import contextlib
import os
import shutil
import subprocess
import uuid
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Union

from .errors import EngineNotFoundError, UnreachablePathError

#: Executable the engine installs as, looked up on ``PATH``.
BINARY_NAME = "cimvocabcheck"

#: Image used by the Docker fallback; override with ``CIMVOCABCHECK_DOCKER_IMAGE``.
DEFAULT_IMAGE = "ghcr.io/soptim/cimvocabcheck-cli:latest"

#: Where the Docker fallback mounts the working directory — the image's own WORKDIR.
CONTAINER_WORKDIR = "/work"

#: Where the Docker fallback mounts, read-only, the directory of a config file that lies above the
#: working directory and would otherwise be invisible to the engine.
CONTAINER_CONFIG_DIR = "/config"

#: Where the Docker fallback mounts, read-only and one numbered directory each, schema paths that
#: lie outside the working directory.
CONTAINER_SCHEMA_DIR = "/schemas"

#: A host path the Docker engine bind-mounts read-only for one call, and where the engine sees it.
Mount = tuple[Path, str]

#: Config file names the engine discovers, in the order it prefers them.
CONFIG_FILENAMES = ("opencgmes.jsonc", "opencgmes.json")

#: Flags that name a schema source. When a call passes one, the engine does no config discovery.
_SCHEMA_SOURCE_FLAGS = ("-c", "--config", "-s", "--schema", "-e", "--endpoint")

#: An engine JAR placed here (by a packaging step, not by this repository) is used automatically.
BUNDLED_JAR = Path(__file__).resolve().parent / "_bundled" / "cimvocabcheck-cli.jar"

ENV_JAR = "CIMVOCABCHECK_JAR"
ENV_BIN = "CIMVOCABCHECK_BIN"
ENV_IMAGE = "CIMVOCABCHECK_DOCKER_IMAGE"
ENV_NO_DOCKER = "CIMVOCABCHECK_NO_DOCKER"

#: Set by the ``cimvocabcheck`` shim for the process it forwards to: the directories it has already
#: forwarded into, ``os.pathsep``-separated. Shims are recognised by their content (see
#: :func:`_is_shim`); this is the backstop for one that is not, so a chain of shims ends instead of
#: forwarding to each other forever.
ENV_SHIM_CHAIN = "CIMVOCABCHECK_SHIM_CHAIN"

#: Text every copy of this package's console script carries: pip and uv write it into the generated
#: script, and on Windows into the zip appended to the launcher ``.exe``. No engine contains it.
SHIM_MARKER = b"cimvocabcheck.__main__"

#: How much of each end of a candidate is searched for :data:`SHIM_MARKER`.
_SHIM_SCAN_BYTES = 64 * 1024

#: The locale a local engine is handed when the caller's would have it read file names as ASCII.
UTF8_LOCALE = "C.UTF-8"

EngineSpec = Union[str, "os.PathLike[str]", "Engine", None]


class Engine:
    """A way to invoke CIMVocabCheck.

    Subclasses differ only in how a command line is assembled; everything above this layer sees
    one interface and one report format.
    """

    kind = "engine"

    def command(
        self,
        args: Sequence[str],
        *,
        stdin: bool = False,
        cwd: Path | None = None,
        mounts: Sequence[Mount] = (),
    ) -> list[str]:
        """Builds the argv to spawn.

        Never a shell string: inputs and schema paths contain spaces. ``cwd`` is the directory the
        process will start in, which an engine that cannot see the filesystem directly has to
        account for. ``mounts`` are the ones :meth:`resolve_config` and :meth:`resolve_schema`
        collected; only the Docker engine has any.
        """
        raise NotImplementedError

    def resolve_path(self, path: str, cwd: Path | None = None) -> str:
        """Rewrites one path for this engine. Only the Docker engine needs to."""
        return path

    def resolve_config(self, path: str, cwd: Path | None, mounts: list[Mount]) -> str:
        """Rewrites a config file path; the Docker engine may mount it instead of refusing it."""
        return self.resolve_path(path, cwd)

    def resolve_schema(self, path: str, cwd: Path | None, mounts: list[Mount]) -> str:
        """Rewrites a schema path; the Docker engine may mount it instead of refusing it."""
        return self.resolve_path(path, cwd)

    def environment(self, env: Mapping[str, str] | None = None) -> dict[str, str] | None:
        """The environment to spawn this engine with: ``env``, or this process's when ``None``.

        A local engine is a JVM, which encodes file names in the locale's charset: under a C or
        POSIX locale (or none, as in many containers and CI images) a non-ASCII input name cannot
        even be opened. Such a locale is upgraded to :data:`UTF8_LOCALE`; any real locale is left
        alone. ``None`` means "inherit unchanged".
        """
        return utf8_environment(env)

    def abandon(self, argv: Sequence[str], env: Mapping[str, str] | None = None) -> None:
        """Cleans up after the process spawned from ``argv`` was killed before it finished.

        Only the Docker engine leaves anything behind: killing ``docker run`` stops the client,
        not the container.
        """

    def __str__(self) -> str:  # pragma: no cover - trivial
        return self.describe()

    def describe(self) -> str:
        raise NotImplementedError


class JarEngine(Engine):
    """A fat JAR run with ``java -jar``."""

    kind = "jar"

    def __init__(self, jar: str | os.PathLike[str], java: str | None = None) -> None:
        self.jar = Path(jar)
        self.java = java or find_java()

    def command(
        self,
        args: Sequence[str],
        *,
        stdin: bool = False,
        cwd: Path | None = None,
        mounts: Sequence[Mount] = (),
    ) -> list[str]:
        return [self.java, "-jar", str(self.jar), *args]

    def describe(self) -> str:
        return f"{self.java} -jar {self.jar}"


class BinaryEngine(Engine):
    """An executable engine — the CLI's launcher script, or a native binary."""

    kind = "binary"

    def __init__(self, binary: str | os.PathLike[str]) -> None:
        self.binary = Path(binary)

    def command(
        self,
        args: Sequence[str],
        *,
        stdin: bool = False,
        cwd: Path | None = None,
        mounts: Sequence[Mount] = (),
    ) -> list[str]:
        return [str(self.binary), *args]

    def describe(self) -> str:
        return str(self.binary)


class DockerEngine(Engine):
    """The published container image, with the working directory mounted at its WORKDIR.

    Inputs are therefore addressed relative to the working directory. That is what the report
    contract asks for anyway — the paths in a report are the paths that were passed in, and CI
    annotations only line up when they are repository-relative.
    """

    kind = "docker"

    def __init__(
        self,
        image: str = DEFAULT_IMAGE,
        docker: str = "docker",
        cwd: Path | None = None,
    ) -> None:
        self.image = image
        self.docker = docker
        self.cwd = Path(cwd) if cwd is not None else Path.cwd()

    def command(
        self,
        args: Sequence[str],
        *,
        stdin: bool = False,
        cwd: Path | None = None,
        mounts: Sequence[Mount] = (),
    ) -> list[str]:
        mount = self._mount(cwd)
        # Named, so a run that has to be killed can be found and removed again.
        run = [self.docker, "run", "--rm", "--name", f"cimvocabcheck-{uuid.uuid4().hex}"]
        if stdin:
            run.append("-i")
        run += ["-v", f"{mount}:{CONTAINER_WORKDIR}"]
        config = None if _names_schema_source(args) else _config_above(mount)
        if config is not None:
            # The engine discovers its config upward from the working directory, which inside the
            # container stops at the mount: a config above it would be silently missed, leaving a
            # syntax-only check that reports every query as valid.
            run += ["-v", f"{config.parent}:{CONTAINER_CONFIG_DIR}:ro"]
            args = ["--config", f"{CONTAINER_CONFIG_DIR}/{config.name}", *args]
        for host, container in mounts:
            run += ["-v", f"{host}:{container}:ro"]
        return [*run, "-w", CONTAINER_WORKDIR, self.image, *args]

    def resolve_config(self, path: str, cwd: Path | None, mounts: list[Mount]) -> str:
        """Like :meth:`resolve_path`, but a config outside the working directory is mounted.

        Its directory goes to :data:`CONTAINER_CONFIG_DIR` read-only, so the config's own relative
        schema paths keep resolving — exactly as for a config discovered above the working
        directory.
        """
        host = self._outside(path, cwd)
        if host is None:
            return self.resolve_path(path, cwd)
        mounts.append((host.parent, CONTAINER_CONFIG_DIR))
        return f"{CONTAINER_CONFIG_DIR}/{host.name}"

    def resolve_schema(self, path: str, cwd: Path | None, mounts: list[Mount]) -> str:
        """Like :meth:`resolve_path`, but a schema outside the working directory is mounted.

        Each goes read-only to its own directory under :data:`CONTAINER_SCHEMA_DIR`.
        """
        host = self._outside(path, cwd)
        if host is None:
            return self.resolve_path(path, cwd)
        container = f"{CONTAINER_SCHEMA_DIR}/{len(mounts)}/{host.name}"
        mounts.append((host, container))
        return container

    def _outside(self, path: str, cwd: Path | None) -> Path | None:
        """The resolved host path when ``path`` exists but lies outside the mount, else ``None``.

        A path that does not exist is left to :meth:`resolve_path` to refuse: binding it would
        make Docker create an empty directory in its place.
        """
        try:
            self.resolve_path(path, cwd)
            return None
        except UnreachablePathError:
            pass
        try:
            return (self._mount(cwd) / path).resolve(strict=True)
        except OSError:
            return None

    def resolve_path(self, path: str, cwd: Path | None = None) -> str:
        if path == "-":
            return path
        mount = self._mount(cwd)
        lexical_mount = Path(os.path.abspath(Path(cwd) if cwd is not None else self.cwd))
        candidate = Path(path)
        lexical = Path(
            os.path.normpath(candidate if candidate.is_absolute() else lexical_mount / candidate)
        )
        # The mount has its symlinks resolved and an absolute input may not, or the other way
        # round; the input is inside when either spelling of it is.
        spellings = ((lexical, lexical_mount), (lexical, mount), (lexical.resolve(), mount))
        for inside, root in spellings:
            try:
                return inside.relative_to(root).as_posix()
            except ValueError:
                continue
        raise UnreachablePathError(
            f"{path!r} is outside the working directory {mount}, which is the only path "
            "the Docker engine mounts. Pass paths relative to the working directory, run "
            "from a directory that contains them, or configure a local engine "
            f"({ENV_JAR} / {ENV_BIN})."
        )

    def environment(self, env: Mapping[str, str] | None = None) -> dict[str, str] | None:
        # The engine runs inside the image, under the image's locale; the client needs nothing.
        return dict(env) if env is not None else None

    def abandon(self, argv: Sequence[str], env: Mapping[str, str] | None = None) -> None:
        if "--name" not in argv:
            return
        name = argv[list(argv).index("--name") + 1]
        # Best effort: the timeout being reported matters more than this cleanup.
        with contextlib.suppress(OSError, subprocess.TimeoutExpired):
            subprocess.run(  # noqa: S603 - argv list, never a shell string
                [self.docker, "rm", "--force", name],
                capture_output=True,
                env=dict(env) if env is not None else None,
                timeout=30,
            )

    def _mount(self, cwd: Path | None) -> Path:
        """The directory to mount: the one the call runs in, else the engine's own.

        An engine is discovered once and may then be used from several working directories, so the
        mount cannot be frozen at discovery time.
        """
        return (Path(cwd) if cwd is not None else self.cwd).resolve()

    def describe(self) -> str:
        return f"{self.docker} run {self.image} (mounting {self.cwd})"


def utf8_environment(env: Mapping[str, str] | None) -> dict[str, str] | None:
    """``env`` with a C/POSIX/unset character locale replaced by :data:`UTF8_LOCALE`.

    The variable that decides is the one POSIX gives precedence to — ``LC_ALL``, else ``LC_CTYPE``,
    else ``LANG`` — and it is overridden in place, so a deliberate ``LC_ALL=C`` keeps its collation
    and messages and only gains UTF-8. Returns ``None`` when ``env`` is ``None`` and nothing needed
    changing, so the child simply inherits. Windows takes no locale from the environment.
    """
    if os.name == "nt":
        return dict(env) if env is not None else None
    environ = dict(os.environ if env is None else env)
    effective = environ.get("LC_ALL") or environ.get("LC_CTYPE") or environ.get("LANG") or ""
    if effective not in ("", "C", "POSIX"):
        return dict(env) if env is not None else None
    environ["LC_ALL" if environ.get("LC_ALL") else "LC_CTYPE"] = UTF8_LOCALE
    return environ


def _config_above(directory: Path) -> Path | None:
    """The config file the engine would discover from ``directory``, if it lies above it.

    ``None`` when the nearest config is inside ``directory`` (the engine finds that one itself) or
    when there is none at all.
    """
    for candidate in (directory, *directory.parents):
        for name in CONFIG_FILENAMES:
            if (candidate / name).is_file():
                return None if candidate == directory else candidate / name
    return None


def _names_schema_source(args: Sequence[str]) -> bool:
    """Whether ``args`` choose a schema source themselves, so no config is discovered."""
    for arg in args:
        if arg == "--":
            return False
        if arg.startswith("--"):
            if arg.split("=", 1)[0] in _SCHEMA_SOURCE_FLAGS:
                return True
        elif arg[:2] in _SCHEMA_SOURCE_FLAGS:
            return True
    return False


def find_java(env: Mapping[str, str] | None = None) -> str:
    """Locates a Java launcher for the JAR engines: ``JAVA_HOME`` first, then ``PATH``."""
    environ = os.environ if env is None else env
    java_home = environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java", path=environ.get("PATH"))
    if found:
        return found
    raise EngineNotFoundError(
        "a CIMVocabCheck JAR was selected but no Java launcher was found. The JAR engine needs a "
        "JRE 21 or newer; install one, or set JAVA_HOME.",
    )


def _is_shim(path: str | os.PathLike[str]) -> bool:
    """Whether ``path`` is a copy of this package's console script rather than an engine.

    Recognised by content, not location: the directories Python installs console scripts into
    (``/usr/local/bin``, ``~/.local/bin``, ``/usr/bin``, ``/opt/homebrew/bin``) are exactly where an
    engine gets installed too, so skipping them would hide it. Both ends are read because a Windows
    launcher carries its script in a zip appended to the executable.
    """
    try:
        with open(path, "rb") as handle:
            head = handle.read(_SHIM_SCAN_BYTES)
            size = handle.seek(0, os.SEEK_END)
            handle.seek(max(len(head), size - _SHIM_SCAN_BYTES))
            tail = handle.read()
    except OSError:
        return False
    return SHIM_MARKER in head or SHIM_MARKER in tail


def _normalized(directory: str | os.PathLike[str]) -> str:
    return os.path.normcase(os.path.realpath(directory))


def _shim_chain(env: Mapping[str, str]) -> list[str]:
    return [_normalized(entry) for entry in env.get(ENV_SHIM_CHAIN, "").split(os.pathsep) if entry]


def shim_environment(engine: Engine, env: Mapping[str, str] | None = None) -> dict[str, str]:
    """The environment the shim hands to ``engine``: ``env`` with the shim chain extended.

    Records, for an executable engine, the directory it lives in. If that executable turns out to
    be a copy of the shim :func:`_is_shim` did not recognise, its own discovery then skips
    everything already visited, so a chain of shims ends instead of looping.
    """
    environ = engine.environment(env) or dict(os.environ if env is None else env)
    chain = _shim_chain(environ)
    if isinstance(engine, BinaryEngine):
        chain.append(_normalized(engine.binary.parent))
    environ[ENV_SHIM_CHAIN] = os.pathsep.join(dict.fromkeys(chain))
    return environ


def _which_engine(env: Mapping[str, str]) -> Path | None:
    search = env.get("PATH")
    skip = set(_shim_chain(env))
    for directory in (search or "").split(os.pathsep):
        if not directory or _normalized(directory) in skip:
            continue
        found = shutil.which(BINARY_NAME, path=directory)
        if found and not _is_shim(found):
            return Path(found)
    return None


def _truthy(value: str | None) -> bool:
    """Reads an on/off environment variable the way a shell user expects."""
    return value is not None and value.strip().lower() not in {"", "0", "false", "no"}


def discover(
    engine: EngineSpec = None,
    *,
    env: Mapping[str, str] | None = None,
    cwd: str | os.PathLike[str] | None = None,
    allow_docker: bool = True,
) -> Engine:
    """Returns the engine to run, searching in the documented order.

    The order is deliberate: an explicit choice beats the environment, the environment beats
    whatever happens to be installed, and Docker is the last resort because it is the slowest and
    the only one that can reach the network to fetch itself.

    Raises:
        EngineNotFoundError: when nothing was found; the message lists everywhere that was tried.
    """
    environ = dict(os.environ if env is None else env)
    working = Path(cwd) if cwd is not None else Path.cwd()
    searched: list[str] = []

    if isinstance(engine, Engine):
        return engine
    if engine is not None:
        return _engine_for_path(Path(engine), environ, "the engine passed to this call")

    for variable in (ENV_JAR, ENV_BIN):
        value = environ.get(variable)
        searched.append(f"${variable} ({value or 'unset'})")
        if value:
            return _engine_for_path(Path(value), environ, f"${variable}")

    searched.append(f"{BINARY_NAME!r} on PATH")
    on_path = _which_engine(environ)
    if on_path is not None:
        return BinaryEngine(on_path)

    searched.append(f"a JAR bundled in this package ({BUNDLED_JAR})")
    if BUNDLED_JAR.is_file():
        return JarEngine(BUNDLED_JAR, java=find_java(environ))

    image = environ.get(ENV_IMAGE) or DEFAULT_IMAGE
    docker_disabled = _truthy(environ.get(ENV_NO_DOCKER)) or not allow_docker
    why = "disabled" if docker_disabled else "requires 'docker' on PATH"
    searched.append(f"the Docker image {image} ({why})")
    if not docker_disabled:
        docker = shutil.which("docker", path=environ.get("PATH"))
        if docker:
            return DockerEngine(image=image, docker=docker, cwd=working)

    raise EngineNotFoundError(
        "no CIMVocabCheck engine found. Install the CLI and put it on PATH, point "
        f"{ENV_JAR} at a fat JAR, or make Docker available for the container fallback.",
        searched,
    )


def _engine_for_path(path: Path, env: Mapping[str, str], origin: str) -> Engine:
    if not path.exists():
        raise EngineNotFoundError(f"{origin} points at {path}, which does not exist.")
    # Checked against this process's directory, so pinned to it: the engine may run in another.
    path = path.absolute()
    if path.suffix.lower() == ".jar":
        return JarEngine(path, java=find_java(env))
    if _normalized(path.parent) in _shim_chain(env) or _is_shim(path):
        raise EngineNotFoundError(
            f"{origin} points at {path}, which is the cimvocabcheck Python shim, not an engine. "
            "Point it at the engine's own launcher or native binary."
        )
    return BinaryEngine(path)
