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

import { deriveUpdateUrl, parseEndpointDirectives, resolveTarget } from "./endpoint";

describe("parseEndpointDirectives", () => {
    it("finds every directive, in order, with flexible spacing", () => {
        const text = [
            "# [endpoint=http://localhost:3030/ds/query]",
            "#[endpoint=./local.ttl]",
            "  #  [ endpoint = http://other/sparql ]",
            "SELECT * WHERE { ?s ?p ?o }",
        ].join("\n");
        assert.deepEqual(parseEndpointDirectives(text), [
            "http://localhost:3030/ds/query",
            "./local.ttl",
            "http://other/sparql",
        ]);
    });

    it("ignores non-directive comments and directive-like text mid-line", () => {
        assert.deepEqual(
            parseEndpointDirectives("# endpoint=http://x\nSELECT 1 # [endpoint=http://y]"),
            [],
        );
    });
});

describe("resolveTarget", () => {
    it("returns none without a directive", () => {
        assert.deepEqual(resolveTarget("SELECT * WHERE { ?s ?p ?o }"), { type: "none" });
    });

    it("picks the first http(s) directive and derives the update sibling", () => {
        const target = resolveTarget("# [endpoint=https://host/ds/query]\nASK {}");
        assert.deepEqual(target, {
            type: "http",
            url: "https://host/ds/query",
            updateUrl: "https://host/ds/update",
        });
    });

    it("reports non-URL directives as unsupported (files come later)", () => {
        assert.deepEqual(resolveTarget("# [endpoint=./data.ttl]\nASK {}"), {
            type: "unsupported",
            directive: "./data.ttl",
        });
    });

    it("skips file directives when an http one is also present", () => {
        const target = resolveTarget(
            "# [endpoint=./a.ttl]\n# [endpoint=http://host/sparql]\nASK {}",
        );
        assert.equal(target.type, "http");
    });
});

describe("deriveUpdateUrl", () => {
    it("maps Fuseki-style query/sparql services to the update sibling", () => {
        assert.equal(deriveUpdateUrl("http://h:3030/ds/query"), "http://h:3030/ds/update");
        assert.equal(deriveUpdateUrl("http://h/ds/sparql"), "http://h/ds/update");
        assert.equal(deriveUpdateUrl("http://h/ds/QUERY"), "http://h/ds/update");
    });

    it("drops the query string when deriving the sibling", () => {
        assert.equal(deriveUpdateUrl("http://h/ds/query?graph=urn:x"), "http://h/ds/update");
    });

    it("keeps other URLs unchanged (updates POST to the same endpoint)", () => {
        assert.equal(deriveUpdateUrl("http://h/ds"), "http://h/ds");
        assert.equal(deriveUpdateUrl("http://h/api/sparql-proxy"), "http://h/api/sparql-proxy");
    });
});
