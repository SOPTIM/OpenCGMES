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

/**
 * `.sparqlbook` interop format: the JSON layout used by the Zazuko "SPARQL Notebook"
 * extension — a pretty-printed array of `{ kind, language, value, metadata }` where
 * `kind` is the VS Code NotebookCellKind ordinal (1 = markup, 2 = code). Per-cell
 * metadata is passed through untouched so foreign notebooks survive a round trip.
 */

import { RawCell, RawNotebook } from "./cells";

/** NotebookCellKind ordinals used on disk (mirror vscode.NotebookCellKind). */
const KIND_MARKUP = 1;
const KIND_CODE = 2;

interface SparqlBookCell {
    kind: number;
    language: string;
    value: string;
    metadata?: Record<string, unknown>;
}

export function parseSparqlBook(text: string): RawNotebook {
    let raw: unknown;
    try {
        raw = JSON.parse(text.length > 0 ? text : "[]");
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        throw new Error(`Not a valid .sparqlbook file (JSON parse failed: ${msg})`, {
            cause: err,
        });
    }
    if (!Array.isArray(raw)) {
        throw new Error("Not a valid .sparqlbook file (expected a JSON array of cells)");
    }

    const cells: RawCell[] = raw.map((entry, index) => {
        if (typeof entry !== "object" || entry === null) {
            throw new Error(`Not a valid .sparqlbook file (cell ${index} is not an object)`);
        }
        const cell = entry as Partial<SparqlBookCell>;
        const value = typeof cell.value === "string" ? cell.value : "";
        if (cell.kind === KIND_MARKUP) {
            return { kind: "markdown", value, metadata: cell.metadata };
        }
        return {
            kind: "code",
            language: typeof cell.language === "string" ? cell.language : "sparql",
            value,
            metadata: cell.metadata,
        };
    });

    return { cells, eol: "\n" };
}

export function serializeSparqlBook(notebook: RawNotebook): string {
    const cells: SparqlBookCell[] = notebook.cells.map((cell) => ({
        kind: cell.kind === "markdown" ? KIND_MARKUP : KIND_CODE,
        language: cell.kind === "markdown" ? "markdown" : (cell.language ?? "sparql"),
        value: cell.value,
        ...(cell.metadata !== undefined ? { metadata: cell.metadata } : {}),
    }));
    return JSON.stringify(cells, null, 2) + "\n";
}
