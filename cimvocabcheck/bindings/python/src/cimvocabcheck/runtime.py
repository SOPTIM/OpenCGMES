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
import functools
import json
import os
import posixpath
import shutil
import subprocess
import uuid
from collections.abc import Mapping, Sequence
from pathlib import Path, PurePath, PureWindowsPath
from typing import Any, Union

from .errors import EngineNotFoundError, UnreachablePathError

#: Executable the engine installs as, looked up on ``PATH``.
BINARY_NAME = "cimvocabcheck"

#: Image used by the Docker fallback; override with ``CIMVOCABCHECK_DOCKER_IMAGE``.
DEFAULT_IMAGE = "ghcr.io/soptim/cimvocabcheck-cli:latest"

#: Where the Docker fallback mounts the working directory — the image's own WORKDIR.
CONTAINER_WORKDIR = "/work"

#: Where the Docker fallback mounts, read-only, what it has to reach outside the working directory:
#: a config, the schema paths it names, a schema passed in. Each goes to this directory followed by
#: its absolute host path (see :func:`container_path`), so a config's relative paths lead to the
#: same places in the container as on the host.
CONTAINER_HOST_DIR = "/host"

#: Container paths a mount must neither be nor contain: the image's own directories and the mounts
#: this module makes.
_IMAGE_PATHS = (
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
)

#: Container directories nothing may be mounted inside: other mounts, and the kernel's.
_NOT_INSIDE = (CONTAINER_WORKDIR, CONTAINER_HOST_DIR, "/proc", "/sys", "/dev")

#: A host path the Docker engine bind-mounts read-only for one call, and where the engine sees it.
Mount = tuple[Path, str]

#: Config file names the engine discovers, in the order it prefers them.
CONFIG_FILENAMES = ("opencgmes.jsonc", "opencgmes.json")

#: The engine's subcommands. Each takes options of its own; the top level is under ``None``.
SUBCOMMANDS = ("explain", "init", "help")

#: Per command, the options that take a value, and which of those values are paths: ``config``,
#: ``schema`` and ``path`` (anything else, as ``-f`` for ``--format``, is passed through as is).
#: ``-f`` is a flag of ``init`` (``--force``), which is why this cannot be one table.
VALUE_OPTIONS: dict[str | None, dict[str, str | None]] = {
    None: {
        "-c": "config",
        "--config": "config",
        "-s": "schema",
        "--schema": "schema",
        "-e": None,
        "--endpoint": None,
        "-p": None,
        "--profile": None,
        "-f": None,
        "--format": None,
        "--strictness": None,
    },
    "explain": {"-c": "config", "--config": "config", "-s": "schema", "--schema": "schema"},
    "init": {"-d": "path", "--dir": "path"},
    "help": {},
}

#: Subcommands that read no schema, so are handed no discovered config.
_SCHEMALESS_SUBCOMMANDS = ("init", "help")

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

#: The variables that name a locale: ``LANG`` and the categories, POSIX's and glibc's. Not every
#: ``LC_`` variable is one — terminals set ``LC_TERMINAL``, and ssh forwards ``LC_*`` wholesale.
_LOCALE_VARIABLES = frozenset(
    {
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
    }
)

