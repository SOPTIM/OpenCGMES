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

"""Python binding for CIMVocabCheck.

Validates SPARQL queries and SHACL shapes against CIM/CGMES schema profiles by driving the
CIMVocabCheck CLI and parsing its published JSON report:

    >>> from cimvocabcheck import validate
    >>> report = validate(["queries/line-segments.rq"], schema="schemas")   # doctest: +SKIP
    >>> report.ok                                                          # doctest: +SKIP
    False

The engine is a single Java implementation — there is no second SPARQL analyser here — so every
consumer, in every language, gets the same findings. This package supplies the ergonomics: engine
discovery, argv construction, and a typed result generated from the published contract.
"""

from __future__ import annotations

from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as _version

from ._codes import CONTRACT_MAJOR, KNOWN_CODES, KNOWN_SEVERITIES, SCHEMA_ID, Code, Severity
from ._ergonomics import Finding, severity_at_least
from ._model import Annotation, FileResult, Report, Summary, Tool
from .api import (
    STDIN_NAME,
    TURTLE_SUFFIXES,
    Strictness,
    build_args,
    parse_report,
    tool_version,
    validate,
    validate_file,
    validate_text,
)
from .errors import (
    CimVocabCheckError,
    EngineNotFoundError,
    EngineTimeoutError,
    ReportParseError,
    ToolError,
)
from .runtime import BinaryEngine, DockerEngine, Engine, JarEngine, discover

try:
    __version__ = _version("cimvocabcheck")
except PackageNotFoundError:  # pragma: no cover - a source checkout, not an installed distribution
    __version__ = "0.0.0.dev0"

__all__ = [
    "Annotation",
    "BinaryEngine",
    "CONTRACT_MAJOR",
    "CimVocabCheckError",
    "Code",
    "DockerEngine",
    "Engine",
    "EngineNotFoundError",
    "EngineTimeoutError",
    "FileResult",
    "Finding",
    "JarEngine",
    "KNOWN_CODES",
    "KNOWN_SEVERITIES",
    "Report",
    "ReportParseError",
    "SCHEMA_ID",
    "STDIN_NAME",
    "Severity",
    "Strictness",
    "Summary",
    "TURTLE_SUFFIXES",
    "Tool",
    "ToolError",
    "__version__",
    "build_args",
    "discover",
    "parse_report",
    "severity_at_least",
    "tool_version",
    "validate",
    "validate_file",
    "validate_text",
]
