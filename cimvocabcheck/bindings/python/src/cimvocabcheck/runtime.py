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

import os
import shutil
import sys
import sysconfig
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Union

from .errors import EngineNotFoundError

#: Executable the engine installs as, looked up on ``PATH``.
BINARY_NAME = "cimvocabcheck"

#: Image used by the Docker fallback; override with ``CIMVOCABCHECK_DOCKER_IMAGE``.
DEFAULT_IMAGE = "ghcr.io/soptim/cimvocabcheck-cli:latest"

#: Where the Docker fallback mounts the working directory — the image's own WORKDIR.
CONTAINER_WORKDIR = "/work"

#: An engine JAR placed here (by a packaging step, not by this repository) is used automatically.
BUNDLED_JAR = Path(__file__).resolve().parent / "_bundled" / "cimvocabcheck-cli.jar"

ENV_JAR = "CIMVOCABCHECK_JAR"
ENV_BIN = "CIMVOCABCHECK_BIN"
ENV_IMAGE = "CIMVOCABCHECK_DOCKER_IMAGE"
ENV_NO_DOCKER = "CIMVOCABCHECK_NO_DOCKER"

EngineSpec = Union[str, "os.PathLike[str]", "Engine", None]


class Engine:
    """A way to invoke CIMVocabCheck.

    Subclasses differ only in how a command line is assembled; everything above this layer sees
    one interface and one report format.
    """

    kind = "engine"

    def command(
        self, args: Sequence[str], *, stdin: bool = False, cwd: Path | None = None
    ) -> list[str]:
        """Builds the argv to spawn.

        Never a shell string: inputs and schema paths contain spaces. ``cwd`` is the directory the
        process will start in, which an engine that cannot see the filesystem directly has to
        account for.
        """
        raise NotImplementedError

    def resolve_path(self, path: str, cwd: Path | None = None) -> str:
        """Rewrites one path for this engine. Only the Docker engine needs to."""
        return path

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
        self, args: Sequence[str], *, stdin: bool = False, cwd: Path | None = None
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
        self, args: Sequence[str], *, stdin: bool = False, cwd: Path | None = None
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
        self, args: Sequence[str], *, stdin: bool = False, cwd: Path | None = None
    ) -> list[str]:
        mount = f"{self._mount(cwd)}:{CONTAINER_WORKDIR}"
        run = [self.docker, "run", "--rm"]
        if stdin:
            run.append("-i")
        return [*run, "-v", mount, "-w", CONTAINER_WORKDIR, self.image, *args]

    def resolve_path(self, path: str, cwd: Path | None = None) -> str:
        if path == "-":
            return path
        mount = self._mount(cwd)
        candidate = Path(path)
        absolute = candidate if candidate.is_absolute() else mount / candidate
        try:
            relative = Path(os.path.normpath(str(absolute))).relative_to(mount)
        except ValueError:
            raise ValueError(
                f"{path!r} is outside the working directory {mount}, which is the only path "
                "the Docker engine mounts. Pass paths relative to the working directory, run "
                "from a directory that contains them, or configure a local engine "
                f"({ENV_JAR} / {ENV_BIN})."
            ) from None
        return relative.as_posix()

    def _mount(self, cwd: Path | None) -> Path:
        """The directory to mount: the one the call runs in, else the engine's own.

        An engine is discovered once and may then be used from several working directories, so the
        mount cannot be frozen at discovery time.
        """
        return (Path(cwd) if cwd is not None else self.cwd).resolve()

    def describe(self) -> str:
        return f"{self.docker} run {self.image} (mounting {self.cwd})"


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


def _script_directories() -> list[str]:
    """Directories holding this interpreter's console scripts — including our own launcher.

    Skipping them keeps :func:`discover` from finding the shim it is running inside and recursing
    into itself forever.
    """
    directories = []
    for scheme in (None, "posix_user" if os.name != "nt" else "nt_user"):
        try:
            path = sysconfig.get_path("scripts") if scheme is None else sysconfig.get_path(
                "scripts", scheme=scheme
            )
        except (KeyError, ValueError):  # pragma: no cover - exotic schemes
            continue
        if path:
            directories.append(os.path.normcase(os.path.realpath(path)))
    directories.append(os.path.normcase(os.path.realpath(os.path.dirname(sys.executable))))
    return directories


def _which_engine(env: Mapping[str, str]) -> Path | None:
    search = env.get("PATH")
    skip = set(_script_directories())
    for directory in (search or "").split(os.pathsep):
        if not directory or os.path.normcase(os.path.realpath(directory)) in skip:
            continue
        found = shutil.which(BINARY_NAME, path=directory)
        if found:
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
    if path.suffix.lower() == ".jar":
        return JarEngine(path, java=find_java(env))
    return BinaryEngine(path)
