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

"""Guards that keep this binding tied to the published contract.

The engine's own test suite already fails when a rule code is added without updating the schema.
These are the other half: the schema this package ships must be the one the repository publishes,
and the generated model must be the one that schema produces.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

PACKAGE_ROOT = Path(__file__).resolve().parent.parent
VENDORED_SCHEMA = PACKAGE_ROOT / "src" / "cimvocabcheck" / "schemas" / (
    "cimvocabcheck-report-1.schema.json"
)
CANONICAL_SCHEMA = (
    PACKAGE_ROOT.parent.parent / "schemas" / "cimvocabcheck-report-1.schema.json"
)
GENERATOR = PACKAGE_ROOT / "scripts" / "generate_model.py"

SOURCES = sorted(
    path
    for directory in ("src", "tests", "scripts")
    for path in (PACKAGE_ROOT / directory).rglob("*.py")
)


def test_the_generated_model_matches_the_schema():
    """Regenerating must be a no-op; otherwise the model and the contract have drifted apart."""
    completed = subprocess.run(
        [sys.executable, str(GENERATOR), "--check"],
        cwd=str(PACKAGE_ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    assert completed.returncode == 0, completed.stdout


@pytest.mark.skipif(
    not CANONICAL_SCHEMA.is_file(),
    reason="not a repository checkout; only the vendored copy exists",
)
def test_the_shipped_schema_is_the_published_one():
    """The package vendors the schema so it works when installed; the two must not diverge."""
    assert VENDORED_SCHEMA.read_bytes() == CANONICAL_SCHEMA.read_bytes(), (
        f"{VENDORED_SCHEMA} differs from the published contract at {CANONICAL_SCHEMA} — "
        "copy the published file over it and re-run scripts/generate_model.py."
    )


def test_every_source_file_carries_the_license_header():
    header = "SPDX-License-Identifier: Apache-2.0"
    missing = [
        str(path.relative_to(PACKAGE_ROOT))
        for path in SOURCES
        if header not in path.read_text(encoding="utf-8")[:1500]
    ]

    assert missing == []
