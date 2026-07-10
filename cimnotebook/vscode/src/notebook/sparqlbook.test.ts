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

import { parseSparqlBook, serializeSparqlBook } from "./sparqlbook";

describe("parseSparqlBook", () => {
    it("maps kind ordinals to markdown/code cells", () => {
        const text = JSON.stringify([
            { kind: 1, language: "markdown", value: "# Title" },
            { kind: 2, language: "sparql", value: "ASK {}" },
        ]);
        const { cells } = parseSparqlBook(text);
        assert.deepEqual(cells, [
            { kind: "markdown", value: "# Title", metadata: undefined },
            { kind: "code", language: "sparql", value: "ASK {}", metadata: undefined },
        ]);
    });

    it("passes per-cell metadata through (Zazuko external-file cells)", () => {
        const text = JSON.stringify([
            { kind: 2, language: "sparql", value: "SELECT 1", metadata: { file: "./q.sparql" } },
        ]);
        const { cells } = parseSparqlBook(text);
        assert.deepEqual(cells[0].metadata, { file: "./q.sparql" });
    });

    it("accepts an empty file as an empty notebook", () => {
        assert.deepEqual(parseSparqlBook("").cells, []);
        assert.deepEqual(parseSparqlBook("[]").cells, []);
    });

    it("rejects non-array JSON with a clear message", () => {
        assert.throws(() => parseSparqlBook("{}"), /expected a JSON array/);
        assert.throws(() => parseSparqlBook("not json"), /JSON parse failed/);
    });

    it("defaults missing language on code cells to sparql", () => {
        const { cells } = parseSparqlBook(JSON.stringify([{ kind: 2, value: "ASK {}" }]));
        assert.equal(cells[0].language, "sparql");
    });
});

describe("serializeSparqlBook", () => {
    it("writes the Zazuko JSON layout, pretty-printed", () => {
        const text = serializeSparqlBook({
            eol: "\n",
            cells: [
                { kind: "markdown", value: "# Title" },
                { kind: "code", language: "shacl", value: "ex:S a sh:NodeShape ." },
            ],
        });
        const parsed = JSON.parse(text);
        assert.deepEqual(parsed, [
            { kind: 1, language: "markdown", value: "# Title" },
            { kind: 2, language: "shacl", value: "ex:S a sh:NodeShape ." },
        ]);
        assert.ok(text.includes("\n  "), "must be pretty-printed for reviewable diffs");
        assert.ok(text.endsWith("\n"));
    });

    it("round-trips cells and metadata", () => {
        const original = JSON.stringify(
            [
                { kind: 1, language: "markdown", value: "intro" },
                { kind: 2, language: "sparql", value: "SELECT 1", metadata: { file: "a.rq" } },
            ],
            null,
            2,
        );
        const roundTripped = serializeSparqlBook(parseSparqlBook(original));
        assert.deepEqual(JSON.parse(roundTripped), JSON.parse(original));
    });
});
