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
 * Commands for endpoints and credentials:
 *
 * - *Set Cell Endpoint* — QuickPick over the config's connections plus free-form URL /
 *   file input; writes a `# [endpoint=…]` directive into the cell (the directive is the
 *   portable source of truth, versioned with the file), or stores a notebook-wide
 *   default in workspaceState without touching the file.
 * - *Set / Clear Connection Credentials* — manage the SecretStorage entries for
 *   `authType: "basic"` connections.
 */

import * as vscode from "vscode";

import { validateRequiredUrl } from "../sidebar/treeItems";
import { ConnectionStore } from "./connections";
import { runCredentialsAction } from "./credentials";
import { applyEndpointDirective, ConnectionInfo } from "./endpoint";
import { pickWorkspaceFiles } from "./filePicker";

export const SET_ENDPOINT_COMMAND = "cimnotebook.notebook.setEndpoint";
export const SET_CREDENTIALS_COMMAND = "cimnotebook.notebook.setCredentials";
export const CLEAR_CREDENTIALS_COMMAND = "cimnotebook.notebook.clearCredentials";

/** Data file extensions offered by the "Enter data file path…" picker. */
const DATA_FILE_PATTERN = "**/*.{xml,ttl,rdf,owl,nt,nq,trig}";

/** Workspace-scoped memory of recently used endpoint URLs (most recent first). */
const RECENT_URLS_KEY = "cimnotebook.recentEndpointUrls";
const MAX_RECENT_URLS = 5;

export function registerEndpointCommands(
    context: vscode.ExtensionContext,
    store: ConnectionStore,
): void {
    context.subscriptions.push(
        vscode.commands.registerCommand(SET_ENDPOINT_COMMAND, (cell?: vscode.NotebookCell) =>
            setEndpoint(context, store, cell),
        ),
        vscode.commands.registerCommand(SET_CREDENTIALS_COMMAND, () =>
            manageCredentials(context, store, "set"),
        ),
        vscode.commands.registerCommand(CLEAR_CREDENTIALS_COMMAND, () =>
            manageCredentials(context, store, "clear"),
        ),
    );
}

// ---- Set Cell Endpoint -----------------------------------------------------------------------

interface EndpointPick extends vscode.QuickPickItem {
    action: "connection" | "url" | "file" | "clear" | "clear-default";
    connection?: ConnectionInfo;
}

async function setEndpoint(
    context: vscode.ExtensionContext,
    store: ConnectionStore,
    cell?: vscode.NotebookCell,
): Promise<void> {
    const target = cell ?? activeCell();
    if (!target) {
        vscode.window.showWarningMessage("CIMNotebook: no notebook cell is active.");
        return;
    }

    const { connections } = await store.forNotebook(target.notebook);
    const notebookDefault = store.notebookDefault(target.notebook);
    const items: EndpointPick[] = connections.map((connection) => ({
        action: "connection",
        connection,
        label: `$(plug) ${connection.name}`,
        description: connection.url,
        detail: connection.default ? "workspace default connection" : undefined,
    }));
    items.push(
        { action: "url", label: "$(globe) Enter endpoint URL…" },
        { action: "file", label: "$(file) Enter data file path…" },
        { action: "clear", label: "$(clear-all) Remove the cell's endpoint directive" },
    );
    if (notebookDefault) {
        items.push({
            action: "clear-default",
            label: "$(circle-slash) Clear the notebook default",
            description: notebookDefault,
        });
    }

    const pick = await vscode.window.showQuickPick(items, {
        title: notebookDefault
            ? `Where should this cell run? (notebook default: ${notebookDefault})`
            : "Where should this cell run?",
        placeHolder: "Connection, URL, or local data file",
    });
    if (!pick) {
        return;
    }
    if (pick.action === "clear-default") {
        await store.setNotebookDefault(target.notebook, undefined);
        return;
    }

    let directive: string | string[] | null;
    switch (pick.action) {
        case "connection":
            directive = pick.connection?.name ?? null;
            break;
        case "url": {
            const url = await pickEndpointUrl(context);
            if (url === undefined) {
                return;
            }
            directive = url;
            break;
        }
        case "file": {
            const files = await pickWorkspaceFiles({
                pattern: DATA_FILE_PATTERN,
                baseUri: vscode.Uri.joinPath(target.notebook.uri, ".."),
                title: "Data file(s) to run this cell against",
                placeHolder: "Search by file name — pick one or more (Tab to multi-select)",
                canPickMany: true,
                allowManualEntry: true,
            });
            const usable = filterDirectiveSafePaths(files);
            if (!usable || usable.length === 0) {
                return;
            }
            // A notebook-wide default is a single stored directive (connections.ts), so a
            // multi-file union — the M3 target — only makes sense written into the cell.
            directive = usable.length > 1 ? usable : usable[0];
            break;
        }
        case "clear":
            directive = null;
            break;
    }

    // "clear" (null) and multi-file (string[]) both apply to the cell only.
    const scope =
        directive === null || Array.isArray(directive)
            ? "This cell"
            : await vscode.window.showQuickPick(["This cell", "Notebook default"], {
                  title: "Apply where?",
                  placeHolder:
                      "This cell = write a # [endpoint=…] directive; Notebook default = " +
                      "remember for all directive-less cells (workspace state, not the file)",
              });
    if (scope === undefined) {
        return;
    }
    if (scope === "Notebook default" && typeof directive === "string") {
        await store.setNotebookDefault(target.notebook, directive);
        return;
    }

    const newText = applyEndpointDirective(target.document.getText(), directive);
    if (newText !== target.document.getText()) {
        const edit = new vscode.WorkspaceEdit();
        edit.replace(
            target.document.uri,
            new vscode.Range(0, 0, target.document.lineCount, 0),
            newText,
        );
        await vscode.workspace.applyEdit(edit);
    }
}

