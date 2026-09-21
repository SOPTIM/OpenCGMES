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

"""Emits the .NET package's model: records read by a source-generated System.Text.Json context.

Required members are spelled with C#'s ``required`` keyword, which System.Text.Json enforces —
so a report missing a contract field fails as a JsonException naming the member, rather than
quietly deserializing into a default.
"""

from __future__ import annotations

import json
from pathlib import Path

from contract_ir import Contract, Field, Kind, Klass, pascal, split_url, wrap

ROOT = Path(__file__).resolve().parents[1] / "dotnet" / "src" / "Soptim.CimVocabCheck"

NAMESPACE = "Soptim.CimVocabCheck"

LINE_LENGTH = 110

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
// ------------------------------------------------------------------------------------------
// GENERATED FILE - DO NOT EDIT.
// Produced from cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json
// by cimvocabcheck/bindings/codegen/generate_models.py.
// Conveniences belong in Ergonomics.cs, which survives regeneration.
// ------------------------------------------------------------------------------------------

// A ".g.cs" file is auto-generated as far as the compiler is concerned, which switches the
// nullable context off unless the file turns it back on itself.
#nullable enable
"""

SCALARS = {Kind.STRING: "string", Kind.ENUM: "string", Kind.INTEGER: "int", Kind.BOOLEAN: "bool"}


def literal(value: str) -> str:
    return json.dumps(value)


def member(name: str) -> str:
    """SCREAMING_SNAKE_CASE from the schema becomes PascalCase, as .NET spells constants."""
    return "".join(part.capitalize() for part in name.split("_"))


def prop(json_name: str) -> str:
    return pascal(json_name)


def doc_comment(text: str, indent: str, tag: str = "summary") -> list[str]:
    """Renders an XML doc comment. ``text`` is already XML — escape schema prose before calling."""
    if not text:
        return []
    lines = wrap(text, LINE_LENGTH - len(indent) - 4)
    out = [f"{indent}/// <{tag}>"]
    out += [f"{indent}/// {line}" for line in lines]
    out.append(f"{indent}/// </{tag}>")
    return out


def escape_xml(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def field_type(f: Field) -> str:
    if f.kind is Kind.STRING_ARRAY:
        return "IReadOnlyList<string>"
    if f.kind is Kind.OBJECT_ARRAY:
        return f"IReadOnlyList<{f.ref}>"
    if f.kind is Kind.OBJECT:
        return f.ref if f.required else f"{f.ref}?"
    scalar = SCALARS[f.kind]
    return scalar if f.required else f"{scalar}?"


def is_optional_collection(f: Field) -> bool:
    return not f.required and f.kind in (Kind.STRING_ARRAY, Kind.OBJECT_ARRAY)


def field_doc(f: Field) -> str:
    doc = escape_xml(f.doc)
    if f.kind is Kind.ENUM:
        return f'{doc} See <see cref="{f.ref}"/> for the values known to this binding.'
    return doc


def codes_file(contract: Contract) -> str:
    out = [
        HEADER,
        BANNER,
        "namespace " + NAMESPACE + ";",
        "",
        "/// <summary>",
        "/// Constants of the published report contract.",
        "/// </summary>",
        "public static class Contract",
        "{",
        "    /// <summary>",
        "    /// Report contract major this binding targets; any report declaring it is accepted.",
        "    /// </summary>",
        f"    public const int Major = {contract.major};",
        "",
        "    /// <summary>",
        "    /// Canonical URL of the schema these types were generated from.",
        "    /// </summary>",
        "    public const string SchemaId =",
    ]
    chunks = split_url(contract.schema_id, LINE_LENGTH - 14)
    for i, chunk in enumerate(chunks):
        suffix = ";" if i == len(chunks) - 1 else " +"
        out.append(f"        {literal(chunk)}{suffix}")
    out.append("}")

    for group in contract.constants:
        out.append("")
        out += doc_comment(escape_xml(group.doc) or f"{group.name} values.", "")
        out += doc_comment(
            "Constants rather than an enum: the contract adds members in minor versions and "
            "obliges a consumer to tolerate one it does not know.",
            "",
            "remarks",
        )
        out.append(f"public static class {group.name}")
        out.append("{")
        for name in group.members:
            out.append(f"    /// <summary>The <c>{name}</c> {group.name.lower()}.</summary>")
            out.append(f"    public const string {member(name)} = {literal(name)};")
            out.append("")
        out.append(
            f"    /// <summary>Every {group.name.lower()} this binding knows, in schema order."
            "</summary>"
        )
        out.append("    public static readonly IReadOnlyList<string> All =")
        out.append("    [")
        out += [f"        {member(name)}," for name in group.members]
        out.append("    ];")
        out.append("}")
    out.append("")
    return "\n".join(out)


def model_file(contract: Contract) -> str:
    out = [
        HEADER,
        BANNER,
        "using System.Text.Json;",
        "using System.Text.Json.Serialization;",
        "",
        "namespace " + NAMESPACE + ";",
    ]
    for klass in contract.classes:
        out += _klass(klass)
    out += _serializer_context(contract)
    return "\n".join(out)


def _klass(klass: Klass) -> list[str]:
    out = [""]
    out += doc_comment(escape_xml(klass.doc) or f"{klass.name}.", "")
    out.append(f"public sealed record {klass.name}")
    out.append("{")
    for i, f in enumerate(klass.fields):
        if i:
            out.append("")
        out += doc_comment(field_doc(f), "    ")
        if is_optional_collection(f):
            # A property initializer would be the obvious way to default this to empty, but
            # System.Text.Json's source generator constructs a type that has `required` members
            # without running initializers — the reflection-based serializer does run them, so
            # the two would disagree. Coalescing in the getter is the only spelling that holds
            # for both.
            backing = f"_{f.json_name}"
            out.append(f"    private readonly {field_type(f)}? {backing};")
            out.append("")
            out += doc_comment(field_doc(f), "    ")
            out.append(f"    [JsonPropertyName({literal(f.json_name)})]")
            out.append(f"    public {field_type(f)} {prop(f.json_name)}")
            out.append("    {")
            out.append(f"        get => {backing} ?? [];")
            out.append(f"        init => {backing} = value;")
            out.append("    }")
            continue
        out.append(f"    [JsonPropertyName({literal(f.json_name)})]")
        modifier = "required " if f.required else ""
        out.append(f"    public {modifier}{field_type(f)} {prop(f.json_name)} {{ get; init; }}")
    out.append("")
    out.append("    /// <summary>")
    out.append("    /// Fields the engine emitted that this binding does not know about.")
    out.append("    /// </summary>")
    out.append("    /// <remarks>")
    out.append("    /// The contract only ever adds within a major version, so a field this")
    out.append("    /// binding predates is still meaningful to a caller that knows about it.")
    out.append("    /// </remarks>")
    out.append("    /// <remarks>")
    out.append("    /// Settable rather than init-only: System.Text.Json refuses to bind")
    out.append("    /// extension data through a constructor parameter, which is how it")
    out.append("    /// populates an init-only member.")
    out.append("    /// </remarks>")
    out.append("    [JsonExtensionData]")
    out.append("    public IDictionary<string, JsonElement>? Extra { get; set; }")
    out.append("}")
    return out


def _serializer_context(contract: Contract) -> list[str]:
    root = contract.classes[-1].name
    return [
        "",
        "/// <summary>",
        "/// Source-generated serialization metadata, so reading a report needs no reflection",
        f"/// and the package stays trimming- and AOT-friendly. Rooted at <see cref=\"{root}\"/>;",
        "/// the nested types come with it.",
        "/// </summary>",
        "[JsonSourceGenerationOptions(",
        "    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,",
        "    WriteIndented = false)]",
        f"[JsonSerializable(typeof({root}))]",
        "internal sealed partial class ReportJsonContext : JsonSerializerContext;",
        "",
    ]


def files(contract: Contract) -> dict[Path, str]:
    return {ROOT / "Codes.g.cs": codes_file(contract), ROOT / "Model.g.cs": model_file(contract)}
