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

import { ConnectionStore } from "./connections";
import { clearCredentials, setCredentials } from "./credentials";
import { applyEndpointDirective, ConnectionInfo } from "./endpoint";

export const SET_ENDPOINT_COMMAND = "cimnotebook.notebook.setEndpoint";
export const SET_CREDENTIALS_COMMAND = "cimnotebook.notebook.setCredentials";
export const CLEAR_CREDENTIALS_COMMAND = "cimnotebook.notebook.clearCredentials";

export function registerEndpointCommands(
    context: vscode.ExtensionContext,
    store: ConnectionStore,
): void {
    context.subscriptions.push(
        vscode.commands.registerCommand(SET_ENDPOINT_COMMAND, (cell?: vscode.NotebookCell) =>
            setEndpoint(store, cell),
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

async function setEndpoint(store: ConnectionStore, cell?: vscode.NotebookCell): Promise<void> {
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

    let directive: string | null;
    switch (pick.action) {
        case "connection":
            directive = pick.connection?.name ?? null;
            break;
        case "url": {
            const url = await vscode.window.showInputBox({
                title: "SPARQL endpoint URL",
                placeHolder: "http://localhost:3030/dataset/query",
                validateInput: (v) =>
                    /^https?:\/\/\S+$/i.test(v) ? undefined : "Enter an http(s):// URL",
            });
            if (url === undefined) {
                return;
            }
            directive = url;
            break;
        }
        case "file": {
            const file = await vscode.window.showInputBox({
                title: "Data file path (relative to the notebook)",
                placeHolder: "./model.xml",
            });
            if (file === undefined || file === "") {
                return;
            }
            directive = file;
            break;
        }
        case "clear":
            directive = null;
            break;
    }

    const scope =
        directive === null
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
    if (scope === "Notebook default") {
        await store.setNotebookDefault(target.notebook, directive ?? undefined);
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
    if (mode === "set") {
        if (await setCredentials(context.secrets, name)) {
            vscode.window.showInformationMessage(
                `CIMNotebook: credentials for "${name}" stored in VS Code secret storage.`,
            );
        }
    } else {
        await clearCredentials(context.secrets, name);
        vscode.window.showInformationMessage(`CIMNotebook: credentials for "${name}" cleared.`);
    }
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
