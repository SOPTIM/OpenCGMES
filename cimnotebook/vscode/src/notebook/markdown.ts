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
 * Markdown notebook format: a plain markdown document whose top-level ```sparql and
 * ```shacl fenced code blocks are executable cells; everything else stays markdown.
 *
 * Design rules (git-friendliness first):
 * - Only unindented, exactly-three-backtick fences with the info string `sparql` or
 *   `shacl` become code cells. Longer/indented/`~~~` fences and other languages stay
 *   markdown verbatim, but are still tracked so fence markers inside them never open
 *   a code cell. When in doubt, content stays markdown.
 * - Serialization is normalizing but idempotent: cells are separated by exactly one
 *   blank line, trailing whitespace-only lines are dropped, and the file ends with a
 *   single newline. `serialize(parse(x))` reaches a fixed point after one pass.
 * - The source file's line ending (LF/CRLF) is detected on parse and restored on
 *   serialization, so saving never rewrites every line of a CRLF file.
 */

import { CODE_CELL_LANGUAGES, RawCell, RawNotebook } from "./cells";

/** Opening fence: 3+ backticks or tildes, up to 3 leading spaces (CommonMark). */
const FENCE_OPEN = /^( {0,3})(`{3,}|~{3,})(.*)$/;
/** Closing fence for a code cell: 3+ backticks on their own line, no indentation. */
const CODE_CELL_CLOSE = /^`{3,}\s*$/;

export function parseMarkdownNotebook(text: string): RawNotebook {
    const eol: RawNotebook["eol"] = text.includes("\r\n") ? "\r\n" : "\n";
    const lines = text.split(/\r?\n/);

    const cells: RawCell[] = [];
    let markdown: string[] = [];
    let code: string[] = [];

    type State =
        | { name: "outside" }
        | { name: "codeCell"; language: string }
        | { name: "otherFence"; marker: string; length: number };
    let state: State = { name: "outside" };

    const flushMarkdown = (): void => {
        const value = trimBlankEdges(markdown).join("\n");
        if (value.length > 0) {
            cells.push({ kind: "markdown", value });
        }
        markdown = [];
    };

    for (const line of lines) {
        if (state.name === "codeCell") {
            if (CODE_CELL_CLOSE.test(line)) {
                cells.push({ kind: "code", language: state.language, value: code.join("\n") });
                code = [];
                state = { name: "outside" };
            } else {
                code.push(line);
            }
            continue;
        }

        if (state.name === "otherFence") {
            markdown.push(line);
            // CommonMark closer: same marker char, at least as long, nothing else on the line.
            const close = new RegExp(`^ {0,3}\\${state.marker}{${state.length},}\\s*$`);
            if (close.test(line)) {
                state = { name: "outside" };
            }
            continue;
        }

        const fence = FENCE_OPEN.exec(line);
        if (fence) {
            const [, indent, marker, rest] = fence;
            const info = rest.trim().toLowerCase();
            const isCodeCellFence =
                indent.length === 0 &&
                marker === "```" &&
                (CODE_CELL_LANGUAGES as readonly string[]).includes(info);
            if (isCodeCellFence) {
                flushMarkdown();
                state = { name: "codeCell", language: info };
            } else if (marker[0] === "`" && rest.includes("`")) {
                // Not a valid fence opener (info strings of backtick fences must not
                // contain backticks, e.g. inline code like ```` ```code``` ````).
                markdown.push(line);
            } else {
                markdown.push(line);
                state = { name: "otherFence", marker: marker[0], length: marker.length };
            }
            continue;
        }

        markdown.push(line);
    }

    if (state.name === "codeCell") {
        // Unclosed cell fence at EOF: keep the original text as markdown so a later
        // save cannot silently invent a closing fence the author never wrote.
        markdown = ["```" + state.language, ...code];
    }
    flushMarkdown();

    return { cells, eol };
}

export function serializeMarkdownNotebook(notebook: RawNotebook): string {
    const blocks = notebook.cells.map((cell) => {
        if (cell.kind === "code") {
            const language = cell.language ?? "sparql";
            const body = trimBlankEdges(cell.value.split("\n")).join("\n");
            return body.length > 0
                ? "```" + language + "\n" + body + "\n```"
                : "```" + language + "\n```";
        }
        return trimBlankEdges(cell.value.split("\n")).join("\n");
    });

    const text = blocks.filter((block) => block.length > 0).join("\n\n");
    const result = text.length > 0 ? text + "\n" : "";
    return notebook.eol === "\r\n" ? result.replace(/\n/g, "\r\n") : result;
}

/** Drops leading/trailing lines that are empty or whitespace-only. */
function trimBlankEdges(lines: string[]): string[] {
    let start = 0;
    let end = lines.length;
    while (start < end && lines[start].trim().length === 0) start++;
    while (end > start && lines[end - 1].trim().length === 0) end--;
    return lines.slice(start, end);
}
