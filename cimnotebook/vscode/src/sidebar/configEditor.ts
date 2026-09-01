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
 * Owns the configuration tree views' one write mechanism: which `opencgmes.jsonc` file
 * they edit, and how. `read()`/`update()` are the only way the tree views (or their
 * commands) touch the file — always through {@link parseConfigModel}/
 * {@link applyConfigModel}'s comment-preserving property-path edits, exactly like the
 * former webview sidebar did, so hand-editing and the tree views coexist.
 *
 * The edited file is the *nearest* `opencgmes.{jsonc,json}` walking up from the active
 * document (within its workspace folder), falling back to the first workspace folder's
 * root — matching validation's own nearest-config discovery.
 */

import * as vscode from "vscode";

import { applyConfigModel, ConfigModel, parseConfigModel } from "./configModel";

export interface ConfigTarget {
    uri: vscode.Uri;
    exists: boolean;
}

export interface ConfigState extends ConfigTarget {
    model: ConfigModel;
}

/** Belt-and-braces guard: the parent-fixpoint check below is the real terminator. */
const MAX_CONFIG_WALK_DEPTH = 64;

export async function targetConfig(): Promise<ConfigTarget> {
    const active =
        vscode.window.activeNotebookEditor?.notebook.uri ??
        vscode.window.activeTextEditor?.document.uri;
    const folder = active
        ? vscode.workspace.getWorkspaceFolder(active)
        : vscode.workspace.workspaceFolders?.[0];
    if (active && active.scheme === "file") {
        // Mirror the server's nearest-config discovery (ConfigLoader.discoverFile): walk from the
        // document's directory all the way to the filesystem root, not just to the workspace
        // folder — otherwise the sidebar would edit (or offer to create) a different file than
        // the one validation and execution actually use.
        let dir = vscode.Uri.joinPath(active, "..");
        for (let i = 0; i < MAX_CONFIG_WALK_DEPTH; i++) {
            for (const name of ["opencgmes.jsonc", "opencgmes.json"]) {
                const candidate = vscode.Uri.joinPath(dir, name);
                if (await exists(candidate)) {
                    return { uri: candidate, exists: true };
                }
            }
            const parent = vscode.Uri.joinPath(dir, "..");
            if (parent.path === dir.path) {
                break;
            }
            dir = parent;
        }
    }
    const root = folder ?? vscode.workspace.workspaceFolders?.[0];
    if (root) {
        const jsonc = vscode.Uri.joinPath(root.uri, "opencgmes.jsonc");
        if (await exists(jsonc)) {
            return { uri: jsonc, exists: true };
        }
        const json = vscode.Uri.joinPath(root.uri, "opencgmes.json");
        if (await exists(json)) {
            return { uri: json, exists: true };
        }
        return { uri: jsonc, exists: false };
    }
    return { uri: vscode.Uri.file("opencgmes.jsonc"), exists: false };
}

/** The current target config's location and parsed model. */
export async function readConfig(): Promise<ConfigState> {
    const target = await targetConfig();
    const text = target.exists ? await readText(target.uri) : "";
    return { ...target, model: parseConfigModel(text) };
}

/**
 * Applies `mutate` to `target`'s model and writes the result back — a property-path
 * edit, so comments and formatting elsewhere in the file are preserved. `target` is the
 * {@link readConfig} result the calling wizard was built from: pinning the file here
 * keeps a mid-wizard focus change from silently redirecting the write to a different
 * config. The text itself is re-read (and existence re-checked) at write time, so
 * concurrent hand-edits are never clobbered by a stale in-memory copy. Returns `false`
 * (after showing an error message) if the write failed; the tree views treat that as
 * "nothing changed" and skip the refresh.
 *
 * An existing config is edited through a {@link vscode.WorkspaceEdit} and then saved,
 * not written straight to disk: when the file is open in an editor with unsaved changes,
 * that buffer — not the file on disk — is its current content, and a raw `fs.writeFile`
 * would both build on stale text and be silently reverted by the user's next save. The
 * edit also lands on the editor's undo stack, so the change can be undone like any other.
 */
export async function updateConfig(
    target: ConfigTarget,
    mutate: (model: ConfigModel) => void,
): Promise<boolean> {
    try {
        if (!(await exists(target.uri))) {
            const model = parseConfigModel("");
            mutate(model);
            const created = applyConfigModel("", model);
            await vscode.workspace.fs.writeFile(target.uri, Buffer.from(created, "utf8"));
            return true;
        }

        const document = await vscode.workspace.openTextDocument(target.uri);
        const text = document.getText();
        const model = parseConfigModel(text);
        mutate(model);
        const newText = applyConfigModel(text, model);
        if (newText === text) {
            return true;
        }
        const edit = new vscode.WorkspaceEdit();
        edit.replace(
            target.uri,
            new vscode.Range(document.positionAt(0), document.positionAt(text.length)),
            newText,
        );
        if (!(await vscode.workspace.applyEdit(edit))) {
            throw new Error("the workspace edit was rejected");
        }
        await document.save();
        return true;
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        vscode.window.showErrorMessage(`CIMNotebook: saving the config failed: ${msg}`);
        return false;
    }
}

/** Opens the target config, offering to create it first when it doesn't exist yet. */
export async function openConfig(): Promise<void> {
    const target = await targetConfig();
    if (target.exists) {
        await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(target.uri));
    } else {
        await vscode.commands.executeCommand("cimnotebook.createConfig");
    }
}

async function exists(uri: vscode.Uri): Promise<boolean> {
    try {
        await vscode.workspace.fs.stat(uri);
        return true;
    } catch {
        return false;
    }
}

/**
 * The config's current text: an already-open editor's buffer when there is one (it may hold
 * unsaved edits, and those are what the tree views should reflect), else the file on disk. Only
 * *already-open* documents are consulted — this runs on every tree refresh, and opening the
 * document here just to read it would be wasteful.
 */
async function readText(uri: vscode.Uri): Promise<string> {
    const open = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri.toString());
    if (open) {
        return open.getText();
    }
    return Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
}
