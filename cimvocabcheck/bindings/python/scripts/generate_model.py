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

"""Generates the binding's report types from the published JSON Schema.

The schema under ``src/cimvocabcheck/schemas/`` is the contract; these types are derived from it
so a contract minor (a new optional field, a new rule code) is picked up by re-running this script
rather than by hand-editing a model and hoping it matches.

Usage::

    python scripts/generate_model.py            # (re)write the generated modules
    python scripts/generate_model.py --check    # fail with a diff if they are stale

``--check`` is what CI and the test suite run: it is the guard that a contract change cannot land
with a model that no longer matches it.
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
import textwrap
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

PACKAGE = Path(__file__).resolve().parent.parent / "src" / "cimvocabcheck"
SCHEMA = PACKAGE / "schemas" / "cimvocabcheck-report-1.schema.json"

LINE_LENGTH = 100

LICENSE_HEADER = """\
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
# Produced from schemas/cimvocabcheck-report-1.schema.json by scripts/generate_model.py.
# Behaviour that is convenience rather than contract belongs in _ergonomics.py, which survives
# regeneration.
# ---------------------------------------------------------------------------------------------
"""

# Generated classes get their convenience methods from these hand-written mixins.
MIXINS = {
    "Report": "ReportMixin",
    "FileResult": "FileResultMixin",
    "Annotation": "AnnotationMixin",
}

SCALAR_READERS = {"string": "string", "integer": "integer", "boolean": "boolean"}
SCALAR_TYPES = {"string": "str", "integer": "int", "boolean": "bool"}


# ---- Schema model -----------------------------------------------------------------------------


@dataclass
class Field:
    json_name: str
    py_name: str
    py_type: str
    required: bool
    reader: str
    doc: str
    default: str | None = None


@dataclass
class Klass:
    name: str
    doc: str
    fields: list[Field] = field(default_factory=list)
    deps: list[str] = field(default_factory=list)


def pascal(name: str) -> str:
    return name[:1].upper() + name[1:]


def snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def wrap(text: str, indent: str, width: int = LINE_LENGTH) -> list[str]:
    return textwrap.wrap(
        " ".join(text.split()),
        width=width - len(indent),
        break_long_words=False,
        break_on_hyphens=False,
    ) or [""]


def literal(value: str) -> str:
    """Renders a string literal the way the rest of the package is written: double quotes."""
    return json.dumps(value)


def split_literal(value: str, indent: str) -> list[str]:
    """Renders a long literal as implicit concatenation so no generated line overruns."""
    budget = LINE_LENGTH - len(indent) - 4 - 2  # the two quotes the literal adds
    chunks, rest = [], value
    while rest:
        if len(rest) <= budget:
            chunks.append(rest)
            break
        head = rest[:budget]
        cut = head.rfind("/") + 1 or budget  # prefer a path boundary over a mid-word split
        chunks.append(rest[:cut])
        rest = rest[cut:]
    return [f"{indent}    {literal(c)}" for c in chunks]


def emit_argument(out: list[str], indent: str, name: str, expr: str) -> None:
    """Emits ``name=expr,`` as a keyword argument, wrapping the call when the line would overrun."""
    line = f"{indent}{name}={expr},"
    if len(line) <= LINE_LENGTH:
        out.append(line)
        return
    head, _, args = expr.partition("(")
    out.append(f"{indent}{name}={head}(")
    for arg in args.rstrip(")").split(", "):
        out.append(f"{indent}    {arg},")
    out.append(f"{indent}),")


def docstring(text: str, indent: str) -> list[str]:
    """Renders a description as a docstring, one-line when it fits."""
    lines = wrap(text, indent, LINE_LENGTH - 6)
    if len(lines) == 1:
        return [f'{indent}"""{lines[0]}"""']
    return (
        [f'{indent}"""{lines[0]}']
        + [f"{indent}{line}" for line in lines[1:]]
        + [f'{indent}"""']
    )


