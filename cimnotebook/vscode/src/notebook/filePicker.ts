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
 * File search / autocomplete for the sidebar's schema-file fields and the *Set Cell
 * Endpoint* command: a QuickPick over `workspace.findFiles`, whose built-in fuzzy
 * filtering is the search-as-you-type behaviour, with a `showOpenDialog` fallback for
 * files outside the workspace (e.g. multi-root setups, or files under an excluded
 * folder). Paths are returned relative to a caller-supplied base directory, matching how
 * `opencgmes.jsonc` paths and `# [endpoint=...]` directives are resolved.
 */

import * as path from "path";
import * as vscode from "vscode";

import { relativizePath } from "./relativizePath";

/** Keeps the picker responsive and avoids listing generated/vendor trees. */
const DEFAULT_EXCLUDES = "**/{node_modules,.git,out,out-test,dist,build,target}/**";
const MAX_RESULTS = 2000;

interface FileQuickPickItem extends vscode.QuickPickItem {
    uri?: vscode.Uri;
    browse?: boolean;
    manual?: boolean;
}

export interface PickWorkspaceFilesOptions {
    /** Glob passed to `workspace.findFiles`, e.g. `"**\/*.{rdf,ttl,owl}"`. */
    pattern: string;
    /** Directory the returned paths are relativized against. */
    baseUri: vscode.Uri;
    title: string;
    placeHolder?: string;
    canPickMany?: boolean;
    /**
     * Adds an "Enter path manually…" item for paths the picker can't offer — typically a
     * file that doesn't exist yet. The typed path is returned verbatim (it is already
     * relative to `baseUri` by convention), so no existence check is applied.
     */
    allowManualEntry?: boolean;
}

/**
 * QuickPick over the workspace files matching `pattern`. Returns paths relative to
 * `baseUri` in `./...` form, or `undefined` if the user cancelled. The last item,
 * "Browse…", opens a native file dialog for files the glob can't reach (excluded
 * folders, other drives, …) — picking it takes over the whole selection, since the
 * dialog has its own multi-select UI.
 */
export async function pickWorkspaceFiles(
    options: PickWorkspaceFilesOptions,
): Promise<string[] | undefined> {
    const { pattern, baseUri, title, placeHolder, canPickMany, allowManualEntry } = options;
    const found = await vscode.workspace.findFiles(pattern, DEFAULT_EXCLUDES, MAX_RESULTS);
    const items: FileQuickPickItem[] = found
        .slice()
        .sort((a, b) => a.fsPath.localeCompare(b.fsPath))
        .map((uri) => ({
            label: `$(file) ${path.basename(uri.fsPath)}`,
            description: vscode.workspace.asRelativePath(path.dirname(uri.fsPath), false),
            uri,
        }));
    items.push({ label: "$(folder-opened) Browse…", browse: true });
    if (allowManualEntry) {
        items.push({ label: "$(edit) Enter path manually…", manual: true });
    }

    const picked = await vscode.window.showQuickPick(items, {
        title,
        placeHolder: placeHolder ?? "Type to search files by name",
        canPickMany,
        matchOnDescription: true,
    });
    if (!picked) {
        return undefined;
    }
    const selections = Array.isArray(picked) ? picked : [picked];
    if (selections.length === 0) {
        return undefined;
    }
    // Like Browse…, manual entry takes over the whole selection: it is an escape hatch,
    // not one pick among many.
    if (selections.some((s) => s.manual)) {
        const typed = await vscode.window.showInputBox({
            title,
            placeHolder: "./model.xml",
            prompt: "Relative paths are resolved against the notebook / config file directory.",
        });
        const trimmed = typed?.trim();
        return trimmed ? [trimmed] : undefined;
    }
    if (selections.some((s) => s.browse)) {
        const uris = await vscode.window.showOpenDialog({
            title,
            canSelectMany: canPickMany,
            canSelectFolders: false,
            canSelectFiles: true,
        });
        if (!uris || uris.length === 0) {
            return undefined;
        }
        return uris.map((uri) => relativizePath(baseUri.fsPath, uri.fsPath));
    }
    return selections
        .filter((s): s is FileQuickPickItem & { uri: vscode.Uri } => s.uri !== undefined)
        .map((s) => relativizePath(baseUri.fsPath, s.uri.fsPath));
}

export interface PickWorkspaceFolderOptions {
    baseUri: vscode.Uri;
    title: string;
}

/** Native folder dialog, returning the picked folder relative to `baseUri`. */
export async function pickWorkspaceFolder(
    options: PickWorkspaceFolderOptions,
): Promise<string | undefined> {
    const uris = await vscode.window.showOpenDialog({
        title: options.title,
        canSelectMany: false,
        canSelectFolders: true,
        canSelectFiles: false,
    });
    const uri = uris?.[0];
    return uri ? relativizePath(options.baseUri.fsPath, uri.fsPath) : undefined;
}