#: Locales the C library always has, whatever is installed.
_BUILTIN_LOCALES = frozenset({"C", "POSIX", "C.utf8"})

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
        mounts = list(mounts)
        subcommand = _subcommand_at(args)
        schemaless = subcommand is not None and args[subcommand] in _SCHEMALESS_SUBCOMMANDS
        config = None if schemaless or _names_schema_source(args) else _nearest_config(mount)
        if config is not None:
            if config.parent == mount:
                seen = f"{CONTAINER_WORKDIR}/{config.name}"
            else:
                # The engine discovers its config upward from the working directory, which inside
                # the container stops at the mount: a config above it would be silently missed,
                # leaving a syntax-only check that reports every query as valid. The flag goes to
                # the command that reads it: one placed before a subcommand belongs to the top
                # level, and the subcommand would discover nothing.
                seen = self._mount_config(config, mount, mounts)
                at = 0 if subcommand is None else subcommand + 1
                args = [*args[:at], "--config", seen, *args[at:]]
            self._mount_config_paths(config, seen, mount, mounts)
        for host, container in mounts:
            run += ["-v", f"{host}:{container}:ro"]
        return [*run, "-w", CONTAINER_WORKDIR, self.image, *args]

    def resolve_config(self, path: str, cwd: Path | None, mounts: list[Mount]) -> str:
        """Like :meth:`resolve_path`, but a config outside the working directory is mounted.

        Its directory is mounted read-only under :data:`CONTAINER_HOST_DIR`, and so is every schema
        path the config names that the container could not otherwise reach, so the config's own
        relative paths resolve as they do on the host — exactly as for a config the engine
        discovers.
        """
        mount = self._mount(cwd)
        host = self._outside(path, cwd)
        if host is None:
            resolved = self.resolve_path(path, cwd)
            seen = posixpath.normpath(posixpath.join(CONTAINER_WORKDIR, resolved))
            self._mount_config_paths((mount / path).resolve(), seen, mount, mounts)
            return resolved
        seen = self._mount_config(host, mount, mounts)
        self._mount_config_paths(host, seen, mount, mounts)
        return seen

    def resolve_schema(self, path: str, cwd: Path | None, mounts: list[Mount]) -> str:
        """Like :meth:`resolve_path`, but a schema outside the working directory is mounted.

        It is mounted read-only under :data:`CONTAINER_HOST_DIR`, at :func:`container_path`.
        """
        host = self._outside(path, cwd)
        if host is None:
            return self.resolve_path(path, cwd)
        container = container_path(host)
        _add_mount(host, container, self._mount(cwd), mounts)
        return container

    @staticmethod
    def _mount_config(config: Path, mount: Path, mounts: list[Mount]) -> str:
        """Mounts the directory of ``config``, which is outside the working directory; returns
        where the engine sees the config."""
        directory = container_path(config.parent)
        _add_mount(config.parent, directory, mount, mounts)
        return f"{directory}/{config.name}"

    @staticmethod
    def _mount_config_paths(config: Path, seen: str, mount: Path, mounts: list[Mount]) -> None:
        """Mounts each schema path ``config`` names that the engine, reading the config at
        ``seen``, would otherwise look for in a place the container does not have.

        A path that does not exist is left alone: the engine reports it, and binding it would make
        Docker create an empty directory in its place.
        """
        for path in _config_schema_paths(config):
            if PureWindowsPath(path).drive or (os.name == "nt" and PurePath(path).is_absolute()):
                raise UnreachablePathError(
                    f"{config} names the schema path {path!r}, a Windows path the Docker engine "
                    "cannot read. Make it relative to the config, or configure a local engine "
                    f"({ENV_JAR} / {ENV_BIN})."
                )
            host = Path(os.path.normpath(config.parent / path))
            if not host.exists():
                continue
            container = posixpath.normpath(
                posixpath.join(posixpath.dirname(seen), PurePath(path).as_posix())
            )
            _add_mount(host, container, mount, mounts, f"{config} names the schema path {path!r}")

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
    """``env`` changed so that the engine's character locale is UTF-8, when it would not be.

    Sets :data:`UTF8_LOCALE` in one of two cases:

    - A named locale is not installed: ``LC_ALL``, since the C library then falls back to C for
      every category.
    - The locale in force is C, POSIX or unset: ``LC_ALL`` if that is set, else ``LC_CTYPE``, so
      a deliberate ``LC_ALL=C`` keeps everything but its charset.

    Returns ``None`` when ``env`` is ``None`` and nothing needed changing, so the child inherits.
    Windows takes no locale from the environment.
    """
    if os.name == "nt":
        return dict(env) if env is not None else None
    environ = dict(os.environ if env is None else env)
    named = (
        [environ["LC_ALL"]]
        if environ.get("LC_ALL")
        else [value for key, value in environ.items() if key in _LOCALE_VARIABLES]
    )
    effective = environ.get("LC_ALL") or environ.get("LC_CTYPE") or environ.get("LANG") or ""
    installed = installed_locales()
    if installed is not None and any(not _is_installed(name, installed) for name in named if name):
        environ["LC_ALL"] = UTF8_LOCALE
    elif effective in ("", "C", "POSIX"):
        environ["LC_ALL" if environ.get("LC_ALL") else "LC_CTYPE"] = UTF8_LOCALE
    else:
        return dict(env) if env is not None else None
    return environ


@functools.lru_cache(maxsize=1)
def installed_locales() -> frozenset[str] | None:
    """The locales ``locale -a`` lists, or ``None`` when that cannot be found out.

    Asked once per process. Without a ``locale`` command (musl-based images have none, and musl
    reads every locale as UTF-8 anyway) nothing is known, and a named locale is trusted.
    """
    command = shutil.which("locale")
    if command is None:
        return None
    try:
        listed = subprocess.run(  # noqa: S603 - argv list, never a shell string
            [command, "-a"], capture_output=True, text=True, timeout=10, check=True
        ).stdout
    except (OSError, subprocess.SubprocessError, UnicodeDecodeError):
        return None
    return frozenset(line.strip() for line in listed.splitlines() if line.strip())


def _is_installed(name: str, installed: frozenset[str]) -> bool:
    """Whether ``name`` is one of ``installed``, however the codeset is spelled."""
    wanted = _locale_key(name)
    return any(_locale_key(entry) == wanted for entry in _BUILTIN_LOCALES | installed)


def _locale_key(name: str) -> str:
    """``en_US.UTF-8`` and ``en_US.utf8`` name one locale; this is what they have in common."""
    language, dot, rest = name.partition(".")
    if not dot:
        return name
    codeset, at, modifier = rest.partition("@")
    return f"{language}.{codeset.lower().replace('-', '').replace('_', '')}{at}{modifier}"


