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

import { parseMarkdownNotebook, serializeMarkdownNotebook } from "./markdown";

/** Asserts that serialize(parse(text)) is a fixed point after one normalization pass. */
function assertRoundTripStable(text: string): string {
    const once = serializeMarkdownNotebook(parseMarkdownNotebook(text));
    const twice = serializeMarkdownNotebook(parseMarkdownNotebook(once));
    assert.equal(twice, once, "serialize(parse(x)) must be idempotent");
    return once;
}

describe("parseMarkdownNotebook", () => {
    it("turns sparql and shacl fences into code cells, keeps prose as markdown", () => {
        const text = [
            "# Title",
            "",
            "Some prose.",
            "",
            "```sparql",
            "SELECT * WHERE { ?s ?p ?o }",
            "```",
            "",
            "More prose.",
            "",
            "```shacl",
            "ex:Shape a sh:NodeShape .",
            "```",
            "",
        ].join("\n");

        const { cells } = parseMarkdownNotebook(text);
        assert.deepEqual(
            cells.map((c) => [c.kind, c.language]),
            [
                ["markdown", undefined],
                ["code", "sparql"],
                ["markdown", undefined],
                ["code", "shacl"],
            ],
        );
        assert.equal(cells[0].value, "# Title\n\nSome prose.");
        assert.equal(cells[1].value, "SELECT * WHERE { ?s ?p ?o }");
        assert.equal(cells[3].value, "ex:Shape a sh:NodeShape .");
    });

    it("keeps other fence languages as markdown, verbatim", () => {
        const text = "```turtle\nex:a ex:b ex:c .\n```\n";
        const { cells } = parseMarkdownNotebook(text);
        assert.equal(cells.length, 1);
        assert.equal(cells[0].kind, "markdown");
        assert.equal(cells[0].value, "```turtle\nex:a ex:b ex:c .\n```");
    });

    it("treats the info string case-insensitively", () => {
        const { cells } = parseMarkdownNotebook("```SPARQL\nASK {}\n```\n");
        assert.equal(cells[0].kind, "code");
        assert.equal(cells[0].language, "sparql");
    });

    it("does not open code cells inside longer fences", () => {
        const text = ["````markdown", "```sparql", "SELECT 1", "```", "````", ""].join("\n");
        const { cells } = parseMarkdownNotebook(text);
        assert.equal(cells.length, 1);
        assert.equal(cells[0].kind, "markdown");
    });

    it("does not open code cells inside tilde fences", () => {
        const text = "~~~\n```sparql\nSELECT 1\n```\n~~~\n";
        const { cells } = parseMarkdownNotebook(text);
        assert.equal(cells.length, 1);
        assert.equal(cells[0].kind, "markdown");
    });

    it("keeps indented sparql fences as markdown", () => {
        const text = "  ```sparql\n  SELECT 1\n  ```\n";
        const { cells } = parseMarkdownNotebook(text);
        assert.equal(cells.length, 1);
        assert.equal(cells[0].kind, "markdown");
    });

    it("keeps an unclosed sparql fence at EOF as markdown", () => {
        const text = "prose\n\n```sparql\nSELECT * WHERE { ?s ?p ?o }";
        const { cells } = parseMarkdownNotebook(text);
        assert.deepEqual(
            cells.map((c) => c.kind),
            ["markdown", "markdown"],
        );
        assert.equal(cells[1].value, "```sparql\nSELECT * WHERE { ?s ?p ?o }");
        assertRoundTripStable(text);
    });

    it("supports empty code cells", () => {
        const { cells } = parseMarkdownNotebook("```sparql\n```\n");
        assert.deepEqual(cells, [{ kind: "code", language: "sparql", value: "" }]);
    });

    it("allows closing fences longer than three backticks", () => {
        const { cells } = parseMarkdownNotebook("```sparql\nASK {}\n`````\nafter\n");
        assert.equal(cells[0].kind, "code");
        assert.equal(cells[0].value, "ASK {}");
        assert.equal(cells[1].value, "after");
    });

    it("ignores fence-like inline code (backtick info strings)", () => {
        const text = "``` ```code``` ```\n";
        const { cells } = parseMarkdownNotebook(text);
        assert.equal(cells.length, 1);
        assert.equal(cells[0].kind, "markdown");
    });

    it("detects CRLF line endings", () => {
        const { eol, cells } = parseMarkdownNotebook("# Hi\r\n\r\n```sparql\r\nASK {}\r\n```\r\n");
        assert.equal(eol, "\r\n");
        assert.equal(cells[1].value, "ASK {}");
    });

    it("returns no cells for empty input", () => {
        assert.deepEqual(parseMarkdownNotebook("").cells, []);
        assert.deepEqual(parseMarkdownNotebook("\n\n\n").cells, []);
    });
});

describe("serializeMarkdownNotebook", () => {
    it("separates cells with exactly one blank line and ends with a newline", () => {
        const text = serializeMarkdownNotebook({
            eol: "\n",
            cells: [
                { kind: "markdown", value: "# Title" },
                { kind: "code", language: "sparql", value: "ASK {}" },
            ],
        });
        assert.equal(text, "# Title\n\n```sparql\nASK {}\n```\n");
    });

    it("restores CRLF line endings", () => {
        const text = serializeMarkdownNotebook({
            eol: "\r\n",
            cells: [{ kind: "code", language: "sparql", value: "ASK {}" }],
        });
        assert.equal(text, "```sparql\r\nASK {}\r\n```\r\n");
    });

    it("defaults code cells without a language to sparql", () => {
        const text = serializeMarkdownNotebook({
            eol: "\n",
            cells: [{ kind: "code", value: "ASK {}" }],
        });
        assert.ok(text.startsWith("```sparql\n"));
    });

    it("drops empty markdown cells", () => {
        const text = serializeMarkdownNotebook({
            eol: "\n",
            cells: [
                { kind: "markdown", value: "   \n  " },
                { kind: "code", language: "sparql", value: "ASK {}" },
            ],
        });
        assert.equal(text, "```sparql\nASK {}\n```\n");
    });
});

describe("round trip", () => {
    it("is byte-stable for an already-normalized document", () => {
        const text = [
            "# CGMES exploration",
            "",
            "Query the model:",
            "",
            "```sparql",
            "# [endpoint=http://localhost:3030/ds/query]",
            "SELECT * WHERE { ?s ?p ?o } LIMIT 10",
            "```",
            "",
            "```shacl",
            "ex:Shape a sh:NodeShape ;",
            "  sh:targetClass cim:Switch .",
            "```",
            "",
        ].join("\n");
        assert.equal(serializeMarkdownNotebook(parseMarkdownNotebook(text)), text);
    });

    it("is idempotent for messy input (extra blank lines, trailing spaces)", () => {
        const messy = "# Title\n\n\n\nprose   \n\n\n```sparql\n\nASK {}\n\n```\n\n\n";
        const normalized = assertRoundTripStable(messy);
        const { cells } = parseMarkdownNotebook(normalized);
        assert.deepEqual(
            cells.map((c) => c.kind),
            ["markdown", "code"],
        );
    });

    it("is idempotent for CRLF documents and preserves CRLF", () => {
        const crlf = "# Hi\r\n\r\n```sparql\r\nASK {}\r\n```\r\n";
        assert.equal(serializeMarkdownNotebook(parseMarkdownNotebook(crlf)), crlf);
    });

    it("is idempotent for documents that are all markdown", () => {
        const text = "# Only prose\n\nNothing to run here.\n";
        assert.equal(serializeMarkdownNotebook(parseMarkdownNotebook(text)), text);
    });
});
