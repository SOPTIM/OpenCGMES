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

"""Shared fixtures.

Tests that need a real engine are marked ``engine`` and skip when none is discoverable, so the
suite is meaningful on a machine with no JRE and authoritative on one with a built CLI.
"""

from __future__ import annotations

import json
import os

import pytest

from cimvocabcheck import api, errors, runtime

#: A query with one unused variable: parses, and produces WARN findings but no error.
WARNING_QUERY = "SELECT ?s WHERE { ?s ?p ?o }"

#: Fails to parse: exactly one ERROR.
BROKEN_QUERY = "SELEEECT * WHERE { ?s ?p ?o }"

#: Clean in syntax-only mode.
CLEAN_QUERY = "SELECT * WHERE { ?s ?p ?o }"


#: Set this in CI so a missing or outdated engine fails the run instead of quietly skipping it.
REQUIRE_ENGINE = "CIMVOCABCHECK_TESTS_REQUIRE_ENGINE"


def _unavailable(reason: str):
    if os.environ.get(REQUIRE_ENGINE):
        pytest.fail(f"{reason} ({REQUIRE_ENGINE} is set)")
    pytest.skip(reason)


@pytest.fixture(scope="session")
def engine():
    """A discovered engine that speaks this binding's report contract.

    Discovery can land on an engine too old for the contract — most plausibly the published
    container image, which lags a change made in this repository. That is not a bug in the binding
    and must not read as one, so the engine is probed once and the suite skips rather than
    reporting a wall of failures.
    """
    try:
        found = runtime.discover()
    except errors.EngineNotFoundError as error:
        _unavailable(f"no CIMVocabCheck engine available: {error}")
    try:
        api.validate_text(CLEAN_QUERY, engine=found)
    except errors.ReportParseError as error:
        _unavailable(
            f"the discovered engine ({found.describe()}) does not speak the report contract "
            f"this binding targets: {error}"
        )
    except errors.CimVocabCheckError as error:
        _unavailable(f"the discovered engine ({found.describe()}) could not be run: {error}")
    return found


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    """An empty project directory: a config with no schema, i.e. syntax-only validation.

    Every engine-backed test runs this way so it exercises the binding rather than the presence of
    a CGMES profile library.
    """
    (tmp_path / "opencgmes.jsonc").write_text('{"cimvocabcheck": {}}', encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    return tmp_path


def write(directory, name: str, text: str) -> str:
    """Writes an input and returns its name, relative — the way reports are meant to be produced."""
    (directory / name).write_text(text, encoding="utf-8")
    return name


def report_json(**overrides) -> str:
    """A minimal well-formed report document, for tests that must not spawn anything."""
    document = {
        "contractVersion": "1.0",
        "tool": {"name": "cimvocabcheck", "version": "1.2.3"},
        "summary": {"files": 0, "valid": 0, "invalid": 0, "errors": 0, "warnings": 0, "infos": 0},
        "results": [],
    }
    document.update(overrides)
    return json.dumps(document)
