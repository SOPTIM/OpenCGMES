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

"""What the generator does when the schema changes, which ``--check`` alone cannot show.

``--check`` only compares against today's schema. These tests feed it variations of the published
schema: the contract changes the generator exists for must reach all three models, and a construct
it cannot map must stop it rather than produce a model that compiles and then fails on every
report.

Run with ``python3 -m unittest discover -s cimvocabcheck/bindings/codegen/tests``.
"""

from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import contract_ir  # noqa: E402
import emit_csharp  # noqa: E402
import emit_python  # noqa: E402
import emit_rust  # noqa: E402
from contract_ir import Kind, SchemaError  # noqa: E402

PUBLISHED = json.loads(contract_ir.CANONICAL_SCHEMA.read_text(encoding="utf-8"))


def schema() -> dict[str, Any]:
    """A private copy of the published schema to vary."""
    return copy.deepcopy(PUBLISHED)


def generate(variant: dict[str, Any]) -> dict[str, str]:
    """Every generated file for ``variant``, keyed by emitter and file name."""
    contract = contract_ir.read(variant)
    out = {}
    for target, emit in (("python", emit_python), ("rust", emit_rust), ("csharp", emit_csharp)):
        for path, content in emit.files(contract).items():
            out[f"{target}/{path.name}"] = content
    return out


def code_list(variant: dict[str, Any]) -> list[str]:
    branches = variant["definitions"]["code"]["anyOf"]
    return next(b["enum"] for b in branches if "enum" in b)


class PublishedSchemaTest(unittest.TestCase):
    def test_codes_and_severities_are_open_constants(self) -> None:
        contract = contract_ir.read(schema())

        codes = contract.constants_named("Code")
        severities = contract.constants_named("Severity")
        assert codes is not None and severities is not None
        self.assertIn("SYNTAX_ERROR", codes.members)
        self.assertEqual(("ERROR", "WARN", "INFO"), severities.members)

        annotation = next(k for k in contract.classes if k.name == "Annotation")
        kinds = {f.json_name: f.kind for f in annotation.fields}
        self.assertEqual(Kind.ENUM, kinds["code"])
        self.assertEqual(Kind.ENUM, kinds["severity"])

    def test_a_closed_enum_still_reads_as_constants(self) -> None:
        variant = schema()
        variant["definitions"]["severity"] = {"type": "string", "enum": ["ERROR", "WARN"]}

        severities = contract_ir.read(variant).constants_named("Severity")

        assert severities is not None
        self.assertEqual(("ERROR", "WARN"), severities.members)


