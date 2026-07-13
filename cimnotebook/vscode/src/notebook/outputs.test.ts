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
    askResultToMarkdown,
    DISPLAY_ROW_CAP,
    errorSummary,
    MIME_MARKDOWN,
    outputItemsFor,
    selectResultsToMarkdown,
    shaclReportToMarkdown,
    turtleToMarkdown,
    updateResultToMarkdown,
} from "./outputs";

function selectJson(bindings: Record<string, unknown>[], vars = ["s"]): string {
    return JSON.stringify({ head: { vars }, results: { bindings } });
}

describe("selectResultsToMarkdown", () => {
    it("renders a header and one row per binding", () => {
        const md = selectResultsToMarkdown(
            selectJson(
                [
                    { s: { type: "uri", value: "http://x/a" } },
                    { s: { type: "literal", value: "plain" } },
                ],
                ["s"],
            ),
        );
        assert.ok(md.startsWith("| s |\n| --- |\n| http://x/a |\n| plain |"));
    });

    it("renders lang and typed literals in Turtle-ish form, bnodes with _:", () => {
        const md = selectResultsToMarkdown(
            selectJson(
                [
                    {
                        a: { type: "literal", value: "hi", "xml:lang": "en" },
                        b: {
                            type: "literal",
                            value: "4",
                            datatype: "http://www.w3.org/2001/XMLSchema#int",
                        },
                        c: { type: "bnode", value: "b0" },
                    },
                ],
                ["a", "b", "c"],
            ),
        );
        assert.ok(md.includes('"hi"@en'));
        assert.ok(md.includes('"4"^^&lt;http://www.w3.org/2001/XMLSchema#int&gt;'));
        assert.ok(md.includes("_:b0"));
    });

    it("leaves unbound variables empty and escapes pipes/newlines", () => {
        const md = selectResultsToMarkdown(
            selectJson([{ a: { type: "literal", value: "x|y\nz" } }], ["a", "b"]),
        );
        assert.ok(md.includes("| x\\|y<br>z |  |"));
    });

    it("escapes HTML in cell values so result data is never parsed as markup", () => {
        const md = selectResultsToMarkdown(
            selectJson([{ a: { type: "literal", value: "use <b> & stay a<b" } }], ["a"]),
        );
        assert.ok(md.includes("| use &lt;b&gt; &amp; stay a&lt;b |"));
        assert.ok(!md.includes("<b>"));
    });

    it("caps the table at DISPLAY_ROW_CAP rows with a note", () => {
        const rows = Array.from({ length: DISPLAY_ROW_CAP + 5 }, (_, i) => ({
            s: { type: "literal", value: `row${i}` },
        }));
        const md = selectResultsToMarkdown(selectJson(rows));
        assert.ok(md.includes(`first ${DISPLAY_ROW_CAP} of ${DISPLAY_ROW_CAP + 5} fetched rows`));
        assert.ok(!md.includes(`row${DISPLAY_ROW_CAP}`));
    });

    it("shows an empty-result message and the stats footer", () => {
        const md = selectResultsToMarkdown(selectJson([]), {
            durationMs: 12,
            rowCount: 0,
            truncated: false,
            resolvedTarget: "http://h/ds/query",
        });
        assert.ok(md.startsWith("*No results.*"));
        assert.ok(md.includes("0 results · 12 ms · http://h/ds/query"));
    });

    it("marks server-side truncation in the footer", () => {
        const md = selectResultsToMarkdown(selectJson([{ s: { type: "literal", value: "x" } }]), {
            durationMs: 3,
            rowCount: 10000,
            truncated: true,
            resolvedTarget: null,
        });
        assert.ok(md.includes("10000+ results (server cap reached)"));
    });
});

describe("askResultToMarkdown", () => {
    it("renders true and false verdicts", () => {
        assert.ok(askResultToMarkdown('{"head":{},"boolean":true}').includes("**true**"));
        assert.ok(askResultToMarkdown('{"head":{},"boolean":false}').includes("**false**"));
    });
});

