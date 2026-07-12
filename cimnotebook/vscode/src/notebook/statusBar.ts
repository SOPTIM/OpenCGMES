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
 * Per-cell status bar item showing where the cell will run — connection, URL, files, or
 * a warning when nothing is configured. Clicking it opens the *Set Cell Endpoint*
 * command for that cell.
 */

import * as vscode from "vscode";

import { ConnectionStore } from "./connections";
import { statusBarText } from "./endpoint";
import { NOTEBOOK_TYPES } from "./serializers";
import { SET_ENDPOINT_COMMAND } from "./endpointCommands";

export function registerCellStatusBar(
    context: vscode.ExtensionContext,
    store: ConnectionStore,
): void {
    const changeEmitter = new vscode.EventEmitter<void>();
    context.subscriptions.push(changeEmitter);
    // Re-resolve labels when the config or a notebook default changes; cell edits
    // already re-trigger the provider through VS Code itself.
    context.subscriptions.push(store.onDidChange(() => changeEmitter.fire()));

    const provider: vscode.NotebookCellStatusBarItemProvider = {
        onDidChangeCellStatusBarItems: changeEmitter.event,
        async provideCellStatusBarItems(cell) {
            if (cell.kind !== vscode.NotebookCellKind.Code) {
                return [];
            }
            const resolution = await store.resolveCell(cell);
            const item = new vscode.NotebookCellStatusBarItem(
                statusBarText(resolution),
                vscode.NotebookCellStatusBarAlignment.Right,
            );
            item.tooltip = "Set the endpoint this cell runs against";
            item.command = {
                title: "Set Cell Endpoint",
                command: SET_ENDPOINT_COMMAND,
                arguments: [cell],
            };
            return [item];
        },
    };

    for (const notebookType of NOTEBOOK_TYPES) {
        context.subscriptions.push(
            vscode.notebooks.registerNotebookCellStatusBarItemProvider(notebookType, provider),
        );
    }
}
