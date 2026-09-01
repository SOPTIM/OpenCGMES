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
 * VS Code adapters around the pure notebook format modules. The three contributed
 * notebook types (see package.json `contributes.notebooks`) share these serializers:
 *
 * - `cimnotebook`            — `*.cimnb.md`, opens as a notebook by default
 * - `cimnotebook-markdown`   — any `*.md`/`*.markdown` via "Open With…"
 * - `cimnotebook-sparqlbook` — `*.sparqlbook` (Zazuko interop) via "Open With…"
 *
 * Outputs are always transient: notebook files on disk stay pure source.
 */

import * as vscode from "vscode";
import { TextDecoder, TextEncoder } from "util";

import { RawCell, RawNotebook } from "./cells";
import { parseMarkdownNotebook, serializeMarkdownNotebook } from "./markdown";
import { parseSparqlBook, serializeSparqlBook } from "./sparqlbook";

export const NOTEBOOK_TYPE_DEFAULT = "cimnotebook";
export const NOTEBOOK_TYPE_MARKDOWN = "cimnotebook-markdown";
export const NOTEBOOK_TYPE_SPARQLBOOK = "cimnotebook-sparqlbook";

/** All notebook types owned by this extension. */
export const NOTEBOOK_TYPES = [
    NOTEBOOK_TYPE_DEFAULT,
    NOTEBOOK_TYPE_MARKDOWN,
    NOTEBOOK_TYPE_SPARQLBOOK,
] as const;

/** Notebook-level metadata key carrying the source file's line ending. */
const METADATA_EOL = "cimnotebookEol";

export function registerNotebookSerializers(context: vscode.ExtensionContext): void {
    const markdown = new FormatSerializer(parseMarkdownNotebook, serializeMarkdownNotebook);
    const sparqlbook = new FormatSerializer(parseSparqlBook, serializeSparqlBook);
    const options: vscode.NotebookDocumentContentOptions = { transientOutputs: true };

    context.subscriptions.push(
        vscode.workspace.registerNotebookSerializer(NOTEBOOK_TYPE_DEFAULT, markdown, options),
        vscode.workspace.registerNotebookSerializer(NOTEBOOK_TYPE_MARKDOWN, markdown, options),
        vscode.workspace.registerNotebookSerializer(NOTEBOOK_TYPE_SPARQLBOOK, sparqlbook, options),
    );
}

/** Snapshot of an open notebook document as raw cells, for the convert command. */
export function notebookDocumentToRaw(notebook: vscode.NotebookDocument): RawNotebook {
    return {
        cells: notebook.getCells().map((cell) => {
            const data = new vscode.NotebookCellData(
                cell.kind,
                cell.document.getText(),
                cell.document.languageId,
            );
            return cellDataToRaw(data, cell.metadata as Record<string, unknown>);
        }),
        eol: readEol(notebook.metadata),
    };
}

class FormatSerializer implements vscode.NotebookSerializer {
    constructor(
        private readonly parse: (text: string) => RawNotebook,
        private readonly serialize: (notebook: RawNotebook) => string,
    ) {}

    deserializeNotebook(content: Uint8Array): vscode.NotebookData {
        const raw = this.parse(new TextDecoder().decode(content));
        const data = new vscode.NotebookData(raw.cells.map(rawToCellData));
        data.metadata = { [METADATA_EOL]: raw.eol };
        return data;
    }

    serializeNotebook(data: vscode.NotebookData): Uint8Array {
        const raw: RawNotebook = {
            cells: data.cells.map((cell) => cellDataToRaw(cell, cell.metadata)),
            eol: readEol(data.metadata),
        };
        return new TextEncoder().encode(this.serialize(raw));
    }
}

function rawToCellData(cell: RawCell): vscode.NotebookCellData {
    const data = new vscode.NotebookCellData(
        cell.kind === "markdown" ? vscode.NotebookCellKind.Markup : vscode.NotebookCellKind.Code,
        cell.value,
        cell.kind === "markdown" ? "markdown" : (cell.language ?? "sparql"),
    );
    if (cell.metadata !== undefined) {
        data.metadata = cell.metadata;
    }
    return data;
}

function cellDataToRaw(
    cell: vscode.NotebookCellData,
    metadata: Record<string, unknown> | undefined,
): RawCell {
    const isMarkdown = cell.kind === vscode.NotebookCellKind.Markup;
    return {
        kind: isMarkdown ? "markdown" : "code",
        ...(isMarkdown ? {} : { language: cell.languageId }),
        value: cell.value,
        ...(metadata !== undefined && Object.keys(metadata).length > 0 ? { metadata } : {}),
    };
}

function readEol(metadata: { [key: string]: unknown } | undefined): RawNotebook["eol"] {
    return metadata?.[METADATA_EOL] === "\r\n" ? "\r\n" : "\n";
}
