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
    applyEndpointDirective,
    classifyDirective,
    ConnectionInfo,
    deriveShaclUrl,
    deriveUpdateUrl,
    parseEndpointDirectives,
    resolveCellTarget,
    statusBarText,
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

describe("classifyDirective", () => {
    it("splits URLs, connection names, and file paths like the server does", () => {
        assert.equal(classifyDirective("http://host/ds/query"), "url");
        assert.equal(classifyDirective("HTTPS://host"), "url");
        assert.equal(classifyDirective("local-fuseki"), "name");
        assert.equal(classifyDirective("prod"), "name");
        assert.equal(classifyDirective("./data.ttl"), "file");
        assert.equal(classifyDirective("model.xml"), "file");
        assert.equal(classifyDirective("sub/dir"), "file");
    });

    it("treats glob patterns as files, never connection names", () => {
        assert.equal(classifyDirective("./rdf/*.ttl"), "file");
        assert.equal(classifyDirective("./rdf/{a,b}.ttl"), "file");
        assert.equal(classifyDirective("{a,b}"), "file");
        assert.equal(classifyDirective("data-?"), "file");
        assert.equal(classifyDirective("[ab]"), "file");
    });
});

describe("resolveCellTarget", () => {
    const CONNECTIONS: ConnectionInfo[] = [
        {
            name: "local-fuseki",
            url: "http://localhost:3030/cgmes/query",
            updateUrl: "http://localhost:3030/cgmes/upd",
            authType: "basic",
        },
        { name: "prod", url: "https://sparql.example.org/query", default: true },
    ];

    it("returns none without directives, defaults, or a default connection", () => {
        assert.deepEqual(resolveCellTarget("SELECT * WHERE { ?s ?p ?o }", []), {
            kind: "none",
        });
    });

    it("picks the first http(s) directive and derives the update and shacl siblings", () => {
        const res = resolveCellTarget("# [endpoint=https://host/ds/query]\nASK {}", []);
        assert.deepEqual(res, {
            kind: "target",
            label: "https://host/ds/query",
            target: {
                type: "http",
                url: "https://host/ds/query",
                updateUrl: "https://host/ds/update",
                shaclUrl: "https://host/ds/shacl?graph=default",
            },
        });
    });

    it("collects every file directive into one union target, in order", () => {
        const res = resolveCellTarget(
            "# [endpoint=./model.xml]\n# [endpoint=extra.ttl]\nASK {}",
            [],
        );
        assert.equal(res.kind, "target");
        assert.deepEqual(res.kind === "target" && res.target, {
            type: "files",
            files: ["./model.xml", "extra.ttl"],
        });
    });

    it("passes glob patterns through as file targets for the server to expand", () => {
        const res = resolveCellTarget(
            "# [endpoint=./rdf/{a,b}.ttl]\n# [endpoint=./more/*.ttl]\nASK {}",
            [],
        );
        assert.equal(res.kind, "target");
        assert.deepEqual(res.kind === "target" && res.target, {
            type: "files",
            files: ["./rdf/{a,b}.ttl", "./more/*.ttl"],
        });
    });

    it("reports directives of mixed kinds instead of silently picking one", () => {
        assert.deepEqual(
            resolveCellTarget("# [endpoint=./a.ttl]\n# [endpoint=http://host/sparql]\nASK {}", []),
            {
                kind: "ambiguous-directives",
                directives: ["./a.ttl", "http://host/sparql"],
            },
        );
        assert.deepEqual(
            resolveCellTarget(
                "# [endpoint=./a.ttl]\n# [endpoint=local-fuseki]\nASK {}",
                CONNECTIONS,
            ),
            {
                kind: "ambiguous-directives",
                directives: ["./a.ttl", "local-fuseki"],
            },
        );
    });

    it("reports repeated URL or connection directives — only files union", () => {
        assert.equal(
            resolveCellTarget("# [endpoint=http://a/q]\n# [endpoint=http://b/q]\nASK {}", []).kind,
            "ambiguous-directives",
        );
        assert.equal(
            resolveCellTarget("# [endpoint=local-fuseki]\n# [endpoint=prod]\nASK {}", CONNECTIONS)
                .kind,
            "ambiguous-directives",
        );
    });

    it("resolves a connection name, keeping its explicit URLs and deriving the rest", () => {
        const res = resolveCellTarget("# [endpoint=local-fuseki]\nASK {}", CONNECTIONS);
        assert.ok(res.kind === "target");
        if (res.kind === "target") {
            assert.equal(res.label, "local-fuseki");
            assert.deepEqual(res.target, {
                type: "http",
                url: "http://localhost:3030/cgmes/query",
                updateUrl: "http://localhost:3030/cgmes/upd",
                shaclUrl: "http://localhost:3030/cgmes/shacl?graph=default",
            });
            assert.equal(res.authConnection?.name, "local-fuseki");
        }
    });

    it("reports an unknown connection name with the known ones", () => {
        assert.deepEqual(resolveCellTarget("# [endpoint=nope]\nASK {}", CONNECTIONS), {
            kind: "unknown-connection",
            name: "nope",
            known: ["local-fuseki", "prod"],
        });
    });

    it("falls back to the notebook default directive, then the default connection", () => {
        const viaDefault = resolveCellTarget("ASK {}", CONNECTIONS, "./model.xml");
        assert.ok(viaDefault.kind === "target" && viaDefault.target.type === "files");

        const viaConfig = resolveCellTarget("ASK {}", CONNECTIONS);
        assert.ok(viaConfig.kind === "target");
        if (viaConfig.kind === "target") {
            assert.equal(viaConfig.label, "prod");
            assert.equal(viaConfig.authConnection, undefined);
        }
    });

    it("cell directives beat the notebook default", () => {
        const res = resolveCellTarget(
            "# [endpoint=./cell.ttl]\nASK {}",
            CONNECTIONS,
            "local-fuseki",
        );
        assert.ok(res.kind === "target" && res.target.type === "files");
    });
});

