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

"""Emits the Python binding's model: frozen dataclasses with hand-written mixins."""

from __future__ import annotations

import json
from pathlib import Path

from contract_ir import Constants, Contract, Field, Kind, Klass, snake, split_url, wrap

ROOT = Path(__file__).resolve().parents[1] / "python" / "src" / "cimvocabcheck"

LINE_LENGTH = 100

HEADER = """\
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
"""

BANNER = """\
# ---------------------------------------------------------------------------------------------
# GENERATED FILE - DO NOT EDIT.
# Produced from cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json
# by cimvocabcheck/bindings/codegen/generate_models.py.
# Behaviour that is convenience rather than contract belongs in _ergonomics.py, which survives
# regeneration.
# ---------------------------------------------------------------------------------------------
"""

#: Generated classes take their convenience methods from these hand-written mixins.
MIXINS = {"Report": "ReportMixin", "FileResult": "FileResultMixin", "Annotation": "AnnotationMixin"}

READERS = {
    Kind.STRING: "string",
    Kind.ENUM: "string",
    Kind.INTEGER: "integer",
    Kind.BOOLEAN: "boolean",
}
SCALARS = {Kind.STRING: "str", Kind.ENUM: "str", Kind.INTEGER: "int", Kind.BOOLEAN: "bool"}


def literal(value: str) -> str:
    return json.dumps(value)


def docstring(text: str, indent: str) -> list[str]:
    lines = wrap(text, LINE_LENGTH - len(indent) - 6)
    if len(lines) == 1:
        return [f'{indent}"""{lines[0]}"""']
    return [f'{indent}"""{lines[0]}'] + [f"{indent}{line}" for line in lines[1:]] + [f'{indent}"""']


def field_type(f: Field) -> str:
    if f.kind is Kind.STRING_ARRAY:
        return "tuple[str, ...]"
    if f.kind is Kind.OBJECT_ARRAY:
        return f"tuple[{f.ref}, ...]"
    if f.kind is Kind.OBJECT:
        return f.ref if f.required else f"{f.ref} | None"
    scalar = SCALARS[f.kind]
    return scalar if f.required else f"{scalar} | None"


def field_default(f: Field) -> str | None:
    if f.required:
        return None
    return "()" if f.kind in (Kind.STRING_ARRAY, Kind.OBJECT_ARRAY) else "None"


def field_reader(f: Field) -> str:
    args = f'data, "{f.json_name}", path'
    req = ", required=True" if f.required else ""
    if f.kind is Kind.STRING_ARRAY:
        return f"_parse.strings({args}{req})"
    if f.kind is Kind.OBJECT_ARRAY:
        return f"_parse.objects({args}, {f.ref}.from_dict{req})"
    if f.kind is Kind.OBJECT:
        return f"_parse.obj({args}, {f.ref}.from_dict{req})"
    return f"_parse.{READERS[f.kind]}({args}{req})"


def field_doc(f: Field) -> str:
    if f.kind is Kind.ENUM:
        return f"{f.doc} See :class:`{f.ref}` for the values known to this binding."
    return f.doc


def emit_argument(out: list[str], indent: str, name: str, expr: str) -> None:
    line = f"{indent}{name}={expr},"
    if len(line) <= LINE_LENGTH:
        out.append(line)
        return
    head, _, args = expr.partition("(")
    out.append(f"{indent}{name}={head}(")
    for arg in args.rstrip(")").split(", "):
        out.append(f"{indent}    {arg},")
    out.append(f"{indent}),")


def codes_module(contract: Contract) -> str:
    out = [
        HEADER,
        BANNER,
        '"""Contract constants: the severities and rule codes this binding knows.',
        "",
        "They are plain strings, not enum members. The contract adds rule codes in minor",
        "versions and obliges a consumer to treat an unrecognised code as a generic finding,",
        "so the model must not reject one — see :attr:`Annotation.is_known_code`.",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from typing import Final",
        "",
        "#: Report contract major this binding targets; any report declaring it is accepted.",
        f"CONTRACT_MAJOR = {contract.major}",
        "",
        "#: Canonical URL of the schema these types were generated from.",
        "SCHEMA_ID = (",
    ]
    out += [f"    {literal(c)}" for c in split_url(contract.schema_id, LINE_LENGTH - 6)]
    out += [")", ""]
    for group in contract.constants:
        out.append("")
        out.append(f"class {group.name}:")
        out += docstring(group.doc or f"{group.name} values.", "    ")
        out.append("")
        for member in group.members:
            out.append(f"    {member} = {literal(member)}")
        out.append("")
        out.append("    ALL: Final[tuple[str, ...]] = (")
        out += [f"        {member}," for member in group.members]
        out.append("    )")
        out.append("")
    out += [
        "",
        "#: Every rule code this binding knows; a newer engine may report others.",
        "KNOWN_CODES: Final[frozenset[str]] = frozenset(Code.ALL)",
        "",
        "#: Every severity this binding knows.",
        "KNOWN_SEVERITIES: Final[frozenset[str]] = frozenset(Severity.ALL)",
        "",
    ]
    return "\n".join(out)


def model_module(contract: Contract) -> str:
    mixins = sorted({MIXINS[k.name] for k in contract.classes if k.name in MIXINS})
    out = [
        HEADER,
        BANNER,
        '"""The report document, as typed Python.',
        "",
        "Unknown fields are kept in ``extra`` rather than dropped: the contract only ever adds",
        "within a major version, so a field this binding has not been regenerated for is still",
        "meaningful to a caller that knows about it.",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from dataclasses import dataclass, field",
        "from typing import Any",
        "",
        "from . import _parse",
        f"from ._ergonomics import {', '.join(mixins)}",
        "",
    ]
    for klass in contract.classes:
        out += _klass(klass)
    return "\n".join(out)


def _klass(klass: Klass) -> list[str]:
    base = MIXINS.get(klass.name)
    out = ["", "@dataclass(frozen=True)"]
    out.append(f"class {klass.name}({base}):" if base else f"class {klass.name}:")
    out += docstring(klass.doc or f"{klass.name}.", "    ")
    out.append("")
    for f in klass.fields:
        doc = field_doc(f)
        if doc:
            out += [f"    #: {line}" for line in wrap(doc, LINE_LENGTH - 7)]
        default = field_default(f)
        suffix = "" if default is None else f" = {default}"
        out.append(f"    {snake(f.json_name)}: {field_type(f)}{suffix}")
    out.append("    #: Fields the engine emitted that this binding does not know about.")
    out.append("    extra: dict[str, Any] = field(default_factory=dict)")
    out.append("")
    # Unannotated, so the dataclass machinery does not mistake it for a field.
    out.append("    _JSON_FIELDS = (")
    out += [f"        {literal(f.json_name)}," for f in klass.fields]
    out.append("    )")
    out.append("")
    out.append("    @classmethod")
    out.append(f'    def from_dict(cls, data: Any, path: str = "$") -> {klass.name}:')
    out += docstring(
        "Reads this node from decoded JSON; ``path`` names it in error messages.", "        "
    )
    out.append("        data = _parse.mapping(data, path)")
    out.append("        return cls(")
    for f in klass.fields:
        emit_argument(out, "            ", snake(f.json_name), field_reader(f))
    out.append("            extra=_parse.extra(data, cls._JSON_FIELDS),")
    out.append("        )")
    out.append("")
    return out


def files(contract: Contract) -> dict[Path, str]:
    return {ROOT / "_codes.py": codes_module(contract), ROOT / "_model.py": model_module(contract)}


# Silence the unused-import warning for a name kept for emitter symmetry.
_ = Constants