/**
 * A small QuickPick history of recently used URLs (workspaceState, last {@link
 * MAX_RECENT_URLS}) above a plain "Enter new URL…" input box — skipped entirely the
 * first time, when there is nothing to recall yet.
 */
async function pickEndpointUrl(context: vscode.ExtensionContext): Promise<string | undefined> {
    const recent = context.workspaceState.get<string[]>(RECENT_URLS_KEY, []);
    let url: string | undefined;
    if (recent.length === 0) {
        url = await promptForUrl();
    } else {
        interface UrlPick extends vscode.QuickPickItem {
            url?: string;
            enterNew?: boolean;
        }
        const items: UrlPick[] = recent.map((u) => ({ label: `$(history) ${u}`, url: u }));
        items.push({ label: "$(edit) Enter new URL…", enterNew: true });
        const pick = await vscode.window.showQuickPick(items, {
            title: "SPARQL endpoint URL",
            placeHolder: "Pick a recent URL or enter a new one",
        });
        if (!pick) {
            return undefined;
        }
        url = pick.enterNew ? await promptForUrl() : pick.url;
    }
    if (url === undefined) {
        return undefined;
    }
    await rememberRecentUrl(context, url);
    return url;
}

function promptForUrl(): Thenable<string | undefined> {
    return vscode.window.showInputBox({
        title: "SPARQL endpoint URL",
        placeHolder: "http://localhost:3030/dataset/query",
        validateInput: validateRequiredUrl,
    });
}

/**
 * Drops picked paths that a `# [endpoint=...]` directive cannot express — its value ends
 * at the first whitespace character (see `parseEndpointDirectives`) — and tells the user
 * why. Returns `undefined` when the pick itself was cancelled.
 */
function filterDirectiveSafePaths(files: string[] | undefined): string[] | undefined {
    if (!files) {
        return undefined;
    }
    const unusable = files.filter((f) => /\s/.test(f));
    if (unusable.length > 0) {
        vscode.window.showErrorMessage(
            `CIMNotebook: ${unusable.join(", ")} can't be used in a # [endpoint=…] directive — ` +
                "paths containing whitespace aren't supported. Rename the file or folder to use it here.",
        );
    }
    return files.filter((f) => !/\s/.test(f));
}

async function rememberRecentUrl(context: vscode.ExtensionContext, url: string): Promise<void> {
    const recent = context.workspaceState.get<string[]>(RECENT_URLS_KEY, []);
    const next = [url, ...recent.filter((u) => u !== url)].slice(0, MAX_RECENT_URLS);
    await context.workspaceState.update(RECENT_URLS_KEY, next);
}

function activeCell(): vscode.NotebookCell | undefined {
    const editor = vscode.window.activeNotebookEditor;
    if (!editor) {
        return undefined;
    }
    const range = editor.selections[0];
    if (!range || range.isEmpty) {
        return undefined;
    }
    return editor.notebook.cellAt(range.start);
}

// ---- credentials ------------------------------------------------------------------------------

async function manageCredentials(
    context: vscode.ExtensionContext,
    store: ConnectionStore,
    mode: "set" | "clear",
): Promise<void> {
    const name = await pickConnectionName(store);
    if (!name) {
        return;
    }
    await runCredentialsAction(context.secrets, name, mode);
}

async function pickConnectionName(store: ConnectionStore): Promise<string | undefined> {
    const notebook = vscode.window.activeNotebookEditor?.notebook;
    const connections = notebook ? (await store.forNotebook(notebook)).connections : [];
    const basicConnections = connections.filter((c) => c.authType?.toLowerCase() === "basic");
    if (basicConnections.length > 0) {
        const pick = await vscode.window.showQuickPick(
            basicConnections.map((c) => ({ label: c.name, description: c.url })),
            { title: "Connection" },
        );
        return pick?.label;
    }
    // No notebook open (or no basic-auth connections declared): fall back to free input so
    // credentials can still be managed.
    return vscode.window.showInputBox({
        title: "Connection name",
        placeHolder: "as declared in opencgmes.jsonc → cimnotebook.connections",
    });
}
