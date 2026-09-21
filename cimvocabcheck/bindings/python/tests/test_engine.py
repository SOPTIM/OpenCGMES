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

"""End-to-end against a real engine.

The scripted tests pin what the binding sends and how it reacts; these pin that a real engine
actually answers that way. They run in syntax-only mode so they need no CGMES profile library,
and they skip when the machine has no engine at all.
"""

from __future__ import annotations

import pytest

from cimvocabcheck import Code, Severity, tool_version, validate, validate_text
from cimvocabcheck.__main__ import main
from cimvocabcheck.api import STDIN_NAME

from .conftest import BROKEN_QUERY, CLEAN_QUERY, WARNING_QUERY, write

pytestmark = pytest.mark.engine


def test_a_clean_query_produces_a_clean_report(engine, workspace):
    write(workspace, "clean.rq", CLEAN_QUERY)

    report = validate(["clean.rq"], engine=engine, cwd=workspace)

    assert report.ok
    assert report.summary.files == 1
    assert report.contract_major == 1
    assert report.tool.name == "cimvocabcheck"
    assert report.findings() == ()


def test_a_broken_query_comes_back_as_findings(engine, workspace):
    write(workspace, "broken.rq", BROKEN_QUERY)

    report = validate(["broken.rq"], engine=engine, cwd=workspace)

    assert not report.ok
    assert report.invalid_files == ("broken.rq",)
    assert report.errors[0].code == Code.SYNTAX_ERROR
    assert report.errors[0].file == "broken.rq"


def test_every_input_is_validated_in_one_run(engine, workspace):
    """The whole point of the batch API: one schema load for all of them."""
    inputs = [
        write(workspace, "clean.rq", CLEAN_QUERY),
        write(workspace, "broken.rq", BROKEN_QUERY),
        write(workspace, "warning.rq", WARNING_QUERY),
    ]

    report = validate(inputs, engine=engine, cwd=workspace)

    assert [r.file for r in report.results] == inputs
    assert report.summary.files == 3
    assert report.summary.invalid == 1


def test_the_summary_accounts_for_exactly_what_the_report_contains(engine, workspace):
    inputs = [
        write(workspace, "broken.rq", BROKEN_QUERY),
        write(workspace, "warning.rq", WARNING_QUERY),
    ]

    report = validate(inputs, engine=engine, cwd=workspace)

    assert report.summary.errors == len(report.errors)
    assert report.summary.warnings == len(report.warnings)
    assert report.summary.infos == len(report.infos)
    assert report.summary.warnings > 0, "the fixture must produce WARN findings"


def test_warnings_are_dropped_when_the_caller_asks_for_the_cli_default(engine, workspace):
    write(workspace, "warning.rq", WARNING_QUERY)

    verbose = validate(["warning.rq"], engine=engine, cwd=workspace)
    quiet = validate(["warning.rq"], engine=engine, cwd=workspace, verbose=False)

    assert verbose.warnings
    assert quiet.warnings == ()
    assert quiet.summary.warnings == 0


def test_strictness_promotes_warnings_to_errors(engine, workspace):
    write(workspace, "warning.rq", WARNING_QUERY)

    default = validate(["warning.rq"], engine=engine, cwd=workspace)
    strict = validate(["warning.rq"], engine=engine, cwd=workspace, strictness="strict")

    assert default.ok
    assert not strict.ok
    assert all(f.severity == Severity.ERROR for f in strict.findings())


def test_a_query_in_memory_is_validated_over_stdin(engine, workspace):
    report = validate_text(BROKEN_QUERY, engine=engine, cwd=workspace)

    assert not report.ok
    assert report.results[0].file == STDIN_NAME


def test_a_path_with_a_space_survives(engine, workspace):
    """The reason this binding never builds a shell string."""
    (workspace / "my queries").mkdir()
    write(workspace / "my queries", "a b.rq", CLEAN_QUERY)

    report = validate(["my queries/a b.rq"], engine=engine, cwd=workspace)

    assert report.ok
    assert report.results[0].file == "my queries/a b.rq"


def test_a_shacl_shape_file_is_validated_as_turtle(engine, workspace):
    write(workspace, "shapes.ttl", "this is not turtle at all\n")

    report = validate(["shapes.ttl"], engine=engine, cwd=workspace)

    assert not report.ok


def test_a_configuration_error_is_raised_rather_than_returned(engine, workspace):
    write(workspace, "clean.rq", CLEAN_QUERY)

    with pytest.raises(Exception) as raised:
        validate(["clean.rq"], engine=engine, cwd=workspace, strictness="bogus")

    assert "strictness" in str(raised.value).lower()


def test_the_binding_reports_the_engine_version(engine, workspace):
    assert tool_version(engine=engine, cwd=workspace)


def test_the_shim_forwards_to_the_engine_and_returns_its_exit_code(engine, workspace, capfd):
    write(workspace, "broken.rq", BROKEN_QUERY)

    assert main(["--format", "json", "--", "broken.rq"]) == 1
    assert '"contractVersion"' in capfd.readouterr().out


def test_the_shim_reports_which_engine_it_found(engine, capfd):
    assert main(["--print-engine"]) == 0
    assert capfd.readouterr().out.strip()