class ContractChangesReachEveryModelTest(unittest.TestCase):
    def test_a_new_optional_field_appears_in_all_three_models(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["properties"]["ruleUrl"] = {
            "type": "string",
            "description": "Where the rule is documented.",
        }

        files = generate(variant)

        self.assertIn("rule_url: str | None = None", files["python/_model.py"])
        self.assertIn('data, "ruleUrl", path', files["python/_model.py"])
        self.assertIn("pub rule_url: Option<String>,", files["rust/model.rs"])
        self.assertIn('[JsonPropertyName("ruleUrl")]', files["csharp/Model.g.cs"])
        self.assertIn("public string? RuleUrl", files["csharp/Model.g.cs"])

    def test_a_new_required_field_is_required_in_all_three_models(self) -> None:
        variant = schema()
        annotation = variant["definitions"]["annotation"]
        annotation["properties"]["ruleUrl"] = {"type": "string"}
        annotation["required"].append("ruleUrl")

        files = generate(variant)

        self.assertIn('data, "ruleUrl", path, required=True', files["python/_model.py"])
        self.assertIn("pub rule_url: String,", files["rust/model.rs"])
        self.assertIn("public required string RuleUrl", files["csharp/Model.g.cs"])

    def test_a_new_code_appears_in_all_three_constant_lists(self) -> None:
        variant = schema()
        code_list(variant).append("A_NEW_RULE")

        files = generate(variant)

        self.assertIn('A_NEW_RULE = "A_NEW_RULE"', files["python/_codes.py"])
        self.assertIn('pub const A_NEW_RULE: &\'static str = "A_NEW_RULE";', files["rust/codes.rs"])
        self.assertIn("        Self::A_NEW_RULE,", files["rust/codes.rs"])
        self.assertIn('public const string ANewRule = "A_NEW_RULE";', files["csharp/Codes.g.cs"])
        self.assertIn("        ANewRule,", files["csharp/Codes.g.cs"])

    def test_the_published_schema_regenerates_the_committed_models(self) -> None:
        contract = contract_ir.load()
        for emit in (emit_python, emit_rust, emit_csharp):
            for path, content in emit.files(contract).items():
                with self.subTest(path=path.name):
                    self.assertEqual(path.read_text(encoding="utf-8"), content)


class UnsupportedConstructsFailTest(unittest.TestCase):
    """Each variant here used to generate something plausible and wrong, without a word."""

    def assert_rejected(self, variant: dict[str, Any], where: str) -> None:
        with self.assertRaises(SchemaError) as raised:
            contract_ir.read(variant)
        self.assertIn(where, str(raised.exception))

    def test_a_definition_that_is_neither_an_object_nor_constants(self) -> None:
        variant = schema()
        variant["definitions"]["code"] = {
            "type": "string",
            "oneOf": [{"enum": ["A"]}, {"pattern": "^X_"}],
        }
        self.assert_rejected(variant, "#/definitions/code")

    def test_an_anyof_that_is_not_the_open_list_pattern(self) -> None:
        variant = schema()
        variant["definitions"]["code"]["anyOf"].append({"type": "integer"})
        self.assert_rejected(variant, "#/definitions/code")

    def test_an_unknown_keyword_on_a_property(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["properties"]["line"]["oneOf"] = [{"minimum": 1}]
        self.assert_rejected(variant, "#/definitions/annotation/properties/line")

    def test_an_unsupported_scalar_type(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["properties"]["line"]["type"] = "number"
        self.assert_rejected(variant, "#/definitions/annotation/properties/line")

    def test_a_nullable_type_list(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["properties"]["line"]["type"] = ["integer", "null"]
        self.assert_rejected(variant, "#/definitions/annotation/properties/line")

    def test_an_inline_value_list(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["properties"]["kind"] = {
            "type": "string",
            "enum": ["a", "b"],
        }
        self.assert_rejected(variant, "#/definitions/annotation/properties/kind")

    def test_an_array_of_integers(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["properties"]["lines"] = {
            "type": "array",
            "items": {"type": "integer"},
        }
        self.assert_rejected(variant, "#/definitions/annotation/properties/lines/items")

    def test_a_closed_object(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["additionalProperties"] = False
        self.assert_rejected(variant, "#/definitions/annotation")

    def test_a_map_typed_object(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["additionalProperties"] = {"type": "string"}
        self.assert_rejected(variant, "#/definitions/annotation")

    def test_a_required_name_without_a_property(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["required"].append("mesage")
        self.assert_rejected(variant, "#/definitions/annotation")

    def test_a_reference_outside_definitions(self) -> None:
        variant = schema()
        variant["properties"]["results"]["items"] = {"$ref": "other.json#/definitions/fileResult"}
        self.assert_rejected(variant, "#/properties/results/items")

    def test_a_reference_to_a_missing_definition(self) -> None:
        variant = schema()
        variant["definitions"]["annotation"]["properties"]["code"] = {"$ref": "#/definitions/rule"}
        self.assert_rejected(variant, "#/definitions/annotation/properties/code")

    def test_constants_that_are_not_strings(self) -> None:
        variant = schema()
        variant["definitions"]["severity"] = {"enum": [1, 2, 3]}
        self.assert_rejected(variant, "#/definitions/severity")


if __name__ == "__main__":
    unittest.main()
