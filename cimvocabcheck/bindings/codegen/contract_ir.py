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
    """A list of known values in the schema — rendered as constants, never as a closed type.

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


class SchemaError(Exception):
    """The schema uses a construct the generator does not know how to map.

    Raised instead of guessing: a construct read the wrong way produces a model that compiles in
    every language and then fails on every real report.
    """


#: Keywords that only constrain or describe a value, never change its generated type.
ANNOTATIONS = frozenset(
    {
        "description",
        "title",
        "$comment",
        "examples",
        "default",
        "format",
        "pattern",
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "minLength",
        "maxLength",
        "minItems",
        "maxItems",
        "uniqueItems",
        "readOnly",
        "writeOnly",
    }
)

OBJECT_KEYWORDS = ANNOTATIONS | {"type", "required", "properties", "additionalProperties"}
ROOT_KEYWORDS = OBJECT_KEYWORDS | {"$schema", "$id", "definitions"}
SCALAR_KEYWORDS = ANNOTATIONS | {"type"}
ARRAY_KEYWORDS = ANNOTATIONS | {"type", "items"}
REF_KEYWORDS = ANNOTATIONS | {"$ref"}
CONSTANTS_KEYWORDS = ANNOTATIONS | {"type", "enum", "anyOf"}


def load(path: Path = CANONICAL_SCHEMA) -> Contract:
    """Reads the published schema into the shape the emitters consume."""
    schema = json.loads(path.read_text(encoding="utf-8"))
    return read(schema, path.name)


def read(schema: Mapping[str, Any], file_name: str = CANONICAL_SCHEMA.name) -> Contract:
    """Reads an already-decoded schema; ``file_name`` carries the contract major."""
    return _Reader(schema, file_name).read()


def constant_members(node: Mapping[str, Any]) -> tuple[str, ...] | None:
    """The values a constants definition lists, or ``None`` when ``node`` is not one.

    Two spellings are recognised. A plain ``enum`` is a closed list. An ``anyOf`` of that list and
    a bare ``{"type": "string"}`` is an open one: it documents the known values while still
    accepting any string, which is what the contract means for codes and severities. Both become
    string constants, never a closed type, because a newer engine may report values the binding
    does not know.
    """
    if "enum" in node:
        return tuple(node["enum"])
    branches = node.get("anyOf")
    if not isinstance(branches, list) or len(branches) != 2:
        return None
    lists = [b for b in branches if set(b) == {"enum"}]
    strings = [b for b in branches if b == {"type": "string"}]
    if len(lists) != 1 or len(strings) != 1:
        return None
    return tuple(lists[0]["enum"])


class _Reader:
    def __init__(self, schema: Mapping[str, Any], file_name: str) -> None:
        self.schema = schema
        self.file_name = file_name
        self.definitions: Mapping[str, Any] = schema.get("definitions", {})
        self.classes: dict[str, Klass] = {}
        self.constants: list[Constants] = []

    def read(self) -> Contract:
        self.check_keywords(self.schema, ROOT_KEYWORDS, "#")
        for name, node in self.definitions.items():
            where = f"#/definitions/{name}"
            members = constant_members(node)
            if members is not None:
                self.check_keywords(node, CONSTANTS_KEYWORDS, where)
                self.check_string_type(node, where)
                if not all(isinstance(m, str) for m in members):
                    raise SchemaError(f"{where}: only string constants are supported")
                self.constants.append(Constants(pascal(name), node.get("description", ""), members))
            elif node.get("type") == "object":
                self.add_class(pascal(name), node, where)
            else:
                raise SchemaError(
                    f"{where}: a definition must be an object or a list of string constants "
                    "(an enum, or an anyOf of an enum and a plain string)"
                )
        self.add_class("Report", self.schema, "#")
        return Contract(
            schema=self.schema,
            major=int(self.file_name.split("-")[-1].split(".")[0]),
            schema_id=self.schema.get("$id", ""),
            constants=self.constants,
            classes=self.ordered(),
        )

    def add_class(self, name: str, node: Mapping[str, Any], where: str) -> Klass:
        if name in self.classes:
            return self.classes[name]
        if where != "#":
            self.check_keywords(node, OBJECT_KEYWORDS, where)
        if node.get("additionalProperties", True) is not True:
            # Every model keeps unknown fields, because the contract adds them in minor versions.
            raise SchemaError(f"{where}: objects must allow additional properties")
        klass = Klass(name=name, doc=node.get("description", ""))
        self.classes[name] = klass  # registered before recursion so self-reference terminates
        required = list(node.get("required", []))
        properties: Mapping[str, Any] = node.get("properties", {})
        unknown = [k for k in required if k not in properties]
        if unknown:
            raise SchemaError(f"{where}: required names no declared property: {unknown}")
        # Required first: languages that give optional fields a default need them last.
        ordered = [k for k in properties if k in required] + [
            k for k in properties if k not in required
        ]
        for json_name in ordered:
            klass.fields.append(
                self.read_field(
                    name,
                    json_name,
                    properties[json_name],
                    json_name in required,
                    f"{where}/properties/{json_name}",
                )
            )
        return klass

    def read_field(
        self, owner: str, json_name: str, node: Mapping[str, Any], required: bool, where: str
    ) -> Field:
        doc = node.get("description", "")

        if "$ref" in node:
            self.check_keywords(node, REF_KEYWORDS, where)
            ref, target = self.resolve(node, where)
            if constant_members(target) is not None:
                return Field(
                    json_name, Kind.ENUM, required, doc or target.get("description", ""), ref
                )
            self.classes[owner].deps.append(ref)
            self.add_class(ref, target, f"#/definitions/{node['$ref'].rsplit('/', 1)[-1]}")
            return Field(json_name, Kind.OBJECT, required, doc, ref)

        kind = node.get("type")
        if not isinstance(kind, str):
            raise SchemaError(f'{where}: needs a single "type" or a "$ref", got {kind!r}')

        if kind == "array":
            self.check_keywords(node, ARRAY_KEYWORDS, where)
            item = node.get("items")
            if not isinstance(item, Mapping):
                raise SchemaError(f'{where}: an array needs one "items" schema')
            if "$ref" in item:
                self.check_keywords(item, REF_KEYWORDS, f"{where}/items")
                ref, item_target = self.resolve(item, f"{where}/items")
                if constant_members(item_target) is None:
                    self.classes[owner].deps.append(ref)
                    self.add_class(
                        ref, item_target, f"#/definitions/{item['$ref'].rsplit('/', 1)[-1]}"
                    )
                    return Field(json_name, Kind.OBJECT_ARRAY, required, doc, ref)
                return Field(json_name, Kind.STRING_ARRAY, required, doc)
            self.check_keywords(item, SCALAR_KEYWORDS, f"{where}/items")
            if item.get("type") != "string":
                raise SchemaError(f"{where}/items: only string or $ref items are supported")
            return Field(json_name, Kind.STRING_ARRAY, required, doc)

        if kind == "object":
            ref = pascal(json_name)
            self.add_class(ref, node, where)
            self.classes[owner].deps.append(ref)
            return Field(json_name, Kind.OBJECT, required, doc, ref)

        scalars = {"string": Kind.STRING, "integer": Kind.INTEGER, "boolean": Kind.BOOLEAN}
        if kind in scalars:
            if "enum" in node or "anyOf" in node:
                raise SchemaError(
                    f"{where}: declare value lists under definitions and $ref them, so every "
                    "binding gets constants for them"
                )
            self.check_keywords(node, SCALAR_KEYWORDS, where)
            return Field(json_name, scalars[kind], required, doc)

        raise SchemaError(f"{where}: unsupported type {kind!r}")

    def resolve(self, node: Mapping[str, Any], where: str) -> tuple[str, Mapping[str, Any]]:
        ref = node["$ref"]
        name = ref.removeprefix("#/definitions/")
        if name == ref or "/" in name:
            raise SchemaError(f"{where}: only #/definitions/<name> references are supported")
        if name not in self.definitions:
            raise SchemaError(f"{where}: {ref} does not exist")
        return pascal(name), self.definitions[name]

    @staticmethod
    def check_keywords(node: Mapping[str, Any], allowed: frozenset[str], where: str) -> None:
        unknown = sorted(set(node) - allowed)
        if unknown:
            raise SchemaError(f"{where}: unsupported keyword(s) {unknown}")

    @staticmethod
    def check_string_type(node: Mapping[str, Any], where: str) -> None:
        if node.get("type", "string") != "string":
            raise SchemaError(f"{where}: constants must be strings")

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
