#!/usr/bin/env python3
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

"""Generates every binding's report model from the one published JSON Schema.

The schema under ``cimvocabcheck/schemas/`` is the contract; these models are derived from it so
a contract minor — a new optional field, a new rule code — is picked up by re-running this script
rather than by hand-editing three models and hoping they agree.

One generator rather than one per language is the point: how the contract maps onto a typed model
is decided once, in ``contract_ir.py``, and each ``emit_*.py`` only decides how to spell it. A
fourth language is an emitter, not another reading of the schema.

Usage::

    python3 generate_models.py                      # (re)write every binding's model
    python3 generate_models.py --target rust        # just one
    python3 generate_models.py --check              # fail with a diff if any is stale

``--check`` is what CI and the test suites run: it is the guard that a contract change cannot
land with a model that no longer matches it.

Python is a build-time dependency of the Rust and .NET packages only in the sense that their
generated sources are committed — building or consuming either package never runs this.
"""

from __future__ import annotations

import argparse
import difflib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import contract_ir  # noqa: E402
import emit_csharp  # noqa: E402
import emit_python  # noqa: E402
import emit_rust  # noqa: E402

TARGETS = {
    "python": emit_python.files,
    "rust": emit_rust.files,
    "csharp": emit_csharp.files,
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--target",
        choices=sorted(TARGETS),
        action="append",
        help="a binding to generate; repeatable, defaults to all of them",
    )
    parser.add_argument(
        "--schema",
        type=Path,
        default=contract_ir.CANONICAL_SCHEMA,
        help="the schema to generate from (default: the published one)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="do not write; exit 1 with a diff when a generated file is stale",
    )
    args = parser.parse_args(argv)

    contract = contract_ir.load(args.schema)
    targets = args.target or sorted(TARGETS)

    stale: list[str] = []
    for target in targets:
        for path, content in TARGETS[target](contract).items():
            current = path.read_text(encoding="utf-8") if path.exists() else ""
            if current == content:
                continue
            stale.append(f"{target}: {path.name}")
            if args.check:
                sys.stdout.writelines(
                    difflib.unified_diff(
                        current.splitlines(keepends=True),
                        content.splitlines(keepends=True),
                        fromfile=f"{path} (committed)",
                        tofile=f"{path} (regenerated)",
                    )
                )
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                print(f"wrote {path}")

    if args.check and stale:
        print(
            "\nThese generated files no longer match the schema:\n  "
            + "\n  ".join(stale)
            + "\n\nRun: python3 cimvocabcheck/bindings/codegen/generate_models.py",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
