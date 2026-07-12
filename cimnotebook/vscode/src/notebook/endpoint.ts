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

/**
 * What one directive value means: an endpoint URL, a named connection from the config's
 * `cimnotebook.connections`, or a file path. Mirrors the server's heuristic — a
 * connection name has no path separators and no extension dot, files virtually always
 * have one ("model.xml" is a file, "local-fuseki" a name).
 */
export function classifyDirective(directive: string): "url" | "name" | "file" {
    if (/^https?:\/\//i.test(directive)) {
        return "url";
    }
    if (!/[/\\.]/.test(directive)) {
        return "name";
    }
    return "file";
}

/**
 * How a cell's execution target was resolved. `authConnection` is set when the target
 * came from a connection declaring `authType: "basic"` — the executor then attaches
 * stored credentials before sending the request.
 */
export type CellResolution =
    | { kind: "target"; target: ExecuteTarget; label: string; authConnection?: ConnectionInfo }
    | { kind: "unknown-connection"; name: string; known: string[] }
    | { kind: "ambiguous-directives"; directives: string[] }
    | { kind: "none" };

/**
 * Resolves a cell's execution target with the full precedence chain: the cell's own
 * directives → the notebook's default directive (set via the *Set Cell Endpoint*
 * command) → the config connection marked `"default": true` → none. Pure — the caller
 * supplies the config connections (from `listConnections`) and the stored notebook
 * default.
 */
export function resolveCellTarget(
    cellText: string,
    connections: ConnectionInfo[],
    notebookDefaultDirective?: string,
): CellResolution {
    const directives = parseEndpointDirectives(cellText);
    if (directives.length > 0) {
        return resolveDirectives(directives, connections);
    }
    if (notebookDefaultDirective) {
        return resolveDirectives([notebookDefaultDirective], connections);
    }
    const defaultConnection = connections.find((c) => c.default === true);
    if (defaultConnection) {
        return connectionResolution(defaultConnection, connections);
    }
    return { kind: "none" };
}

/**
 * One target per cell. Several *file* directives are a union (the M3 multi-file target),
 * but any other repetition — two URLs, two connection names, or a mix of kinds — has no
 * single meaning, and quietly running the cell against whichever one came first would
 * send the query somewhere the author didn't ask for. Those are reported instead.
 */
function resolveDirectives(directives: string[], connections: ConnectionInfo[]): CellResolution {
    const kinds = new Set(directives.map(classifyDirective));
    if (kinds.size > 1 || (directives.length > 1 && !kinds.has("file"))) {
        return { kind: "ambiguous-directives", directives };
    }

    if (kinds.has("url")) {
        const url = directives[0];
        return {
            kind: "target",
            target: {
                type: "http",
                url,
                updateUrl: deriveUpdateUrl(url),
                shaclUrl: deriveShaclUrl(url),
            },
            label: url,
        };
    }
    if (kinds.has("name")) {
        const name = directives[0];
        const connection = connections.find((c) => c.name === name);
        if (!connection) {
            return {
                kind: "unknown-connection",
                name,
                known: connections.map((c) => c.name),
            };
        }
        return connectionResolution(connection, connections);
    }
    return {
        kind: "target",
        target: { type: "files", files: directives },
        label: directives.join(", "),
    };
}

function connectionResolution(
    connection: ConnectionInfo,
    connections: ConnectionInfo[],
): CellResolution {
    if (!connection.url) {
        return {
            kind: "unknown-connection",
            name: connection.name,
            known: connections.filter((c) => c.url).map((c) => c.name),
        };
    }
    return {
        kind: "target",
        target: {
            type: "http",
            url: connection.url,
            updateUrl: connection.updateUrl || deriveUpdateUrl(connection.url),
            shaclUrl: connection.shaclUrl || deriveShaclUrl(connection.url),
        },
        label: connection.name,
        authConnection: connection.authType?.toLowerCase() === "basic" ? connection : undefined,
    };
}

/** Short status-bar text for a cell's resolution, with a codicon hinting the kind. */
export function statusBarText(resolution: CellResolution): string {
    switch (resolution.kind) {
        case "target": {
            const target = resolution.target;
            if (target.type === "files") {
                const files = target.files ?? [];
                const first = files[0]?.replace(/^.*[/\\]/, "") ?? "?";
                return `$(file) ${first}${files.length > 1 ? ` (+${files.length - 1})` : ""}`;
            }
            const viaConnection = resolution.label !== target.url;
            return viaConnection
                ? `$(plug) ${resolution.label}`
                : `$(globe) ${shortUrl(target.url ?? "")}`;
        }
        case "unknown-connection":
            return `$(warning) unknown connection: ${resolution.name}`;
        case "ambiguous-directives":
            return "$(warning) conflicting endpoint directives";
        case "none":
            return "$(warning) no endpoint";
    }
}

