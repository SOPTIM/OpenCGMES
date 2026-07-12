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

import {
    deriveShaclUrl,
    deriveUpdateUrl,
    parseEndpointDirectives,
    resolveTarget,
} from "./endpoint";

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

    it("picks the first http(s) directive and derives the update and shacl siblings", () => {
        const target = resolveTarget("# [endpoint=https://host/ds/query]\nASK {}");
        assert.deepEqual(target, {
            type: "http",
            url: "https://host/ds/query",
            updateUrl: "https://host/ds/update",
            shaclUrl: "https://host/ds/shacl?graph=default",
        });
    });

    it("resolves a non-URL directive to a local-files target", () => {
        assert.deepEqual(resolveTarget("# [endpoint=./data.ttl]\nASK {}"), {
            type: "files",
            files: ["./data.ttl"],
        });
    });

    it("collects every file directive into one union target, in order", () => {
        assert.deepEqual(
            resolveTarget("# [endpoint=./model.xml]\n# [endpoint=extra.ttl]\nASK {}"),
            {
                type: "files",
                files: ["./model.xml", "extra.ttl"],
            },
        );
    });

    it("prefers the http directive when files are also present", () => {
        const target = resolveTarget(
            "# [endpoint=./a.ttl]\n# [endpoint=http://host/sparql]\nASK {}",
        );
        assert.equal(target.type, "http");
    });
});

describe("deriveShaclUrl", () => {
    it("maps Fuseki-style query/sparql services to the shacl sibling with a default graph", () => {
        assert.equal(
            deriveShaclUrl("http://h:3030/ds/query"),
            "http://h:3030/ds/shacl?graph=default",
        );
        assert.equal(deriveShaclUrl("http://h/ds/sparql"), "http://h/ds/shacl?graph=default");
    });

    it("appends /shacl to dataset-style URLs", () => {
        assert.equal(deriveShaclUrl("http://h/ds"), "http://h/ds/shacl?graph=default");
        assert.equal(deriveShaclUrl("http://h/ds/"), "http://h/ds/shacl?graph=default");
    });

    it("keeps an explicit shacl service and preserves an existing query string", () => {
        assert.equal(
            deriveShaclUrl("http://h/ds/shacl?graph=urn:x"),
            "http://h/ds/shacl?graph=urn:x",
        );
        assert.equal(
            deriveShaclUrl("http://h/ds/query?graph=urn:x"),
            "http://h/ds/shacl?graph=urn:x",
        );
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
