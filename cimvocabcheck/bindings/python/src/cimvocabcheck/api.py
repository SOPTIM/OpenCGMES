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

"""Running the engine and turning its report into typed Python.

Validation is a *batch* operation here, and deliberately so: loading a CGMES profile set costs
around a second, while validating one more query after that costs almost nothing. Passing every
input to one call is the difference between a CI job that takes a second and one that takes a
second per file.
"""

from __future__ import annotations

import json
import os
import subprocess
from collections.abc import Iterable, Mapping, Sequence
from collections.abc import Mapping as MappingABC
from pathlib import Path
from typing import Any, Union

from ._codes import CONTRACT_MAJOR
from ._model import Report
from .errors import EngineTimeoutError, ReportParseError, ToolError
from .runtime import Engine, EngineSpec, discover

PathLike = Union[str, "os.PathLike[str]"]

#: Exit codes the CLI documents. Anything else means the engine itself failed.
EXIT_CLEAN = 0
EXIT_FINDINGS = 1
EXIT_USAGE = 2

#: The name the engine reports an input under when it was read from stdin.
STDIN_NAME = "<stdin>"

#: Suffixes the engine reads as Turtle (SHACL shapes); anything else is read as SPARQL.
TURTLE_SUFFIXES = (".ttl", ".shacl")


class Strictness:
    """Values accepted by ``strictness``.

    Unlike the rule codes, this is a closed set owned by the CLI rather than by the report
    contract — an unknown value is rejected by the engine with exit code 2.
    """

    PERMISSIVE = "permissive"
    DEFAULT = "default"
    STRICT = "strict"
    PEDANTIC = "pedantic"

    ALL = (PERMISSIVE, DEFAULT, STRICT, PEDANTIC)


def _as_paths(value: None | PathLike | Iterable[PathLike]) -> list[str]:
    """Normalises one path or an iterable of them.

    A bare ``str`` is a single path, never an iterable of characters — getting that wrong turns
    ``validate("q.rq")`` into eight nonsense inputs.
    """
    if value is None:
        return []
    if isinstance(value, (str, os.PathLike)):
        return [os.fspath(value)]
    return [os.fspath(item) for item in value]


def build_args(
    inputs: Sequence[str],
    *,
    schema: Sequence[str] = (),
    config: str | None = None,
    endpoint: str | None = None,
    strict_endpoint: bool = False,
    profiles: Sequence[str] = (),
    strictness: str | None = None,
    verbose: bool = True,
    extra_args: Sequence[str] = (),
) -> list[str]:
    """Assembles the engine's argv tail. Exposed so a caller can see exactly what will be run."""
    args: list[str] = ["--format", "json"]
    if verbose:
        args.append("--verbose")
    if config is not None:
        args += ["--config", config]
    for path in schema:
        args += ["--schema", path]
    if endpoint is not None:
        args += ["--endpoint", endpoint]
    if strict_endpoint:
        args.append("--strict-endpoint")
    for profile in profiles:
        args += ["--profile", profile]
    if strictness is not None:
        args += ["--strictness", strictness]
    args += list(extra_args)
    # Everything after "--" is an input, so a file whose name begins with "-" is not read as a flag.
    return args + ["--", *inputs]


