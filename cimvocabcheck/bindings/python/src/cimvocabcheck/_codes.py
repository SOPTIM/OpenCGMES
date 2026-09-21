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
# Produced from cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json
# by cimvocabcheck/bindings/codegen/generate_models.py.
# Behaviour that is convenience rather than contract belongs in _ergonomics.py, which survives
# regeneration.
# ---------------------------------------------------------------------------------------------

"""Contract constants: the severities and rule codes this binding knows.

They are plain strings, not enum members. The contract adds rule codes in minor
versions and obliges a consumer to treat an unrecognised code as a generic finding,
so the model must not reject one — see :attr:`Annotation.is_known_code`.
"""

from __future__ import annotations

from typing import Final

#: Report contract major this binding targets; any report declaring it is accepted.
CONTRACT_MAJOR = 1

#: Canonical URL of the schema these types were generated from.
SCHEMA_ID = (
    "https://raw.githubusercontent.com/SOPTIM/OpenCGMES/main/cimvocabcheck/schemas/"
    "cimvocabcheck-report-1.schema.json"
)


class Severity:
    """Severity after --strictness has been applied."""

    ERROR = "ERROR"
    WARN = "WARN"
    INFO = "INFO"

    ALL: Final[tuple[str, ...]] = (
        ERROR,
        WARN,
        INFO,
    )


class Code:
    """Stable identifier of the rule that triggered — the key automation should switch on. The
    catalogue is documented at https://opencgmes.soptim.de/cimvocabcheck/validation-checks.
    New codes are added in minor contract versions, so consumers must treat an unrecognised
    code as a generic finding rather than an error.
    """

    SYNTAX_ERROR = "SYNTAX_ERROR"
    UNKNOWN_CLASS = "UNKNOWN_CLASS"
    UNKNOWN_PROPERTY = "UNKNOWN_PROPERTY"
    UNKNOWN_VOCABULARY_TERM = "UNKNOWN_VOCABULARY_TERM"
    GRAPH_NOT_CONFIGURED = "GRAPH_NOT_CONFIGURED"
    UNSUPPORTED_DYNAMIC_PROPERTY = "UNSUPPORTED_DYNAMIC_PROPERTY"
    QUERY_IMPLIED_TYPE = "QUERY_IMPLIED_TYPE"
    DATATYPE_MISMATCH = "DATATYPE_MISMATCH"
    PROPERTY_NOT_ALLOWED_FOR_CLASS = "PROPERTY_NOT_ALLOWED_FOR_CLASS"
    NODE_KIND_INCOMPATIBLE_WITH_RANGE = "NODE_KIND_INCOMPATIBLE_WITH_RANGE"
    DATATYPE_INCOMPATIBLE_WITH_RANGE = "DATATYPE_INCOMPATIBLE_WITH_RANGE"
    CLASS_INCOMPATIBLE_WITH_RANGE = "CLASS_INCOMPATIBLE_WITH_RANGE"
    INVALID_CARDINALITY = "INVALID_CARDINALITY"
    INVALID_ENUM_VALUE = "INVALID_ENUM_VALUE"
    INVALID_VALUE_RANGE = "INVALID_VALUE_RANGE"
    UNKNOWN_TERM_IN_EXPRESSION = "UNKNOWN_TERM_IN_EXPRESSION"
    CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY = "CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY"
    PROJECTED_VARIABLE_UNBOUND = "PROJECTED_VARIABLE_UNBOUND"
    UNUSED_VARIABLE = "UNUSED_VARIABLE"

    ALL: Final[tuple[str, ...]] = (
        SYNTAX_ERROR,
        UNKNOWN_CLASS,
        UNKNOWN_PROPERTY,
        UNKNOWN_VOCABULARY_TERM,
        GRAPH_NOT_CONFIGURED,
        UNSUPPORTED_DYNAMIC_PROPERTY,
        QUERY_IMPLIED_TYPE,
        DATATYPE_MISMATCH,
        PROPERTY_NOT_ALLOWED_FOR_CLASS,
        NODE_KIND_INCOMPATIBLE_WITH_RANGE,
        DATATYPE_INCOMPATIBLE_WITH_RANGE,
        CLASS_INCOMPATIBLE_WITH_RANGE,
        INVALID_CARDINALITY,
        INVALID_ENUM_VALUE,
        INVALID_VALUE_RANGE,
        UNKNOWN_TERM_IN_EXPRESSION,
        CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY,
        PROJECTED_VARIABLE_UNBOUND,
        UNUSED_VARIABLE,
    )


#: Every rule code this binding knows; a newer engine may report others.
KNOWN_CODES: Final[frozenset[str]] = frozenset(Code.ALL)

#: Every severity this binding knows.
KNOWN_SEVERITIES: Final[frozenset[str]] = frozenset(Severity.ALL)
