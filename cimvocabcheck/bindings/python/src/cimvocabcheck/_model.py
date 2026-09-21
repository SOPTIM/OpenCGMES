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

# ---------------------------------------------------------------------------------------------
# GENERATED FILE - DO NOT EDIT.
# Produced from schemas/cimvocabcheck-report-1.schema.json by scripts/generate_model.py.
# Behaviour that is convenience rather than contract belongs in _ergonomics.py, which survives
# regeneration.
# ---------------------------------------------------------------------------------------------

"""The report document, as typed Python.

Unknown fields are kept in ``extra`` rather than dropped: the contract only ever adds
within a major version, so a field this binding has not been regenerated for is still
meaningful to a caller that knows about it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from . import _parse
from ._ergonomics import AnnotationMixin, FileResultMixin, ReportMixin


@dataclass(frozen=True)
class Annotation(AnnotationMixin):
    """One finding. Position and term are present whenever the engine could resolve them."""

    #: Severity after --strictness has been applied. See :class:`Severity` for the values known to
    #: this binding.
    severity: str
    #: Stable identifier of the rule that triggered — the key automation should switch on. The
    #: catalogue is documented at https://opencgmes.soptim.de/cimvocabcheck/validation-checks. New
    #: codes are added in minor contract versions, so consumers must treat an unrecognised code as a
    #: generic finding rather than an error. See :class:`Code` for the values known to this binding.
    code: str
    #: Human-readable rendering. Not stable across releases — key automation off "code", never off
    #: this text.
    message: str
    #: 1-based line in the input file. Findings inside embedded SPARQL (sh:select / sh:ask) are
    #: mapped back to their line in the Turtle source. Absent when the engine could not resolve a
    #: position.
    line: int | None = None
    #: 1-based column, counted in UTF-16 code units. Absent when unresolved.
    column: int | None = None
    #: The offending RDF term (class IRI, property IRI, …). Absent when the finding is not about a
    #: single term.
    term: str | None = None
    #: Named graph the finding occurred in, under per-graph profile scoping. Absent for
    #: default-graph context.
    graph: str | None = None
    #: For UNKNOWN_CLASS / UNKNOWN_PROPERTY: profiles in which the term DOES exist. A hint that the
    #: wrong profile is in scope rather than a typo. Absent when empty.
    found_in_other_profiles: tuple[str, ...] = ()
    #: Fields the engine emitted that this binding does not know about.
    extra: dict[str, Any] = field(default_factory=dict)

    _JSON_FIELDS = (
        "severity",
        "code",
        "message",
        "line",
        "column",
        "term",
        "graph",
        "foundInOtherProfiles",
    )

    @classmethod
    def from_dict(cls, data: Any, path: str = "$") -> Annotation:
        """Reads this node from decoded JSON; ``path`` names it in error messages."""
        data = _parse.mapping(data, path)
        return cls(
            severity=_parse.string(data, "severity", path, required=True),
            code=_parse.string(data, "code", path, required=True),
            message=_parse.string(data, "message", path, required=True),
            line=_parse.integer(data, "line", path),
            column=_parse.integer(data, "column", path),
            term=_parse.string(data, "term", path),
            graph=_parse.string(data, "graph", path),
            found_in_other_profiles=_parse.strings(data, "foundInOtherProfiles", path),
            extra=_parse.extra(data, cls._JSON_FIELDS),
        )


@dataclass(frozen=True)
class FileResult(FileResultMixin):
    """One validated input and the findings reported against it."""

    #: The input as it was passed on the command line, or "<stdin>" for input read from stdin.
    #: Invoke the CLI with repository-relative paths for findings to line up with a source tree.
    file: str
    #: False when the input has at least one ERROR-severity finding after strictness is applied.
    #: Independent of --verbose.
    valid: bool
    #: Findings for this input; empty when it is clean, or when --verbose is off and only WARN/INFO
    #: findings were produced.
    annotations: tuple[Annotation, ...]
    #: Fields the engine emitted that this binding does not know about.
    extra: dict[str, Any] = field(default_factory=dict)

    _JSON_FIELDS = (
        "file",
        "valid",
        "annotations",
    )

    @classmethod
    def from_dict(cls, data: Any, path: str = "$") -> FileResult:
        """Reads this node from decoded JSON; ``path`` names it in error messages."""
        data = _parse.mapping(data, path)
        return cls(
            file=_parse.string(data, "file", path, required=True),
            valid=_parse.boolean(data, "valid", path, required=True),
            annotations=_parse.objects(
                data,
                "annotations",
                path,
                Annotation.from_dict,
                required=True,
            ),
            extra=_parse.extra(data, cls._JSON_FIELDS),
        )


@dataclass(frozen=True)
class Tool:
    """Which engine produced the report."""

    #: Always "cimvocabcheck".
    name: str
    #: Released tool version, or "unknown" when the engine runs from a checkout rather than a
    #: packaged JAR.
    version: str
    #: Fields the engine emitted that this binding does not know about.
    extra: dict[str, Any] = field(default_factory=dict)

    _JSON_FIELDS = (
        "name",
        "version",
    )

    @classmethod
    def from_dict(cls, data: Any, path: str = "$") -> Tool:
        """Reads this node from decoded JSON; ``path`` names it in error messages."""
        data = _parse.mapping(data, path)
        return cls(
            name=_parse.string(data, "name", path, required=True),
            version=_parse.string(data, "version", path, required=True),
            extra=_parse.extra(data, cls._JSON_FIELDS),
        )


@dataclass(frozen=True)
class Summary:
    """Counts over this document. The finding counts are taken AFTER the --verbose filter, so
    they always account for exactly what "results" contains; "valid"/"invalid" are decided
    before filtering and are unaffected by it.
    """

    #: Number of validated inputs.
    files: int
    #: Inputs with no ERROR-severity finding.
    valid: int
    #: Inputs with at least one ERROR-severity finding.
    invalid: int
    #: ERROR-severity findings in this document.
    errors: int
    #: WARN-severity findings in this document.
    warnings: int
    #: INFO-severity findings in this document.
    infos: int
    #: Fields the engine emitted that this binding does not know about.
    extra: dict[str, Any] = field(default_factory=dict)

    _JSON_FIELDS = (
        "files",
        "valid",
        "invalid",
        "errors",
        "warnings",
        "infos",
    )

    @classmethod
    def from_dict(cls, data: Any, path: str = "$") -> Summary:
        """Reads this node from decoded JSON; ``path`` names it in error messages."""
        data = _parse.mapping(data, path)
        return cls(
            files=_parse.integer(data, "files", path, required=True),
            valid=_parse.integer(data, "valid", path, required=True),
            invalid=_parse.integer(data, "invalid", path, required=True),
            errors=_parse.integer(data, "errors", path, required=True),
            warnings=_parse.integer(data, "warnings", path, required=True),
            infos=_parse.integer(data, "infos", path, required=True),
            extra=_parse.extra(data, cls._JSON_FIELDS),
        )


@dataclass(frozen=True)
class Report(ReportMixin):
    """The document CIMVocabCheck writes with --format json. This is the published integration
    contract: non-Java consumers generate their result types from this schema. Within contract
    major version 1, fields, output formats and diagnostic codes are only ever ADDED — nothing
    is renamed or removed — so a consumer must ignore properties and enum members it does not
    know. Breaking changes require a new major (a new file,
    cimvocabcheck-report-2.schema.json). The SARIF report (--format sarif) follows the OASIS
    SARIF 2.1.0 schema instead and is not described here.
    """

    #: Version of this report contract, "<major>.<minor>" — independent of the tool version. A
    #: consumer should accept any report whose major matches the one it was written against.
    contract_version: str
    #: Which engine produced the report.
    tool: Tool
    #: Counts over this document. The finding counts are taken AFTER the --verbose filter, so they
    #: always account for exactly what "results" contains; "valid"/"invalid" are decided before
    #: filtering and are unaffected by it.
    summary: Summary
    #: One entry per validated input, in the order the inputs were given.
    results: tuple[FileResult, ...]
    #: Fields the engine emitted that this binding does not know about.
    extra: dict[str, Any] = field(default_factory=dict)

    _JSON_FIELDS = (
        "contractVersion",
        "tool",
        "summary",
        "results",
    )

    @classmethod
    def from_dict(cls, data: Any, path: str = "$") -> Report:
        """Reads this node from decoded JSON; ``path`` names it in error messages."""
        data = _parse.mapping(data, path)
        return cls(
            contract_version=_parse.string(data, "contractVersion", path, required=True),
            tool=_parse.obj(data, "tool", path, Tool.from_dict, required=True),
            summary=_parse.obj(data, "summary", path, Summary.from_dict, required=True),
            results=_parse.objects(data, "results", path, FileResult.from_dict, required=True),
            extra=_parse.extra(data, cls._JSON_FIELDS),
        )