class Generator:
    def __init__(self, schema: Mapping[str, Any]) -> None:
        self.schema = schema
        self.definitions: Mapping[str, Any] = schema.get("definitions", {})
        self.classes: dict[str, Klass] = {}
        self.enums: list[tuple[str, str, tuple[str, ...]]] = []  # (class, doc, members)

    # -- traversal ------------------------------------------------------------------------------

    def collect(self) -> None:
        for name, node in self.definitions.items():
            if "enum" in node:
                self.enums.append((pascal(name), node.get("description", ""), tuple(node["enum"])))
            elif node.get("type") == "object":
                self.add_class(pascal(name), node)
        self.add_class("Report", self.schema)

    def add_class(self, name: str, node: Mapping[str, Any]) -> Klass:
        if name in self.classes:
            return self.classes[name]
        klass = Klass(name=name, doc=node.get("description", ""))
        self.classes[name] = klass  # registered before recursion so self-reference terminates
        required = list(node.get("required", []))
        properties: Mapping[str, Any] = node.get("properties", {})
        ordered = [k for k in properties if k in required] + [
            k for k in properties if k not in required
        ]
        for json_name in ordered:
            klass.fields.append(
                self.build_field(name, json_name, properties[json_name], json_name in required)
            )
        return klass

    def build_field(
        self, owner: str, json_name: str, node: Mapping[str, Any], required: bool
    ) -> Field:
        doc = node.get("description", "")
        target = self.resolve(node)
        py_name = snake(json_name)
        args = f'data, "{json_name}", path'
        req = ", required=True" if required else ""

        if "$ref" in node and target is not node:
            ref_name = node["$ref"].rsplit("/", 1)[-1]
            if "enum" in target:
                # Deliberately typed `str`, not an enum: the contract adds codes in minor versions
                # and requires consumers to tolerate ones they do not know. The constants in
                # _codes.py are for comparison, not for validation.
                doc = doc or target.get("description", "")
                return Field(
                    json_name,
                    py_name,
                    "str",
                    required,
                    f"_parse.string({args}{req})",
                    doc + f" See :class:`{pascal(ref_name)}` for the values known to this binding.",
                    None if required else "None",
                )
            cls = pascal(ref_name)
            self.classes[owner].deps.append(cls)
            return Field(
                json_name,
                py_name,
                cls,
                required,
                f"_parse.obj({args}, {cls}.from_dict{req})",
                doc,
                None if required else "None",
            )

        kind = node.get("type")
        if kind == "array":
            item = node.get("items", {})
            item_target = self.resolve(item)
            if "$ref" in item and "enum" not in item_target:
                cls = pascal(item["$ref"].rsplit("/", 1)[-1])
                self.classes[owner].deps.append(cls)
                return Field(
                    json_name,
                    py_name,
                    f"tuple[{cls}, ...]",
                    required,
                    f"_parse.objects({args}, {cls}.from_dict{req})",
                    doc,
                    None if required else "()",
                )
            if item_target.get("type") != "string":
                raise SystemExit(f"unsupported array item type in {owner}.{json_name}")
            return Field(
                json_name,
                py_name,
                "tuple[str, ...]",
                required,
                f"_parse.strings({args}{req})",
                doc,
                None if required else "()",
            )

        if kind == "object":
            cls = pascal(json_name)
            self.add_class(cls, node)
            self.classes[owner].deps.append(cls)
            return Field(
                json_name,
                py_name,
                cls,
                required,
                f"_parse.obj({args}, {cls}.from_dict{req})",
                doc,
                None if required else "None",
            )

        if kind in SCALAR_READERS:
            py_type = SCALAR_TYPES[kind]
            return Field(
                json_name,
                py_name,
                py_type if required else f"{py_type} | None",
                required,
                f"_parse.{SCALAR_READERS[kind]}({args}{req})",
                doc,
                None if required else "None",
            )

        raise SystemExit(f"unsupported schema type {kind!r} at {owner}.{json_name}")

    def resolve(self, node: Mapping[str, Any]) -> Mapping[str, Any]:
        if "$ref" not in node:
            return node
        ref = node["$ref"]
        if not ref.startswith("#/definitions/"):
            raise SystemExit(f"unsupported $ref {ref!r}")
        return self.definitions[ref.split("/")[-1]]

    def order(self) -> list[Klass]:
        """Emits each class after the ones it references, so no forward declarations are needed."""
        emitted: list[Klass] = []
        seen: set = set()

        def visit(name: str) -> None:
            if name in seen:
                return
            seen.add(name)
            for dep in self.classes[name].deps:
                visit(dep)
            emitted.append(self.classes[name])

        for name in self.classes:
            visit(name)
        return emitted

    # -- emission -------------------------------------------------------------------------------

    def codes_module(self) -> str:
        major = SCHEMA.name.split("-")[-1].split(".")[0]
        out = [
            LICENSE_HEADER,
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
            f"CONTRACT_MAJOR = {major}",
            "",
            "#: Canonical URL of the schema these types were generated from.",
            "SCHEMA_ID = (",
        ]
        out.extend(split_literal(self.schema.get("$id", ""), ""))
        out.extend([")", ""])
        for name, doc, members in self.enums:
            out.append("")
            out.append(f"class {name}:")
            out.extend(docstring(doc or f"{name} values.", "    "))
            out.append("")
            for member in members:
                out.append(f"    {member} = {literal(member)}")
            out.append("")
            out.append("    ALL: Final[tuple[str, ...]] = (")
            for member in members:
                out.append(f"        {member},")
            out.append("    )")
            out.append("")
        out.append("")
        out.append("#: Every rule code this binding knows; a newer engine may report others.")
        out.append("KNOWN_CODES: Final[frozenset[str]] = frozenset(Code.ALL)")
        out.append("")
        out.append("#: Every severity this binding knows.")
        out.append("KNOWN_SEVERITIES: Final[frozenset[str]] = frozenset(Severity.ALL)")
        out.append("")
        return "\n".join(out)

    def model_module(self) -> str:
        mixins = sorted({MIXINS[k.name] for k in self.classes.values() if k.name in MIXINS})
        out = [
            LICENSE_HEADER,
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
            "from ._ergonomics import {}".format(", ".join(mixins)),
            "",
        ]
        for klass in self.order():
            out.append("")
            base = MIXINS.get(klass.name)
            out.append("@dataclass(frozen=True)")
            out.append(f"class {klass.name}({base}):" if base else f"class {klass.name}:")
            out.extend(docstring(klass.doc or f"{klass.name}.", "    "))
            out.append("")
            for f in klass.fields:
                if f.doc:
                    for line in wrap(f.doc, "    #: "):
                        out.append(f"    #: {line}")
                if f.default is None:
                    out.append(f"    {f.py_name}: {f.py_type}")
                elif f.default == "()":
                    out.append(f"    {f.py_name}: {f.py_type} = ()")
                else:
                    out.append(f"    {f.py_name}: {f.py_type} = {f.default}")
            out.append("    #: Fields the engine emitted that this binding does not know about.")
            out.append("    extra: dict[str, Any] = field(default_factory=dict)")
            out.append("")
            # Unannotated, so the dataclass machinery does not mistake it for a field.
            out.append("    _JSON_FIELDS = (")
            for f in klass.fields:
                out.append(f"        {literal(f.json_name)},")
            out.append("    )")
            out.append("")
            out.append("    @classmethod")
            # `from __future__ import annotations` makes the forward reference resolvable
            # without quotes, and a quoted one would be flagged as redundant.
            out.append(f'    def from_dict(cls, data: Any, path: str = "$") -> {klass.name}:')
            out.extend(
                docstring(
                    "Reads this node from decoded JSON; ``path`` names it in error messages.",
                    "        ",
                )
            )
            out.append("        data = _parse.mapping(data, path)")
            out.append("        return cls(")
            for f in klass.fields:
                emit_argument(out, "            ", f.py_name, f.reader)
            out.append("            extra=_parse.extra(data, cls._JSON_FIELDS),")
            out.append("        )")
            out.append("")
        return "\n".join(out)


def generate() -> dict[Path, str]:
    generator = Generator(json.loads(SCHEMA.read_text(encoding="utf-8")))
    generator.collect()
    return {
        PACKAGE / "_codes.py": generator.codes_module(),
        PACKAGE / "_model.py": generator.model_module(),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="do not write; exit 1 with a diff when a generated module is stale",
    )
    args = parser.parse_args(argv)

    stale = False
    for path, content in generate().items():
        current = path.read_text(encoding="utf-8") if path.exists() else ""
        if current == content:
            continue
        stale = True
        if args.check:
            sys.stdout.writelines(
                difflib.unified_diff(
                    current.splitlines(keepends=True),
                    content.splitlines(keepends=True),
                    fromfile=f"{path.name} (committed)",
                    tofile=f"{path.name} (regenerated)",
                )
            )
        else:
            path.write_text(content, encoding="utf-8")
            print(f"wrote {path}")

    if args.check and stale:
        print(
            "\nThe generated model no longer matches the schema. "
            "Run: python scripts/generate_model.py",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
