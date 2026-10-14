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

"""``python -m cimvocabcheck`` — the engine's own CLI, without having to locate it first.

Arguments are passed straight through, so this is the real CLI: the same flags, the same output
formats and the same exit codes (0 clean, 1 findings, 2 usage). All this layer adds is finding an
engine to run, which is what makes the package usable as a ``pre-commit`` hook — and, for the
Docker engine, the paths: those it names are rewritten to where the container sees them, mounting
what lies outside the working directory as the library does.
"""

from __future__ import annotations

import subprocess
import sys
from collections.abc import Callable, Sequence

from .errors import CimVocabCheckError
from .runtime import DockerEngine, Mount, discover, shim_environment

#: Reports which engine was discovered and exits, without running a validation.
PRINT_ENGINE_FLAG = "--print-engine"

USAGE_EXIT = 2

#: The engine's subcommands. Each takes options of its own; the top level is under ``None``.
_SUBCOMMANDS = ("explain", "init", "help")

#: Per command, the options that take a value, and which of those values are paths: ``config``,
#: ``schema`` and ``path`` (anything else, as ``-f`` for ``--format``, is passed through as is).
#: ``-f`` is a flag of ``init`` (``--force``), which is why this cannot be one table.
_VALUE_OPTIONS: dict[str | None, dict[str, str | None]] = {
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


def main(argv: Sequence[str] | None = None) -> int:
    """Runs the engine with ``argv``, inheriting this process's stdin, stdout and stderr."""
    args: list[str] = list(sys.argv[1:] if argv is None else argv)
    try:
        engine = discover()
        if PRINT_ENGINE_FLAG in args:
            print(engine.describe())
            return 0
        mounts: list[Mount] = []
        if isinstance(engine, DockerEngine):
            args = docker_arguments(engine, args, mounts)
        command = engine.command(args, stdin="-" in args, mounts=mounts)
    except CimVocabCheckError as error:
        print(f"cimvocabcheck: {error}", file=sys.stderr)
        return USAGE_EXIT

    try:
        env = shim_environment(engine)
        return subprocess.call(command, env=env)  # noqa: S603 - argv list, never a shell string
    except OSError as error:
        print(f"cimvocabcheck: could not start {engine.describe()}: {error}", file=sys.stderr)
        return USAGE_EXIT
    except KeyboardInterrupt:  # pragma: no cover - interactive only
        return 130


def docker_arguments(engine: DockerEngine, args: Sequence[str], mounts: list[Mount]) -> list[str]:
    """``args`` with every path in them rewritten to where the Docker engine sees it.

    A config or schema outside the working directory is mounted, adding to ``mounts``; an input
    outside it is refused, as the library refuses it, rather than reported as unreadable.

    Raises:
        UnreachablePathError: A path cannot be reached from inside the container.
    """
    rewrite: dict[str | None, Callable[[str], str]] = {
        "config": lambda path: engine.resolve_config(path, None, mounts),
        "schema": lambda path: engine.resolve_schema(path, None, mounts),
        "path": lambda path: engine.resolve_path(path),
    }
    keep: Callable[[str], str] = lambda value: value  # noqa: E731
    out: list[str] = []
    command: str | None = None
    pending: Callable[[str], str] | None = None
    inputs_only = False
    positional = False
    for arg in args:
        options = _VALUE_OPTIONS[command]
        if pending is not None:
            out.append(pending(arg))
            pending = None
        elif inputs_only or arg == "-" or not arg.startswith("-"):
            # A subcommand can only be the first word that is not an option.
            if not inputs_only and not positional and arg in _SUBCOMMANDS:
                command = arg
                out.append(arg)
            else:
                out.append(engine.resolve_path(arg))
            positional = True
        elif arg == "--":
            inputs_only = True
            out.append(arg)
        elif arg.startswith("--"):
            name, equals, value = arg.partition("=")
            kind = options.get(name, "-")
            if kind == "-":
                out.append(arg)
            elif equals:
                out.append(f"{name}={rewrite.get(kind, keep)(value)}")
            else:
                out.append(arg)
                pending = rewrite.get(kind, keep)
        else:
            name, value = arg[:2], arg[2:]
            kind = options.get(name, "-")
            if kind == "-":
                out.append(arg)
            elif value:
                equals = "=" if value.startswith("=") else ""
                out.append(f"{name}{equals}{rewrite.get(kind, keep)(value[len(equals) :])}")
            else:
                out.append(arg)
                pending = rewrite.get(kind, keep)
    return out


if __name__ == "__main__":
    raise SystemExit(main())
