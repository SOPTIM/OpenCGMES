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
 * Resolves a cell's execution target from its `# [endpoint=...]` directives — the same
 * magic-comment syntax the language server reads to pick the validation schema, so one
 * directive drives both "validate against" and "run against".
 *
 * Pure module (no `vscode` import); the LSP wire types for the
 * `cimvocabcheck.notebook.execute` command live here too, mirroring the server's records
 * in `cimvocabcheck/lsp/.../notebook/`.
 */

/** Same pattern as the server's `EndpointDirective` (value has no spaces or `]`). */
const DIRECTIVE = /^\s*#\s*\[\s*endpoint\s*=\s*([^\]\s]+)\s*\]/gm;

/** Returns every `# [endpoint=...]` value in the cell text, in order. */
export function parseEndpointDirectives(text: string): string[] {
    return [...text.matchAll(DIRECTIVE)].map((m) => m[1].trim());
}

export type ResolvedTarget =
    | { type: "http"; url: string; updateUrl: string }
    | { type: "unsupported"; directive: string }
    | { type: "none" };

/**
 * Resolves the target for a cell: the first `http(s)://` directive wins. Non-URL
 * directives (file paths, names) are recognised but not yet executable — they resolve to
 * `unsupported` so the cell can show a precise message instead of a parse error.
 */
export function resolveTarget(cellText: string): ResolvedTarget {
    const directives = parseEndpointDirectives(cellText);
    if (directives.length === 0) {
        return { type: "none" };
    }
    const url = directives.find((d) => /^https?:\/\//i.test(d));
    if (url === undefined) {
        return { type: "unsupported", directive: directives[0] };
    }
    return { type: "http", url, updateUrl: deriveUpdateUrl(url) };
}

/**
 * Best-effort update endpoint for a query endpoint URL: Fuseki-style `…/query` or
 * `…/sparql` services get their `…/update` sibling (dropping any query string); anything
 * else is assumed to accept updates on the same URL. The server stays strict — it only
 * executes updates against an explicit `updateUrl` — so this derivation is the client's
 * deliberate choice, not a hidden server fallback.
 */
export function deriveUpdateUrl(url: string): string {
    const sibling = /^(.*\/)(?:query|sparql)(?:\?.*)?$/i.exec(url);
    return sibling ? sibling[1] + "update" : url;
}

// ---- Wire types for cimvocabcheck.notebook.execute (mirror the server's records) ----

export const EXECUTE_COMMAND = "cimvocabcheck.notebook.execute";

export interface ExecuteTarget {
    type: "http";
    url: string;
    updateUrl?: string;
}

export interface ExecuteRequest {
    cellUri: string;
    languageId: string;
    text: string;
    target: ExecuteTarget;
    options?: { timeoutMs?: number; maxRows?: number };
}

export type QueryKind = "SELECT" | "ASK" | "CONSTRUCT" | "DESCRIBE" | "UPDATE";

export interface ExecError {
    code: string;
    message: string;
    detail?: string | null;
    line?: number | null;
    column?: number | null;
}

export interface ExecStats {
    durationMs: number;
    rowCount?: number | null;
    truncated: boolean;
    resolvedTarget?: string | null;
}

export interface ExecuteResponse {
    status: "SUCCESS" | "ERROR" | "CANCELLED";
    queryKind?: QueryKind | null;
    resultsJson?: string | null;
    turtle?: string | null;
    stats?: ExecStats | null;
    error?: ExecError | null;
}