def container_path(host: PurePath) -> str:
    """Where the Docker fallback mounts the absolute host path ``host``: under
    :data:`CONTAINER_HOST_DIR`, mirroring it, so that ``..`` leads to the same place on both sides.

    A Windows drive becomes a directory of its own: ``C:\\repo`` is ``/host/C/repo``.
    """
    if isinstance(host, PureWindowsPath) or (os.name == "nt" and host.drive):
        windows = PureWindowsPath(host)
        rest = "/".join(windows.parts[1:])
        return posixpath.join(CONTAINER_HOST_DIR, windows.drive.rstrip(":\\/"), rest).rstrip("/")
    return posixpath.normpath(CONTAINER_HOST_DIR + "/" + PurePath(host).as_posix())


def _add_mount(
    host: Path, container: str, workdir: Path, mounts: list[Mount], origin: str | None = None
) -> None:
    """Adds ``host`` at ``container`` to ``mounts``, unless a mount already shows it there.

    Raises:
        UnreachablePathError: ``container`` is one of the image's own directories, or inside a
            directory nothing may be mounted in — except where :func:`container_path` put it.
    """
    for mounted, target in ((workdir, CONTAINER_WORKDIR), *mounts):
        try:
            rest = host.relative_to(mounted).as_posix()
        except ValueError:
            continue
        if posixpath.normpath(posixpath.join(target, rest)) == container:
            return
    mirrored = container == container_path(host) and container != CONTAINER_HOST_DIR
    if not mirrored and not _mountable(container):
        raise UnreachablePathError(
            f"{origin or repr(str(host))}, which the Docker engine would see at {container}, a "
            "path inside its image. Make the path lead somewhere else, pass the schema itself, or "
            f"configure a local engine ({ENV_JAR} / {ENV_BIN})."
        )
    mounts.append((host, container))


def _mountable(container: str) -> bool:
    if not container.startswith("/") or container == "/":
        return False
    if any(own == container or own.startswith(container + "/") for own in _IMAGE_PATHS):
        return False
    return not any(container.startswith(directory + "/") for directory in _NOT_INSIDE)


def _nearest_config(directory: Path) -> Path | None:
    """The config file the engine would discover from ``directory``, or ``None``."""
    for candidate in (directory, *directory.parents):
        for name in CONFIG_FILENAMES:
            if (candidate / name).is_file():
                return candidate / name
    return None


def _config_schema_paths(config: Path) -> list[str]:
    """The schema paths ``config`` names, as written; none when it cannot be read.

    A config the engine will reject anyway is the engine's to report, with its own message.
    """
    try:
        document: Any = json.loads(_strip_jsonc(config.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        return []
    section = document.get("cimvocabcheck") if isinstance(document, dict) else None
    if not isinstance(section, dict):
        return []
    paths = section.get("schemas")
    named = [path for path in paths if isinstance(path, str)] if isinstance(paths, list) else []
    directory = section.get("schemasDirectory")
    return [*named, directory] if isinstance(directory, str) else named


def _strip_jsonc(text: str) -> str:
    """``text`` without the comments and trailing commas the engine's JSON reader allows."""
    out: list[str] = []
    i, length = 0, len(text)
    while i < length:
        char = text[i]
        if char == '"':
            end = i + 1
            while end < length and text[end] != '"':
                end += 2 if text[end] == "\\" else 1
            out.append(text[i : end + 1])
            i = end + 1
        elif text.startswith("//", i):
            newline = text.find("\n", i)
            i = length if newline < 0 else newline
        elif text.startswith("/*", i):
            close = text.find("*/", i + 2)
            i = length if close < 0 else close + 2
        elif char == ",":
            following = _skip_jsonc_space(text, i + 1)
            if following >= length or text[following] not in "]}":
                out.append(char)
            i += 1
        else:
            out.append(char)
            i += 1
    return "".join(out)


def _skip_jsonc_space(text: str, i: int) -> int:
    """The index of the next character of ``text`` from ``i`` that is neither space nor comment."""
    while i < len(text):
        if text[i].isspace():
            i += 1
        elif text.startswith("//", i):
            newline = text.find("\n", i)
            i = len(text) if newline < 0 else newline
        elif text.startswith("/*", i):
            close = text.find("*/", i + 2)
            i = len(text) if close < 0 else close + 2
        else:
            break
    return i


def _subcommand_at(args: Sequence[str]) -> int | None:
    """The index of the subcommand in ``args``, or ``None`` when they run the top-level command.

    A subcommand can only be the first word that is neither an option nor an option's value.
    """
    takes_value = VALUE_OPTIONS[None]
    pending = False
    for index, arg in enumerate(args):
        if pending:
            pending = False
        elif arg == "--":
            return None
        elif arg == "-" or not arg.startswith("-"):
            return index if arg in SUBCOMMANDS else None
        elif arg.startswith("--"):
            pending = "=" not in arg and arg in takes_value
        else:
            pending = len(arg) == 2 and arg in takes_value
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
