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

"""Hand-written behaviour mixed into the generated report types.

The data classes in ``_model.py`` are generated from the published JSON Schema and are
regenerated whenever the contract gains a field. Everything that is *convenience* rather than
*contract* lives here, so regenerating never overwrites it.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import TYPE_CHECKING, NamedTuple

from ._codes import KNOWN_CODES, Severity

if TYPE_CHECKING:  # pragma: no cover - typing only
    from ._model import Annotation, FileResult


class Finding(NamedTuple):
    """One annotation together with the input it was reported against.

    The report nests annotations under their file; most callers want them flat and still need to
    know where each came from.
    """

    file: str
    annotation: Annotation

    @property
    def severity(self) -> str:
        return self.annotation.severity

    @property
    def code(self) -> str:
        return self.annotation.code

    @property
    def message(self) -> str:
        return self.annotation.message

    def __str__(self) -> str:
        a = self.annotation
        return f"{a.location(self.file)}: {a.severity}[{a.code}] {a.message}"


class AnnotationMixin:
    """Convenience on a single finding."""

    severity: str
    code: str
    line: int | None
    column: int | None

    @property
    def is_error(self) -> bool:
        return self.severity == Severity.ERROR

    @property
    def is_warning(self) -> bool:
        return self.severity == Severity.WARN

    @property
    def is_info(self) -> bool:
        return self.severity == Severity.INFO

    @property
    def is_known_code(self) -> bool:
        """False for a code added by an engine newer than this binding.

        Such a finding is still a valid finding — the contract adds codes in minor versions and
        requires consumers to treat an unknown one as a generic finding, never as an error.
        """
        return self.code in KNOWN_CODES

    def location(self, file: str) -> str:
        """Renders ``file:line:col``, omitting the parts the engine could not resolve."""
        parts = [file]
        if self.line is not None:
            parts.append(str(self.line))
            if self.column is not None:
                parts.append(str(self.column))
        return ":".join(parts)


class FileResultMixin:
    """Convenience on one validated input."""

    file: str
    valid: bool
    annotations: tuple[Annotation, ...]

    def findings(
        self,
        severity: str | None = None,
        code: str | None = None,
        min_severity: str | None = None,
    ) -> tuple[Finding, ...]:
        """This input's findings, narrowed by exact severity, code, or a severity floor."""
        return tuple(
            Finding(self.file, a)
            for a in self.annotations
            if (severity is None or a.severity == severity)
            and (code is None or a.code == code)
            and (min_severity is None or severity_at_least(a.severity, min_severity))
        )


class ReportMixin:
    """Convenience on a whole report."""

    contract_version: str
    results: tuple[FileResult, ...]

    @property
    def contract_major(self) -> int:
        """The contract major this document declares — what compatibility is judged on."""
        return int(self.contract_version.split(".", 1)[0])

    @property
    def ok(self) -> bool:
        """True when no input has an ERROR-severity finding."""
        return all(r.valid for r in self.results)

    def findings(
        self,
        severity: str | None = None,
        code: str | None = None,
        min_severity: str | None = None,
    ) -> tuple[Finding, ...]:
        """All findings across all inputs, optionally narrowed by severity, code or a floor."""
        return tuple(
            finding
            for result in self.results
            for finding in result.findings(
                severity=severity, code=code, min_severity=min_severity
            )
        )

    @property
    def errors(self) -> tuple[Finding, ...]:
        return self.findings(severity=Severity.ERROR)

    @property
    def warnings(self) -> tuple[Finding, ...]:
        return self.findings(severity=Severity.WARN)

    @property
    def infos(self) -> tuple[Finding, ...]:
        return self.findings(severity=Severity.INFO)

    @property
    def invalid_files(self) -> tuple[str, ...]:
        return tuple(r.file for r in self.results if not r.valid)

    def for_file(self, file: str) -> FileResult | None:
        """The result for one input, addressed exactly as it was passed to :func:`validate`."""
        for r in self.results:
            if r.file == file:
                return r
        return None

    def format(self) -> str:
        """Renders the findings as compiler-style lines, one per finding."""
        return "\n".join(str(f) for f in self.findings())


def severity_at_least(severity: str, floor: str) -> bool:
    """True when ``severity`` is at least as severe as ``floor``.

    Unknown severities sort below INFO rather than raising: the contract may grow, and a consumer
    that crashes on an unrecognised value is worse than one that under-reports it.
    """
    order: Sequence[str] = (Severity.INFO, Severity.WARN, Severity.ERROR)
    try:
        return order.index(severity) >= order.index(floor)
    except ValueError:
        return False
