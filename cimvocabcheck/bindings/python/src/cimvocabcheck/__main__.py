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
engine to run, which is what makes the package usable as a ``pre-commit`` hook.
"""

from __future__ import annotations

import subprocess
import sys
from collections.abc import Sequence

from .errors import CimVocabCheckError
from .runtime import discover

#: Reports which engine was discovered and exits, without running a validation.
PRINT_ENGINE_FLAG = "--print-engine"

USAGE_EXIT = 2


def main(argv: Sequence[str] | None = None) -> int:
    """Runs the engine with ``argv``, inheriting this process's stdin, stdout and stderr."""
    args: list[str] = list(sys.argv[1:] if argv is None else argv)
    try:
        engine = discover()
        if PRINT_ENGINE_FLAG in args:
            print(engine.describe())
            return 0
        command = engine.command(args, stdin="-" in args)
    except CimVocabCheckError as error:
        print(f"cimvocabcheck: {error}", file=sys.stderr)
        return USAGE_EXIT

    try:
        return subprocess.call(command)  # noqa: S603 - argv list, never a shell string
    except OSError as error:
        print(f"cimvocabcheck: could not start {engine.describe()}: {error}", file=sys.stderr)
        return USAGE_EXIT
    except KeyboardInterrupt:  # pragma: no cover - interactive only
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