function shortUrl(url: string): string {
    return url.replace(/^https?:\/\//i, "").replace(/\?.*$/, "");
}

/**
 * Returns the cell text with its endpoint directive(s) set to `value` (replacing the
 * first existing directive line with one line per value and dropping any further ones),
 * or with all directive lines removed when `value` is null. An array writes one line per
 * entry, in order — the *Set Cell Endpoint* command uses this for a multi-file union
 * target (M3). The directive stays the portable source of truth inside the file.
 */
export function applyEndpointDirective(cellText: string, value: string | string[] | null): string {
    const values = value === null ? [] : Array.isArray(value) ? value : [value];
    const directiveLine = /^\s*#\s*\[\s*endpoint\s*=\s*[^\]\s]+\s*\][^\n]*$/;
    const lines = cellText.split("\n");
    const kept: string[] = [];
    let inserted = false;
    for (const line of lines) {
        if (!directiveLine.test(line)) {
            kept.push(line);
            continue;
        }
        if (values.length > 0 && !inserted) {
            kept.push(...values.map((v) => `# [endpoint=${v}]`));
            inserted = true;
        }
        // Further directive lines (and all of them when clearing) are dropped.
    }
    if (values.length > 0 && !inserted) {
        kept.unshift(...values.map((v) => `# [endpoint=${v}]`));
    }
    return kept.join("\n");
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

/**
 * Best-effort SHACL service URL for a query endpoint URL: Fuseki-style `…/query` or
 * `…/sparql` services get their `…/shacl` sibling, a URL already ending in `/shacl` is
 * kept, and anything else gets `/shacl` appended. Fuseki's SHACL operation requires a
 * `?graph=` selector, so an existing query string is preserved and `?graph=default`
 * (the default graph) is added when there is none. As with updates, the server never
 * derives this — an explicit `shaclUrl` is required there.
 */
export function deriveShaclUrl(url: string): string {
    const query = /\?.*$/.exec(url)?.[0] ?? "";
    const path = query ? url.slice(0, -query.length) : url;
    const sibling = /^(.*\/)(?:query|sparql)$/i.exec(path);
    const shaclPath = sibling
        ? sibling[1] + "shacl"
        : /\/shacl$/i.test(path)
          ? path
          : path.replace(/\/+$/, "") + "/shacl";
    return shaclPath + (query || "?graph=default");
}

// ---- Wire types for the cimvocabcheck.notebook.* commands (mirror the server's records) ----

export const EXECUTE_COMMAND = "cimvocabcheck.notebook.execute";
export const LIST_CONNECTIONS_COMMAND = "cimvocabcheck.notebook.listConnections";
export const SET_DEFAULT_ENDPOINT_COMMAND = "cimvocabcheck.notebook.setDefaultEndpoint";

/**
 * Tells the server which endpoint a notebook's directive-less cells use, so they validate against
 * what they run against. The default lives in the client's workspace state (not in the notebook
 * file), hence this push; `endpoint: null` clears it.
 */
export interface SetDefaultEndpointRequest {
    notebookUri: string;
    endpoint: string | null;
}

/**
 * Credentials attached to an execute request. They come from VS Code SecretStorage —
 * never from the config file — and travel only inside this request.
 */
export interface ExecAuth {
    type: "basic";
    username: string;
    password: string;
}

/** One connection from the config's `cimnotebook.connections`, as the server lists it. */
export interface ConnectionInfo {
    name: string;
    url?: string;
    updateUrl?: string;
    shaclUrl?: string;
    authType?: string;
    default?: boolean;
}

/** Result of `cimvocabcheck.notebook.listConnections`. */
export interface ListConnectionsResponse {
    configPath?: string | null;
    connections: ConnectionInfo[];
    queryTimeoutSeconds?: number | null;
    maxRows?: number | null;
}

export interface ExecuteTarget {
    type: "http" | "files";
    url?: string;
    updateUrl?: string;
    shaclUrl?: string;
    files?: string[];
    auth?: ExecAuth;
}

export interface ExecuteRequest {
    cellUri: string;
    /** Base for resolving relative file paths server-side; omitted for untitled notebooks. */
    notebookUri?: string;
    languageId: string;
    text: string;
    target: ExecuteTarget;
    options?: { timeoutMs?: number; maxRows?: number };
}

export type QueryKind = "SELECT" | "ASK" | "CONSTRUCT" | "DESCRIBE" | "UPDATE" | "SHACL";

export interface ShaclSummary {
    conforms: boolean;
    violations: number;
    warnings: number;
    infos: number;
}

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
    shaclSummary?: ShaclSummary | null;
    stats?: ExecStats | null;
    error?: ExecError | null;
}