describe("statusBarText", () => {
    it("labels each resolution kind distinctly", () => {
        assert.equal(
            statusBarText({
                kind: "target",
                label: "local-fuseki",
                target: { type: "http", url: "http://h/q" },
            }),
            "$(plug) local-fuseki",
        );
        assert.equal(
            statusBarText({
                kind: "target",
                label: "http://h:3030/ds/query?x=1",
                target: { type: "http", url: "http://h:3030/ds/query?x=1" },
            }),
            "$(globe) h:3030/ds/query",
        );
        assert.equal(
            statusBarText({
                kind: "target",
                label: "./a/model.xml, b.ttl",
                target: { type: "files", files: ["./a/model.xml", "b.ttl"] },
            }),
            "$(file) model.xml (+1)",
        );
        assert.equal(
            statusBarText({ kind: "unknown-connection", name: "x", known: [] }),
            "$(warning) unknown connection: x",
        );
        assert.equal(
            statusBarText({ kind: "ambiguous-directives", directives: ["a.ttl", "http://h/q"] }),
            "$(warning) conflicting endpoint directives",
        );
        assert.equal(statusBarText({ kind: "none" }), "$(warning) no endpoint");
    });
});

describe("applyEndpointDirective", () => {
    it("inserts a directive as the first line when none exists", () => {
        assert.equal(
            applyEndpointDirective("ASK {}", "local-fuseki"),
            "# [endpoint=local-fuseki]\nASK {}",
        );
    });

    it("replaces the first directive and drops any further ones", () => {
        const text = "# [endpoint=./a.ttl]\nASK {}\n# [endpoint=./b.ttl]";
        assert.equal(applyEndpointDirective(text, "http://h/q"), "# [endpoint=http://h/q]\nASK {}");
    });

    it("removes all directive lines when clearing", () => {
        const text = "# [endpoint=./a.ttl]\nASK {}\n# [endpoint=./b.ttl]";
        assert.equal(applyEndpointDirective(text, null), "ASK {}");
    });

    it("leaves ordinary comments alone", () => {
        const text = "# just a comment\nASK {}";
        assert.equal(applyEndpointDirective(text, null), text);
    });

    it("writes one directive line per array entry, in order", () => {
        assert.equal(
            applyEndpointDirective("ASK {}", ["a.ttl", "b.ttl"]),
            "# [endpoint=a.ttl]\n# [endpoint=b.ttl]\nASK {}",
        );
    });

    it("replaces every existing directive with the new array at the first one's position", () => {
        const text = "# [endpoint=./a.ttl]\nASK {}\n# [endpoint=./b.ttl]";
        assert.equal(
            applyEndpointDirective(text, ["x.ttl", "y.ttl", "z.ttl"]),
            "# [endpoint=x.ttl]\n# [endpoint=y.ttl]\n# [endpoint=z.ttl]\nASK {}",
        );
    });

    it("treats an empty array like clearing", () => {
        const text = "# [endpoint=./a.ttl]\nASK {}";
        assert.equal(applyEndpointDirective(text, []), "ASK {}");
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