def validate(
    paths: PathLike | Iterable[PathLike],
    *,
    schema: None | PathLike | Iterable[PathLike] = None,
    config: PathLike | None = None,
    endpoint: str | None = None,
    strict_endpoint: bool = False,
    profiles: Iterable[str] = (),
    strictness: str | None = None,
    verbose: bool = True,
    engine: EngineSpec = None,
    cwd: PathLike | None = None,
    env: Mapping[str, str] | None = None,
    timeout: float | None = None,
    extra_args: Sequence[str] = (),
) -> Report:
    """Validates SPARQL queries and SHACL shapes, and returns one report for all of them.

    Args:
        paths: Inputs to validate — one path or many. Files ending in ``.ttl``/``.shacl`` are read
            as SHACL shapes, everything else as SPARQL. Pass paths relative to ``cwd``: they appear
            verbatim in the report, and CI annotations only line up when they are
            repository-relative.
        schema: RDFS profile file(s), or directories of them. Without a schema — and without
            ``config`` or ``endpoint`` — the engine checks syntax only.
        config: An ``opencgmes.jsonc``. Omitted, the engine discovers the nearest one above ``cwd``.
        endpoint: A SPARQL endpoint to load the schema and named-graph mapping from.
        strict_endpoint: Fail instead of falling back to a syntax-only check when ``endpoint``
            exposes no CIM schema.
        profiles: Profile IRIs to restrict validation to.
        strictness: One of :class:`Strictness`; overrides the config file.
        verbose: Include WARN and INFO findings. Defaults to ``True``, unlike the CLI: a library
            hands back everything and lets the caller filter, rather than discarding findings the
            caller cannot then recover.
        engine: An :class:`~cimvocabcheck.runtime.Engine`, or a path to a JAR or binary. Omitted,
            one is discovered (see :func:`~cimvocabcheck.runtime.discover`).
        cwd: Working directory the engine runs in; inputs are resolved against it.
        env: Environment for the child process and for engine discovery.
        timeout: Seconds to wait before giving up on the engine.
        extra_args: Further CLI flags, for options newer than this binding.

    Returns:
        The report. Findings are data, not exceptions — a query with errors comes back as a report
        whose :attr:`~cimvocabcheck.Report.ok` is ``False``.

    Raises:
        EngineNotFoundError: No engine could be discovered.
        ToolError: The engine reported a usage or configuration failure, or failed outright.
        ReportParseError: The engine's output was not a report of the contract major this binding
            targets.
        EngineTimeoutError: ``timeout`` elapsed.
    """
    return _validate(
        _as_paths(paths),
        None,
        schema=schema,
        config=config,
        endpoint=endpoint,
        strict_endpoint=strict_endpoint,
        profiles=profiles,
        strictness=strictness,
        verbose=verbose,
        engine=engine,
        cwd=cwd,
        env=env,
        timeout=timeout,
        extra_args=extra_args,
    )


def validate_file(path: PathLike, **kwargs: Any) -> Report:
    """Validates a single input. A convenience over :func:`validate`, which is the real entry point.

    Reach for :func:`validate` whenever there is more than one file: the schema load dominates the
    run, so one call with ten inputs costs about what one call with one input costs.
    """
    return validate([path], **kwargs)


def validate_text(text: str, **kwargs: Any) -> Report:
    """Validates a SPARQL query held in memory, by feeding it to the engine on stdin.

    The engine tells SHACL from SPARQL by file suffix and stdin has none, so shapes must come from
    a file. The single result is reported under :data:`STDIN_NAME`.

    Accepts every keyword argument :func:`validate` does.
    """
    return _validate(["-"], text, **kwargs)


def _validate(
    inputs: Sequence[str],
    stdin_text: str | None,
    *,
    schema: None | PathLike | Iterable[PathLike] = None,
    config: PathLike | None = None,
    endpoint: str | None = None,
    strict_endpoint: bool = False,
    profiles: Iterable[str] = (),
    strictness: str | None = None,
    verbose: bool = True,
    engine: EngineSpec = None,
    cwd: PathLike | None = None,
    env: Mapping[str, str] | None = None,
    timeout: float | None = None,
    extra_args: Sequence[str] = (),
) -> Report:
    resolved = engine if isinstance(engine, Engine) else discover(engine, env=env, cwd=cwd)
    here = Path(cwd) if cwd is not None else None
    args = build_args(
        [resolved.resolve_path(path, here) for path in inputs],
        schema=[resolved.resolve_path(path, here) for path in _as_paths(schema)],
        config=resolved.resolve_path(os.fspath(config), here) if config is not None else None,
        endpoint=endpoint,
        strict_endpoint=strict_endpoint,
        profiles=list(profiles),
        strictness=strictness,
        verbose=verbose,
        extra_args=extra_args,
    )
    return _run(resolved, args, cwd=cwd, env=env, timeout=timeout, stdin_text=stdin_text)


