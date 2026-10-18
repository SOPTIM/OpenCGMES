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

"""The report schema, read once into a language-neutral shape.

Every binding's model comes from here, so "what the contract says" is decided in one place and
the per-language emitters only decide how to spell it. Adding a fourth language is an emitter,
not another reading of the schema.
"""

from __future__ import annotations

import json
import re
import textwrap
from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

#: The published contract. Bindings may vendor a copy, but this is the one that is generated from.
CANONICAL_SCHEMA = (
    Path(__file__).resolve().parents[2] / "schemas" / "cimvocabcheck-report-1.schema.json"
)


class Kind(Enum):
    """What a field holds, independently of how any one language spells it."""

    STRING = "string"
    INTEGER = "integer"
    BOOLEAN = "boolean"
    ENUM = "enum"
    OBJECT = "object"
    STRING_ARRAY = "string-array"
    OBJECT_ARRAY = "object-array"


@dataclass(frozen=True)
class Field:
    """One property of one object in the report."""

    json_name: str
    kind: Kind
    required: bool
    doc: str
    #: Name of the referenced class (OBJECT, OBJECT_ARRAY) or constants group (ENUM).
    ref: str | None = None


@dataclass
class Klass:
    """One object type in the report."""

    name: str
    doc: str
    fields: list[Field] = field(default_factory=list)
    deps: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class Constants:
    """A closed set in the schema — rendered as constants, never as a closed type.

    The contract adds members in minor versions and obliges consumers to tolerate ones they do
    not know, so a language enum would be exactly the wrong shape.
    """

    name: str
    doc: str
    members: tuple[str, ...]


@dataclass
class Contract:
    """Everything the emitters need: the schema, flattened and ordered."""

    schema: Mapping[str, Any]
    major: int
    schema_id: str
    constants: list[Constants]
    classes: list[Klass]

    def constants_named(self, name: str) -> Constants | None:
        return next((c for c in self.constants if c.name == name), None)


# ---- Reading ----------------------------------------------------------------------------------


def load(path: Path = CANONICAL_SCHEMA) -> Contract:
    """Reads the published schema into the shape the emitters consume."""
    schema = json.loads(path.read_text(encoding="utf-8"))
    return _Reader(schema, path).read()


class _Reader:
    def __init__(self, schema: Mapping[str, Any], path: Path) -> None:
        self.schema = schema
        self.path = path
        self.definitions: Mapping[str, Any] = schema.get("definitions", {})
        self.classes: dict[str, Klass] = {}
        self.constants: list[Constants] = []

    def read(self) -> Contract:
        for name, node in self.definitions.items():
            if "enum" in node:
                self.constants.append(
                    Constants(pascal(name), node.get("description", ""), tuple(node["enum"]))
                )
            elif node.get("type") == "object":
                self.add_class(pascal(name), node)
        self.add_class("Report", self.schema)
        return Contract(
            schema=self.schema,
            major=int(self.path.name.split("-")[-1].split(".")[0]),
            schema_id=self.schema.get("$id", ""),
            constants=self.constants,
            classes=self.ordered(),
        )

    def add_class(self, name: str, node: Mapping[str, Any]) -> Klass:
        if name in self.classes:
            return self.classes[name]
        klass = Klass(name=name, doc=node.get("description", ""))
        self.classes[name] = klass  # registered before recursion so self-reference terminates
        required = list(node.get("required", []))
        properties: Mapping[str, Any] = node.get("properties", {})
        # Required first: languages that give optional fields a default need them last.
        ordered = [k for k in properties if k in required] + [
            k for k in properties if k not in required
        ]
        for json_name in ordered:
            klass.fields.append(
                self.read_field(name, json_name, properties[json_name], json_name in required)
            )
        return klass

    def read_field(
        self, owner: str, json_name: str, node: Mapping[str, Any], required: bool
    ) -> Field:
        doc = node.get("description", "")
        target = self.resolve(node)

        if "$ref" in node:
            ref = pascal(node["$ref"].rsplit("/", 1)[-1])
            if "enum" in target:
                return Field(
                    json_name, Kind.ENUM, required, doc or target.get("description", ""), ref
                )
            self.classes[owner].deps.append(ref)
            self.add_class(ref, target)
            return Field(json_name, Kind.OBJECT, required, doc, ref)

        kind = node.get("type")
        if kind == "array":
            item = node.get("items", {})
            item_target = self.resolve(item)
            if "$ref" in item and "enum" not in item_target:
                ref = pascal(item["$ref"].rsplit("/", 1)[-1])
                self.classes[owner].deps.append(ref)
                self.add_class(ref, item_target)
                return Field(json_name, Kind.OBJECT_ARRAY, required, doc, ref)
            if item_target.get("type") != "string":
                raise SystemExit(f"unsupported array item type in {owner}.{json_name}")
            return Field(json_name, Kind.STRING_ARRAY, required, doc)

        if kind == "object":
            ref = pascal(json_name)
            self.add_class(ref, node)
            self.classes[owner].deps.append(ref)
            return Field(json_name, Kind.OBJECT, required, doc, ref)

        scalars = {"string": Kind.STRING, "integer": Kind.INTEGER, "boolean": Kind.BOOLEAN}
        if kind in scalars:
            return Field(json_name, scalars[kind], required, doc)

        raise SystemExit(f"unsupported schema type {kind!r} at {owner}.{json_name}")

    def resolve(self, node: Mapping[str, Any]) -> Mapping[str, Any]:
        if "$ref" not in node:
            return node
        ref = node["$ref"]
        if not ref.startswith("#/definitions/"):
            raise SystemExit(f"unsupported $ref {ref!r}")
        return self.definitions[ref.split("/")[-1]]

    def ordered(self) -> list[Klass]:
        """Dependencies first, so a language needing declaration order gets one."""
        emitted: list[Klass] = []
        seen: set[str] = set()

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


# ---- Shared text helpers ------------------------------------------------------------------------


def pascal(name: str) -> str:
    return name[:1].upper() + name[1:]


def snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def wrap(text: str, width: int) -> list[str]:
    """Reflows a schema description; never breaks a hyphenated term or a URL."""
    return textwrap.wrap(
        " ".join(text.split()),
        width=width,
        break_long_words=False,
        break_on_hyphens=False,
    ) or [""]


def split_url(value: str, budget: int) -> list[str]:
    """Chops a long literal into chunks a language can concatenate, preferring path boundaries."""
    chunks, rest = [], value
    while rest:
        if len(rest) <= budget:
            chunks.append(rest)
            break
        head = rest[:budget]
        cut = head.rfind("/") + 1 or budget
        chunks.append(rest[:cut])
        rest = rest[cut:]
    return chunks
