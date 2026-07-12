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
 * Client-side view of a notebook's named connections: fetches the `cimnotebook` config
 * section from the language server (`listConnections`, nearest-config discovery happens
 * there), caches it per notebook, and invalidates the cache when any `opencgmes.jsonc`
 * changes. Also holds the per-notebook default directive (workspaceState) that cells
 * without their own directive fall back to — and pushes it to the server, which needs it
 * to validate those cells against the same endpoint they run against.
 */

import * as vscode from "vscode";
import type { LanguageClient } from "vscode-languageclient/node";

import {
    CellResolution,
    LIST_CONNECTIONS_COMMAND,
    ListConnectionsResponse,
    resolveCellTarget,
    SET_DEFAULT_ENDPOINT_COMMAND,
    SetDefaultEndpointRequest,
} from "./endpoint";

const EMPTY: ListConnectionsResponse = { connections: [] };

const DEFAULT_DIRECTIVE_KEY = "cimnotebook.defaultEndpoint";

export class ConnectionStore {
    /**
     * Keyed by notebook URI. Holds the *in-flight* request, not just its result: the cell
     * status-bar provider resolves every visible cell, so a cold cache would otherwise fire one
     * `listConnections` per cell instead of one per notebook.
     */
    private readonly cache = new Map<string, Promise<ListConnectionsResponse>>();
    private readonly changeEmitter = new vscode.EventEmitter<void>();

    /** Fires when connections or notebook defaults may have changed (config edit, command). */
    readonly onDidChange = this.changeEmitter.event;

    constructor(
        private readonly context: vscode.ExtensionContext,
        private readonly getClient: () => LanguageClient | undefined,
    ) {
        const watcher = vscode.workspace.createFileSystemWatcher("**/opencgmes.{jsonc,json}");
        context.subscriptions.push(
            watcher,
            watcher.onDidChange(() => this.invalidate()),
            watcher.onDidCreate(() => this.invalidate()),
            watcher.onDidDelete(() => this.invalidate()),
            this.changeEmitter,
        );
    }

    /** The connections config applying to a notebook; empty when unavailable. */
    forNotebook(notebook: vscode.NotebookDocument): Promise<ListConnectionsResponse> {
        const key = notebook.uri.toString();
        const cached = this.cache.get(key);
        if (cached) {
            return cached;
        }
        const client = this.getClient();
        if (!client) {
            return Promise.resolve(EMPTY);
        }
        const pending = client
            .sendRequest<ListConnectionsResponse | null>("workspace/executeCommand", {
                command: LIST_CONNECTIONS_COMMAND,
                arguments: [{ notebookUri: key }],
            })
            .then((response) => response ?? EMPTY)
            .catch(() => {
                // Server not ready or command failed — behave as "no connections", and drop the
                // entry so the next call retries. Only evict our own: invalidate() may already
                // have replaced it with a newer request.
                if (this.cache.get(key) === pending) {
                    this.cache.delete(key);
                }
                return EMPTY;
            });
        this.cache.set(key, pending);
        return pending;
    }

    /** Full resolution for a cell, including notebook default and config default. */
    async resolveCell(cell: vscode.NotebookCell): Promise<CellResolution> {
        const { connections } = await this.forNotebook(cell.notebook);
        return resolveCellTarget(
            cell.document.getText(),
            connections,
            this.notebookDefault(cell.notebook),
        );
    }

    /** The notebook's stored default directive (URL, file, or connection name), if any. */
    notebookDefault(notebook: vscode.NotebookDocument): string | undefined {
        const all = this.context.workspaceState.get<Record<string, string>>(
            DEFAULT_DIRECTIVE_KEY,
            {},
        );
        return all[notebook.uri.toString()];
    }

    /** Stores (or clears, with undefined) the notebook's default directive. */
    async setNotebookDefault(
        notebook: vscode.NotebookDocument,
        directive: string | undefined,
    ): Promise<void> {
        const all = {
            ...this.context.workspaceState.get<Record<string, string>>(DEFAULT_DIRECTIVE_KEY, {}),
        };
        if (directive === undefined) {
            delete all[notebook.uri.toString()];
        } else {
            all[notebook.uri.toString()] = directive;
        }
        await this.context.workspaceState.update(DEFAULT_DIRECTIVE_KEY, all);
        await this.pushNotebookDefault(notebook.uri.toString(), directive ?? null);
        this.changeEmitter.fire();
    }

    /**
     * Re-sends every stored notebook default to the language server. The defaults live in workspace
     * state, so the server — which validates cells and has no notion of notebooks — starts out
     * knowing none of them: without this, a directive-less cell would be validated syntax-only
     * (no completion, no hover, no go-to-definition) until the user picked its endpoint again.
     * Called once the client is running, and on every restart.
     */
    async syncNotebookDefaults(): Promise<void> {
        const all = this.context.workspaceState.get<Record<string, string>>(
            DEFAULT_DIRECTIVE_KEY,
            {},
        );
        for (const [notebookUri, directive] of Object.entries(all)) {
            await this.pushNotebookDefault(notebookUri, directive);
        }
    }

    private async pushNotebookDefault(notebookUri: string, endpoint: string | null): Promise<void> {
        const client = this.getClient();
        if (!client) {
            return; // Server not up yet; syncNotebookDefaults() replays the state when it is.
        }
        const request: SetDefaultEndpointRequest = { notebookUri, endpoint };
        try {
            await client.sendRequest("workspace/executeCommand", {
                command: SET_DEFAULT_ENDPOINT_COMMAND,
                arguments: [request],
            });
        } catch {
            // Server not ready or command unsupported (an older server jar): execution still works
            // — only the cell's schema-based validation keeps using the workspace schema.
        }
    }

    /** Drops all cached config responses (config file changed). */
    invalidate(): void {
        this.cache.clear();
        this.changeEmitter.fire();
    }
}
