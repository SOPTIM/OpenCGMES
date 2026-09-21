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

"""Emits the Rust crate's model: serde structs, with the conveniences left to ergonomics.rs.

Inherent impls may live in any module of the defining crate, so the hand-written half needs no
trait or mixin plumbing — it just writes ``impl Report`` next door.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from contract_ir import Contract, Field, Kind, Klass, snake, split_url, wrap

ROOT = Path(__file__).resolve().parents[1] / "rust" / "src"

LINE_LENGTH = 100

HEADER = """\
//    Copyright (c) 2026 SOPTIM AG
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
//    SPDX-License-Identifier: Apache-2.0
"""

BANNER = """\
// -----------------------------------------------------------------------------------------
// GENERATED FILE - DO NOT EDIT.
// Produced from cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json
// by cimvocabcheck/bindings/codegen/generate_models.py.
// Conveniences belong in ergonomics.rs, which survives regeneration.
// -----------------------------------------------------------------------------------------
"""

SCALARS = {Kind.STRING: "String", Kind.ENUM: "String", Kind.INTEGER: "u64", Kind.BOOLEAN: "bool"}


def literal(value: str) -> str:
    return json.dumps(value)


def camel(name: str) -> str:
    """What serde's ``rename_all = "camelCase"`` makes of a snake_case field name."""
    head, *rest = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


def rustdoc_safe(text: str) -> str:
    """Angle-brackets bare URLs, which rustdoc's ``bare_urls`` lint otherwise reports."""

    def bracket(match: re.Match[str]) -> str:
        url = match.group(0)
        trailing = len(url) - len(url.rstrip(".,;:"))
        return f"<{url[: len(url) - trailing]}>{url[len(url) - trailing :]}" if url else url

    return re.sub(r"https?://[^\s<>]+", bracket, text)


def doc_comment(text: str, indent: str) -> list[str]:
    if not text:
        return []
    width = LINE_LENGTH - len(indent) - 4
    return [f"{indent}/// {line}" for line in wrap(rustdoc_safe(text), width)]


def field_type(f: Field) -> str:
    if f.kind is Kind.STRING_ARRAY:
        return "Vec<String>"
    if f.kind is Kind.OBJECT_ARRAY:
        return f"Vec<{f.ref}>"
    if f.kind is Kind.OBJECT:
        return f.ref if f.required else f"Option<{f.ref}>"
    scalar = SCALARS[f.kind]
    return scalar if f.required else f"Option<{scalar}>"


def field_attribute(f: Field) -> str | None:
    """Serde attributes: absent optionals must not become deserialization errors."""
    if f.required:
        return None
    if f.kind in (Kind.STRING_ARRAY, Kind.OBJECT_ARRAY):
        return '#[serde(default, skip_serializing_if = "Vec::is_empty")]'
    return '#[serde(default, skip_serializing_if = "Option::is_none")]'


def field_doc(f: Field) -> str:
    if f.kind is Kind.ENUM:
        return f"{f.doc} See [`{f.ref}`](crate::{f.ref}) for the values known to this binding."
    return f.doc


def rust_const(name: str, value: str) -> list[str]:
    """Emits a constant the way rustfmt would: one line, or the value on the next."""
    one_line = f"    pub const {name}: &'static str = {value};"
    if len(one_line) <= LINE_LENGTH:
        return [one_line]
    return [f"    pub const {name}: &'static str =", f"        {value};"]


def rust_array(declaration: str, items: list[str]) -> list[str]:
    """Emits an array the way rustfmt would: inline while it fits, one item per line otherwise."""
    one_line = f"    {declaration} = [{', '.join(items)}];"
    if len(one_line) <= LINE_LENGTH:
        return [one_line]
    return [f"    {declaration} = ["] + [f"        {item}," for item in items] + ["    ];"]


def codes_module(contract: Contract) -> str:
    out = [
        HEADER,
        BANNER,
        "/// Report contract major this binding targets; any report declaring it is accepted.",
        f"pub const CONTRACT_MAJOR: u64 = {contract.major};",
        "",
        "/// Canonical URL of the schema these types were generated from.",
        "pub const SCHEMA_ID: &str = concat!(",
    ]
    out += [f"    {literal(c)}," for c in split_url(contract.schema_id, LINE_LENGTH - 8)]
    out.append(");")
    for group in contract.constants:
        out.append("")
        out += doc_comment(group.doc or f"{group.name} values.", "")
        out += doc_comment(
            "Constants rather than an enum: the contract adds members in minor versions and "
            "obliges a consumer to tolerate one it does not know.",
            "",
        )
        out.append(f"pub struct {group.name};")
        out.append("")
        out.append(f"impl {group.name} {{")
        for i, member in enumerate(group.members):
            if i:
                out.append("")
            out.append(f"    /// The `{member}` {group.name.lower()}.")
            out += rust_const(member, literal(member))
        out.append("")
        out.append(f"    /// Every {group.name.lower()} this binding knows, in schema order.")
        out += rust_array(
            f"pub const ALL: [&'static str; {len(group.members)}]",
            [f"Self::{member}" for member in group.members],
        )
        out.append("}")
    out.append("")
    return "\n".join(out)


def model_module(contract: Contract) -> str:
    out = [
        HEADER,
        BANNER,
        "//! The report document, as Rust types.",
        "//!",
        "//! Unknown fields are collected into `extra` rather than rejected: the contract only",
        "//! ever adds within a major version, so a field this crate has not been regenerated",
        "//! for is still meaningful to a caller that knows about it.",
        "",
        "use std::collections::BTreeMap;",
        "",
        "use serde::{Deserialize, Serialize};",
        "",
    ]
    for klass in contract.classes:
        out += _klass(klass)
    return "\n".join(out)


def _klass(klass: Klass) -> list[str]:
    out = doc_comment(klass.doc or f"{klass.name}.", "")
    out.append("#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]")
    out.append('#[serde(rename_all = "camelCase")]')
    out.append(f"pub struct {klass.name} {{")
    for i, f in enumerate(klass.fields):
        if i:
            out.append("")
        out += doc_comment(field_doc(f), "    ")
        rust_name = snake(f.json_name)
        attribute = field_attribute(f)
        # rename_all covers the ordinary case; say it explicitly when it would not round-trip.
        if camel(rust_name) != f.json_name:
            rename = f'rename = {literal(f.json_name)}'
            attribute = (
                f"#[serde({rename})]"
                if attribute is None
                else attribute.replace("#[serde(", f"#[serde({rename}, ")
            )
        if attribute:
            out.append(f"    {attribute}")
        out.append(f"    pub {rust_name}: {field_type(f)},")
    out.append("")
    out.append("    /// Fields the engine emitted that this binding does not know about.")
    out.append("    #[serde(flatten)]")
    out.append("    pub extra: BTreeMap<String, serde_json::Value>,")
    out.append("}")
    out.append("")
    return out


def files(contract: Contract) -> dict[Path, str]:
    return {ROOT / "codes.rs": codes_module(contract), ROOT / "model.rs": model_module(contract)}