def tool_version(
    engine: EngineSpec = None,
    *,
    cwd: PathLike | None = None,
    env: Mapping[str, str] | None = None,
    timeout: float | None = None,
) -> str:
    """Returns the engine's own version string, e.g. ``"1.4.2"``.

    ``"unknown"`` means the engine runs from a checkout rather than a packaged artifact — the
    version is stamped into the JAR manifest at package time.
    """
    resolved = engine if isinstance(engine, Engine) else discover(engine, env=env, cwd=cwd)
    completed = _spawn(resolved, ["--version"], cwd=cwd, env=env, timeout=timeout)
    if completed.returncode != EXIT_CLEAN:
        raise ToolError(
            "the engine failed to report its version.",
            argv=resolved.command(["--version"]),
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )
    first = completed.stdout.strip().splitlines()[0] if completed.stdout.strip() else ""
    return first.split(" ", 1)[1].strip() if " " in first else first


# ---- Plumbing ---------------------------------------------------------------------------------


def _spawn(
    engine: Engine,
    args: Sequence[str],
    *,
    cwd: PathLike | None,
    env: Mapping[str, str] | None,
    timeout: float | None,
    stdin_text: str | None = None,
) -> subprocess.CompletedProcess[str]:
    argv = engine.command(
        args, stdin=stdin_text is not None, cwd=Path(cwd) if cwd is not None else None
    )
    try:
        return subprocess.run(  # noqa: S603 - argv list, never a shell string
            argv,
            input=stdin_text,
            capture_output=True,
            cwd=os.fspath(cwd) if cwd is not None else None,
            env=dict(env) if env is not None else None,
            timeout=timeout,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except subprocess.TimeoutExpired as expired:
        raise EngineTimeoutError(
            f"the engine did not finish within {timeout}s.",
            argv=argv,
            stdout=_text(expired.stdout),
            stderr=_text(expired.stderr),
        ) from expired
    except OSError as error:
        raise ToolError(
            f"could not start the engine ({engine.describe()}): {error}", argv=argv
        ) from error


def _text(value: Any) -> str:
    if value is None:
        return ""
    return value.decode("utf-8", "replace") if isinstance(value, bytes) else str(value)


def _run(
    engine: Engine,
    args: Sequence[str],
    *,
    cwd: PathLike | None,
    env: Mapping[str, str] | None,
    timeout: float | None,
    stdin_text: str | None = None,
) -> Report:
    completed = _spawn(engine, args, cwd=cwd, env=env, timeout=timeout, stdin_text=stdin_text)
    argv = engine.command(
        args, stdin=stdin_text is not None, cwd=Path(cwd) if cwd is not None else None
    )

    if completed.returncode == EXIT_USAGE:
        raise ToolError(
            "the engine rejected the request (usage or configuration error); no report was "
            "produced.",
            argv=argv,
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )
    if completed.returncode not in (EXIT_CLEAN, EXIT_FINDINGS):
        raise ToolError(
            f"the engine exited with {completed.returncode}, which is outside its documented "
            "exit codes (0 clean, 1 findings, 2 usage).",
            argv=argv,
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )
    return parse_report(
        completed.stdout, argv=argv, returncode=completed.returncode, stderr=completed.stderr
    )


def parse_report(
    stdout: str,
    *,
    argv: Sequence[str] = (),
    returncode: int | None = None,
    stderr: str = "",
) -> Report:
    """Parses a ``--format json`` document, rejecting one from an incompatible contract major."""
    try:
        document = json.loads(stdout)
    except ValueError as error:
        raise ReportParseError(
            f"the engine's output was not JSON ({error}).",
            argv=argv,
            returncode=returncode,
            stdout=stdout,
            stderr=stderr,
        ) from error

    if not isinstance(document, MappingABC):
        raise ReportParseError(
            "the engine's output was not a report object.",
            argv=argv,
            returncode=returncode,
            stdout=stdout,
            stderr=stderr,
        )

    declared = document.get("contractVersion")
    major = str(declared).split(".", 1)[0] if declared is not None else ""
    if major != str(CONTRACT_MAJOR):
        raise ReportParseError(
            f"this binding speaks report contract {CONTRACT_MAJOR}.x but the engine produced "
            f"{declared!r}. A contract major renames or removes fields, so upgrade the binding "
            "to match the engine.",
            argv=argv,
            returncode=returncode,
            stderr=stderr,
        )
    return Report.from_dict(document)