describe("turtleToMarkdown", () => {
    it("fences the turtle payload", () => {
        assert.equal(turtleToMarkdown("<a> <b> <c> .\n"), "```turtle\n<a> <b> <c> .\n```");
    });

    it("shows a message for an empty graph", () => {
        assert.ok(turtleToMarkdown("   \n").startsWith("*Empty graph.*"));
    });
});

describe("shaclReportToMarkdown", () => {
    const report = "[] a sh:ValidationReport ; sh:conforms true .";

    it("renders a conforming verdict with the fenced report", () => {
        const md = shaclReportToMarkdown(
            report,
            { conforms: true, violations: 0, warnings: 0, infos: 0 },
            { durationMs: 12, truncated: false, resolvedTarget: "./model.xml" },
        );
        assert.ok(md.startsWith("✅ **Conforms**"));
        assert.ok(md.includes("```turtle\n" + report + "\n```"));
        assert.ok(md.includes("12 ms"));
    });

    it("counts violations and mentions warnings/infos only when present", () => {
        const md = shaclReportToMarkdown("report", {
            conforms: false,
            violations: 2,
            warnings: 1,
            infos: 0,
        });
        assert.ok(md.startsWith("❌ **Does not conform** — 2 violations, 1 warning."));
        assert.ok(!md.includes("info"));
    });

    it("copes with a missing summary and an empty report", () => {
        const md = shaclReportToMarkdown("", null);
        assert.equal(md, "**Validation finished.**");
    });
});

describe("updateResultToMarkdown", () => {
    it("confirms the update with stats", () => {
        const md = updateResultToMarkdown({ durationMs: 8, truncated: false });
        assert.ok(md.startsWith("✅ **Update executed.**"));
        assert.ok(md.includes("8 ms"));
    });
});

describe("error rendering", () => {
    it("summarizes with code and parse position", () => {
        assert.equal(
            errorSummary({ code: "PARSE_ERROR", message: "bad token", line: 3, column: 7 }),
            "PARSE_ERROR: bad token (line 3, column 7)",
        );
        assert.equal(errorSummary({ code: "HTTP_ERROR", message: "boom" }), "HTTP_ERROR: boom");
    });
});

describe("outputItemsFor", () => {
    // The items of one cell output are alternatives — VS Code renders the richest MIME type by
    // its display order, which ranks application/json ABOVE text/markdown. An application/json
    // item therefore hides the formatted result, whatever order the items are given in.
    it("never offers application/json, which would outrank the markdown rendering", () => {
        const responses = [
            { status: "SUCCESS", queryKind: "SELECT", resultsJson: selectJson([]) },
            { status: "SUCCESS", queryKind: "ASK", resultsJson: '{"boolean":true}' },
            { status: "SUCCESS", queryKind: "CONSTRUCT", turtle: "<a> <b> <c> ." },
            { status: "SUCCESS", queryKind: "UPDATE" },
            {
                status: "SUCCESS",
                queryKind: "SHACL",
                turtle: "",
                shaclSummary: { conforms: true, violations: 0, warnings: 0, infos: 0 },
            },
        ] as const;

        for (const response of responses) {
            const items = outputItemsFor(response);
            assert.equal(items[0].mime, MIME_MARKDOWN, `${response.queryKind}: markdown first`);
            assert.ok(
                !items.some((i) => i.mime === "application/json"),
                `${response.queryKind} must not offer application/json`,
            );
        }
    });

    it("carries the raw SELECT results alongside the table", () => {
        const resultsJson = selectJson([{ s: { type: "uri", value: "http://x" } }]);
        const items = outputItemsFor({ status: "SUCCESS", queryKind: "SELECT", resultsJson });

        assert.deepEqual(
            items.map((i) => i.mime),
            [MIME_MARKDOWN, "application/sparql-results+json", "text/plain"],
        );
        assert.ok(items[0].text.includes("| http://x |"));
        assert.equal(items[1].text, resultsJson);
    });

    it("has no items for an unknown result kind", () => {
        assert.deepEqual(outputItemsFor({ status: "SUCCESS" }), []);
    });
});
