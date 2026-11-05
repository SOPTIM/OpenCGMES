/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { applyConfigModel, ConfigModel, parseConfigModel } from "./configModel";

const CONFIG = `{
  // keep me: file header comment
  "cimvocabcheck": {
    // keep me: strictness comment
    "strictness": "default",
    "schemas": ["a.rdf", "b.rdf"]
  },
  "cimnotebook": {
    "queryTimeoutSeconds": 30,
    "connections": [
      { "name": "local", "url": "http://localhost:3030/ds/query", "default": true }
    ]
  }
}
`;

describe("parseConfigModel", () => {
    it("reads both sections leniently", () => {
        const model = parseConfigModel(CONFIG);
        assert.equal(model.strictness, "default");
        assert.deepEqual(model.schemas, ["a.rdf", "b.rdf"]);
        assert.equal(model.queryTimeoutSeconds, 30);
        assert.equal(model.maxRows, undefined);
        assert.deepEqual(model.connections, [
            {
                name: "local",
                url: "http://localhost:3030/ds/query",
                updateUrl: undefined,
                shaclUrl: undefined,
                authType: undefined,
                default: true,
                rawIndex: 0,
            },
        ]);
    });

    it("reads the RDFArchitect model the workspace validates against", () => {
        const model = parseConfigModel('{ "cimvocabcheck": { "rdfArchitect": "cgmes-3.0" } }');
        assert.equal(model.rdfArchitect, "cgmes-3.0");
        assert.equal(parseConfigModel(CONFIG).rdfArchitect, undefined);
    });

    it("tolerates comments, trailing commas, and garbage", () => {
        assert.deepEqual(parseConfigModel('{ /* c */ "cimvocabcheck": { }, }').schemas, undefined);
        assert.equal(parseConfigModel("not json").strictness, undefined);
        assert.equal(parseConfigModel("").strictness, undefined);
    });
});

describe("applyConfigModel", () => {
    it("changes only the edited value and keeps comments elsewhere", () => {
        const model = parseConfigModel(CONFIG);
        model.strictness = "strict";

        const result = applyConfigModel(CONFIG, model);

        assert.ok(result.includes('"strictness": "strict"'));
        assert.ok(result.includes("keep me: file header comment"));
        assert.ok(result.includes("keep me: strictness comment"));
        assert.ok(
            result.includes('"schemas": ["a.rdf", "b.rdf"]'),
            "untouched array kept verbatim",
        );
        assert.deepEqual(parseConfigModel(result).connections, model.connections);
    });

    it("is a no-op for an unchanged model", () => {
        assert.equal(applyConfigModel(CONFIG, parseConfigModel(CONFIG)), CONFIG);
    });

    it("removes a property when the form clears it", () => {
        const model = parseConfigModel(CONFIG);
        model.strictness = "";

        const result = applyConfigModel(CONFIG, model);

        assert.ok(!result.includes('"strictness"'));
        assert.ok(result.includes("keep me: file header comment"));
    });

    it("replaces the connections array and drops empty optional fields", () => {
        const model = parseConfigModel(CONFIG);
        model.connections = [
            {
                name: "prod",
                url: "https://example.org/query",
                updateUrl: "  ",
                authType: "basic",
                default: false,
            },
        ];

        const parsedBack = parseConfigModel(applyConfigModel(CONFIG, model));

        assert.deepEqual(parsedBack.connections, [
            {
                name: "prod",
                url: "https://example.org/query",
                updateUrl: undefined,
                shaclUrl: undefined,
                authType: "basic",
                default: undefined,
                rawIndex: 0,
            },
        ]);
    });

    // A config the sidebar's form model cannot fully represent: comments between and inside
    // entries, a field the form does not know, and a work-in-progress entry without a url.
    const HANDWRITTEN = `{
  "cimnotebook": {
    "connections": [
      // production — ask ops for credentials
      {
        "name": "prod",
        "url": "https://example.org/query",
        "description": "the shared instance"
      },
      { "name": "wip" },
      { "name": "local", "url": "http://localhost:3030/ds/query" }
    ]
  }
}
`;

    it("edits one connection in place, keeping comments, unknown fields, and skipped entries", () => {
        const model = parseConfigModel(HANDWRITTEN);
        const local = model.connections?.find((c) => c.name === "local");
        assert.ok(local);
        local.default = true;

        const result = applyConfigModel(HANDWRITTEN, model);

        assert.ok(result.includes("production — ask ops for credentials"), "comment kept");
        assert.ok(result.includes('"description": "the shared instance"'), "unknown field kept");
        assert.ok(result.includes('{ "name": "wip" }'), "incomplete entry kept verbatim");
        const parsedBack = parseConfigModel(result);
        assert.equal(parsedBack.connections?.find((c) => c.name === "local")?.default, true);
        assert.equal(parsedBack.connections?.find((c) => c.name === "prod")?.default, undefined);
    });

    it("removes exactly the targeted entry by its raw-array position", () => {
        const model = parseConfigModel(HANDWRITTEN);
        model.connections = model.connections?.filter((c) => c.name !== "prod");

        const result = applyConfigModel(HANDWRITTEN, model);

        assert.ok(!result.includes('"prod"'));
        // The neighbor may get reformatted by the removal edit, but it must survive.
        assert.ok(result.includes('"wip"'), "skipped entry survives the removal");
        assert.ok(result.includes('"local"'));
    });

    it("appends a new connection without rewriting existing entries", () => {
        const model = parseConfigModel(HANDWRITTEN);
        model.connections = [
            ...(model.connections ?? []),
            { name: "new", url: "http://h/q", default: true },
        ];

        const result = applyConfigModel(HANDWRITTEN, model);

        assert.ok(result.includes("production — ask ops for credentials"), "comment kept");
        assert.ok(result.includes('{ "name": "wip" }'), "skipped entry kept");
        const parsedBack = parseConfigModel(result);
        assert.deepEqual(
            parsedBack.connections?.map((c) => c.name),
            ["prod", "local", "new"],
        );
        assert.equal(parsedBack.connections?.find((c) => c.name === "new")?.default, true);
    });

    it("builds a full config from empty text", () => {
        const model: ConfigModel = {
            strictness: "strict",
            queryTimeoutSeconds: 10,
            connections: [{ name: "local", url: "http://h/q", default: true }],
        };

        const result = applyConfigModel("", model);
        const parsedBack = parseConfigModel(result);

        assert.equal(parsedBack.strictness, "strict");
        assert.equal(parsedBack.queryTimeoutSeconds, 10);
        assert.equal(parsedBack.connections?.[0].name, "local");
        assert.equal(parsedBack.connections?.[0].default, true);
    });
});
