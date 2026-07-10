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
 * Renders `cimvocabcheck.notebook.execute` responses as the items of one notebook cell
 * output. Pure string module: the vscode `NotebookCellOutputItem` wrapping happens in the
 * executor, so these builders stay unit-testable with plain `node --test`.
 *
 * The markdown item is the primary rendering (v1 ships no custom notebook renderer); the
 * raw payload travels alongside under its own MIME type for other renderers and for
 * *Change Presentation*.
 */

import { ExecError, ExecStats, ExecuteResponse } from "./endpoint";

/** Rows shown in the markdown table; the raw output item carries the full result. */
export const DISPLAY_ROW_CAP = 50;

export const MIME_MARKDOWN = "text/markdown";
export const MIME_SPARQL_JSON = "application/sparql-results+json";
export const MIME_TEXT = "text/plain";

/** One representation of a cell result — the executor turns these into output items. */
export interface OutputItem {
    mime: string;
    text: string;
}

/**
 * The items representing a successful result, primary rendering first.
 *
 * The items of one output are *alternatives*: VS Code renders exactly one of them, the
 * richest MIME type by its display order. That order (`NOTEBOOK_DISPLAY_ORDER`) ranks
 * **`application/json` above `text/markdown`**, so an `application/json` item silently
 * wins over — and hides — the formatted table, whatever order the items are given in.
 * Hence the raw results ship as `application/sparql-results+json` (which no built-in
 * renderer claims) plus `text/plain` (which ranks *below* markdown, so it stays available
 * under *Change Presentation* without taking over). Never add `application/json` here.
 */
export function outputItemsFor(response: ExecuteResponse): OutputItem[] {
    const stats = response.stats;
    switch (response.queryKind) {
        case "SELECT": {
            const resultsJson = response.resultsJson ?? "{}";
            return [
                { mime: MIME_MARKDOWN, text: selectResultsToMarkdown(resultsJson, stats) },
                { mime: MIME_SPARQL_JSON, text: resultsJson },
                { mime: MIME_TEXT, text: resultsJson },
            ];
        }
        case "ASK": {
            const resultsJson = response.resultsJson ?? "{}";
            return [
                { mime: MIME_MARKDOWN, text: askResultToMarkdown(resultsJson, stats) },
                { mime: MIME_SPARQL_JSON, text: resultsJson },
            ];
        }
        case "CONSTRUCT":
        case "DESCRIBE": {
            const turtle = response.turtle ?? "";
            return [
                { mime: MIME_MARKDOWN, text: turtleToMarkdown(turtle, stats) },
                { mime: MIME_TEXT, text: turtle },
            ];
        }
        case "UPDATE":
            return [{ mime: MIME_MARKDOWN, text: updateResultToMarkdown(stats) }];
        default:
            return [];
    }
}

interface SparqlResultsJson {
    head: { vars?: string[] };
    results?: { bindings: Record<string, SparqlTerm>[] };
    boolean?: boolean;
}

interface SparqlTerm {
    type: string;
    value: string;
    "xml:lang"?: string;
    datatype?: string;
}

/** Markdown table for a SELECT result (SPARQL 1.1 Query Results JSON). */
export function selectResultsToMarkdown(resultsJson: string, stats?: ExecStats | null): string {
    const parsed = JSON.parse(resultsJson) as SparqlResultsJson;
    const vars = parsed.head.vars ?? [];
    const bindings = parsed.results?.bindings ?? [];

    if (bindings.length === 0) {
        return "*No results.*" + statsFooter(stats);
    }

    const header = `| ${vars.map(escapeCell).join(" | ")} |`;
    const divider = `| ${vars.map(() => "---").join(" | ")} |`;
    const rows = bindings
        .slice(0, DISPLAY_ROW_CAP)
        .map((b) => `| ${vars.map((v) => escapeCell(termToText(b[v]))).join(" | ")} |`);

    const capNote =
        bindings.length > DISPLAY_ROW_CAP
            ? `\n\n*Table shows the first ${DISPLAY_ROW_CAP} of ${bindings.length} fetched rows.*`
            : "";
    return [header, divider, ...rows].join("\n") + capNote + statsFooter(stats);
}

/** Markdown verdict for an ASK result. */
export function askResultToMarkdown(resultsJson: string, stats?: ExecStats | null): string {
    const parsed = JSON.parse(resultsJson) as SparqlResultsJson;
    return (parsed.boolean === true ? "✅ **true**" : "❌ **false**") + statsFooter(stats);
}

/** Fenced turtle block for a CONSTRUCT/DESCRIBE result. */
export function turtleToMarkdown(turtle: string, stats?: ExecStats | null): string {
    const body = turtle.trimEnd();
    if (body.length === 0) {
        return "*Empty graph.*" + statsFooter(stats);
    }
    return "```turtle\n" + body + "\n```" + statsFooter(stats);
}

/** Confirmation line for a successful UPDATE. */
export function updateResultToMarkdown(stats?: ExecStats | null): string {
    return "✅ **Update executed.**" + statsFooter(stats);
}

/** One-line summary for `NotebookCellOutputItem.error`, with parse position if known. */
export function errorSummary(error: ExecError): string {
    const position =
        error.line != null
            ? ` (line ${error.line}${error.column != null ? `, column ${error.column}` : ""})`
            : "";
    return `${error.code}: ${error.message}${position}`;
}

function statsFooter(stats?: ExecStats | null): string {
    if (!stats) {
        return "";
    }
    const parts: string[] = [];
    if (stats.rowCount != null) {
        parts.push(
            stats.truncated
                ? `${stats.rowCount}+ results (server cap reached)`
                : `${stats.rowCount} results`,
        );
    }
    parts.push(`${stats.durationMs} ms`);
    if (stats.resolvedTarget) {
        parts.push(stats.resolvedTarget);
    }
    return `\n\n<small>${parts.map(escapeHtml).join(" · ")}</small>`;
}

/** Escapes a value for use inside a markdown table cell. */
function escapeCell(value: string): string {
    return value.replace(/\\/g, "\\\\").replace(/\|/g, "\\|").replace(/\r?\n/g, "<br>");
}

function escapeHtml(value: string | number): string {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/** Compact display text for one SPARQL JSON term. */
function termToText(term: SparqlTerm | undefined): string {
    if (term === undefined) {
        return "";
    }
    switch (term.type) {
        case "uri":
            return term.value;
        case "bnode":
            return `_:${term.value}`;
        default: {
            if (term["xml:lang"]) {
                return `"${term.value}"@${term["xml:lang"]}`;
            }
            if (term.datatype) {
                return `"${term.value}"^^<${term.datatype}>`;
            }
            return term.value;
        }
    }
}
