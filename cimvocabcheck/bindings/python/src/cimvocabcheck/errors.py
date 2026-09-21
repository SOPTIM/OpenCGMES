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

"""Exceptions raised by this binding.

Findings are *data*, never exceptions: a query with errors comes back as a
:class:`~cimvocabcheck.Report`. These are raised only when the engine could not be run, or
ran but did not produce a report this binding can trust.
"""

from __future__ import annotations

from collections.abc import Sequence


class CimVocabCheckError(Exception):
    """Base class for every error this binding raises."""


class EngineNotFoundError(CimVocabCheckError):
    """No CIMVocabCheck engine could be discovered.

    The message lists every location that was searched, in order, so the fix is visible without
    reading the discovery code.
    """

    def __init__(self, message: str, searched: Sequence[str] = ()) -> None:
        self.searched = tuple(searched)
        if self.searched:
            listing = "\n".join(
                f"  {i}. {where}" for i, where in enumerate(self.searched, start=1)
            )
            message = f"{message}\nSearched, in order:\n{listing}"
        super().__init__(message)


class ToolError(CimVocabCheckError):
    """The engine ran but did not deliver a usable report.

    Covers a usage/configuration failure (exit code 2), any exit code outside the documented
    ``0``/``1``/``2``, and output that is not a parseable report.
    """

    def __init__(
        self,
        message: str,
        *,
        argv: Sequence[str] = (),
        returncode: int | None = None,
        stdout: str = "",
        stderr: str = "",
    ) -> None:
        self.argv = tuple(argv)
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        detail = stderr.strip() or stdout.strip()
        if detail:
            message = message + "\n" + detail
        super().__init__(message)


class ReportParseError(ToolError):
    """The engine's stdout was not a report conforming to the contract major this binding targets.

    A report whose ``contractVersion`` major is newer than this binding's is *not* an error — the
    contract only adds within a major — so this is raised for a genuinely different document.
    """


class EngineTimeoutError(ToolError):
    """The engine did not finish within the requested ``timeout``."""
