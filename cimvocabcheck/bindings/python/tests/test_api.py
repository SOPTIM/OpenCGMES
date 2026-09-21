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

"""The command line this binding builds, and how it reacts to what comes back.

These run against a scripted stand-in rather than the real engine, so they pin the contract
between the binding and *any* conforming engine — including the exit codes and the failure modes
a real one is hard to provoke into.
"""

from __future__ import annotations

import sys

import pytest

from cimvocabcheck import (
    EngineTimeoutError,
    ReportParseError,
    Strictness,
    ToolError,
    build_args,
    tool_version,
    validate,
    validate_file,
    validate_text,
)
from cimvocabcheck.runtime import Engine

from .conftest import report_json

SCRIPT = """
import sys
sys.stdout.write({stdout!r})
sys.stderr.write({stderr!r})
sys.stdout.flush()
sys.exit({code})
"""


class ScriptedEngine(Engine):
    """An engine that ignores its arguments and returns what the test told it to.

    It records the argv it was handed, which is the other half of what these tests check.
    """

    kind = "scripted"

    def __init__(self, stdout: str = "", stderr: str = "", code: int = 0, sleep: float = 0.0):
        self.source = SCRIPT.format(stdout=stdout, stderr=stderr, code=code)
        if sleep:
            self.source = f"import time; time.sleep({sleep})\n" + self.source
        self.calls = []

    def command(self, args, *, stdin=False, cwd=None):
        self.calls.append(list(args))
        return [sys.executable, "-c", self.source, *args]

    def describe(self):
        return "a scripted engine"

    @property
    def last_call(self):
        return self.calls[-1]


# ---- argv -------------------------------------------------------------------------------------


def test_the_report_format_and_every_option_reach_the_command_line():
    args = build_args(
        ["a.rq", "b.rq"],
        schema=["profiles", "extra.rdf"],
        config="opencgmes.jsonc",
        endpoint="http://localhost:3030/ds/query",
        strict_endpoint=True,
        profiles=["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
        strictness=Strictness.STRICT,
        verbose=True,
    )

    assert args[:2] == ["--format", "json"]
    assert "--verbose" in args
    assert args.count("--schema") == 2
    assert args[args.index("--config") + 1] == "opencgmes.jsonc"
    assert args[args.index("--strictness") + 1] == "strict"
    assert "--strict-endpoint" in args
    assert args[-3:] == ["--", "a.rq", "b.rq"]


def test_inputs_are_separated_from_options_so_a_leading_dash_is_still_a_file():
    args = build_args(["-weird-name.rq"])

    assert args[args.index("--") + 1] == "-weird-name.rq"


def test_warnings_are_requested_by_default_because_a_library_should_not_drop_findings():
    engine = ScriptedEngine(stdout=report_json())

    validate(["q.rq"], engine=engine)

    assert "--verbose" in engine.last_call


def test_verbose_can_be_turned_off_to_match_the_cli_default():
    engine = ScriptedEngine(stdout=report_json())

    validate(["q.rq"], engine=engine, verbose=False)

    assert "--verbose" not in engine.last_call


def test_a_single_path_is_one_input_not_a_string_of_characters():
    engine = ScriptedEngine(stdout=report_json())

    validate("queries/q.rq", engine=engine)

    assert engine.last_call[-2:] == ["--", "queries/q.rq"]


def test_a_pathlib_path_is_accepted(tmp_path):
    engine = ScriptedEngine(stdout=report_json())

    validate(tmp_path / "q.rq", engine=engine, schema=tmp_path / "profiles")

    assert str(tmp_path / "q.rq") in engine.last_call
    assert str(tmp_path / "profiles") in engine.last_call


def test_validate_file_goes_through_the_batch_call():
    engine = ScriptedEngine(stdout=report_json())

    validate_file("q.rq", engine=engine)

    assert engine.last_call[-2:] == ["--", "q.rq"]


def test_unknown_options_can_be_forwarded_without_a_binding_release():
    engine = ScriptedEngine(stdout=report_json())

    validate(["q.rq"], engine=engine, extra_args=["--future-flag", "value"])

    assert engine.last_call[engine.last_call.index("--future-flag") + 1] == "value"


def test_text_is_validated_through_stdin():
    engine = ScriptedEngine(stdout=report_json())

    validate_text("SELECT * WHERE { ?s ?p ?o }", engine=engine)

    assert engine.last_call[-1] == "-"


# ---- exit codes -------------------------------------------------------------------------------


def test_findings_are_data_not_an_exception():
    """Exit code 1 means "the input has errors", which is a report, not a tool failure."""
    document = report_json(
        summary={"files": 1, "valid": 0, "invalid": 1, "errors": 1, "warnings": 0, "infos": 0},
        results=[
            {
                "file": "q.rq",
                "valid": False,
                "annotations": [{"severity": "ERROR", "code": "SYNTAX_ERROR", "message": "boom"}],
            }
        ],
    )
    engine = ScriptedEngine(stdout=document, code=1)

    report = validate(["q.rq"], engine=engine)

    assert not report.ok
    assert report.summary.errors == 1


def test_a_usage_error_is_raised_with_the_engines_own_complaint():
    engine = ScriptedEngine(stderr="Error: Unknown strictness level 'bogus'.\n", code=2)

    with pytest.raises(ToolError) as raised:
        validate(["q.rq"], engine=engine, strictness="bogus")

    assert "Unknown strictness level" in str(raised.value)
    assert raised.value.returncode == 2


def test_an_undocumented_exit_code_is_reported_as_an_engine_failure():
    engine = ScriptedEngine(stderr="Exception in thread \"main\"\n", code=137)

    with pytest.raises(ToolError) as raised:
        validate(["q.rq"], engine=engine)

    assert "outside its documented exit codes" in str(raised.value)
    assert raised.value.returncode == 137


def test_unparseable_output_carries_what_the_engine_actually_printed():
    engine = ScriptedEngine(stdout="not a report at all", stderr="warning: something\n", code=0)

    with pytest.raises(ReportParseError) as raised:
        validate(["q.rq"], engine=engine)

    assert raised.value.stdout == "not a report at all"
    assert "something" in raised.value.stderr


def test_a_timeout_is_its_own_error():
    engine = ScriptedEngine(stdout=report_json(), sleep=5)

    with pytest.raises(EngineTimeoutError):
        validate(["q.rq"], engine=engine, timeout=0.3)


def test_an_engine_that_cannot_be_started_is_reported_clearly(tmp_path):
    class Missing(Engine):
        def command(self, args, *, stdin=False, cwd=None):
            return [str(tmp_path / "does-not-exist")]

        def describe(self):
            return "a missing engine"

    with pytest.raises(ToolError) as raised:
        validate(["q.rq"], engine=Missing())

    assert "could not start" in str(raised.value)


def test_tool_version_reports_the_engine_not_the_binding():
    engine = ScriptedEngine(stdout="cimvocabcheck 1.4.2\n")

    assert tool_version(engine=engine) == "1.4.2"


def test_the_command_is_argv_never_a_shell_string():
    """Paths contain spaces; anything that goes through a shell eventually loses one."""
    engine = ScriptedEngine(stdout=report_json())

    validate(["my queries/a b.rq"], engine=engine)

    assert engine.last_call[-1] == "my queries/a b.rq"
    assert isinstance(engine.command([]), list)


def test_the_child_runs_in_the_requested_working_directory(tmp_path):
    """Inputs are resolved by the engine, so it has to start where the caller says it does."""

    class ReportsItsCwd(Engine):
        def command(self, args, *, stdin=False, cwd=None):
            return [
                sys.executable,
                "-c",
                "import json, os, sys; "
                "sys.stdout.write(json.dumps({'contractVersion': '1.0', 'tool': "
                "{'name': 'cimvocabcheck', 'version': os.path.realpath(os.getcwd())}, 'summary': "
                "{'files': 0, 'valid': 0, 'invalid': 0, 'errors': 0, 'warnings': 0, 'infos': 0}, "
                "'results': []}))",
            ]

        def describe(self):
            return "an engine that reports its working directory"

    report = validate(["q.rq"], engine=ReportsItsCwd(), cwd=tmp_path)

    assert report.tool.version == str(tmp_path.resolve())
