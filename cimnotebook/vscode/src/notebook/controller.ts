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
 * Notebook controllers (kernels) for the three CIM Notebook types. VS Code binds a
 * controller to exactly one notebook type, so each type gets its own controller instance;
 * they share one {@link CellExecutor}. Cancellation uses the per-cell execution token
 * (no `interruptHandler`), which the executor forwards to the language-server request.
 */

import * as vscode from "vscode";
import type { LanguageClient } from "vscode-languageclient/node";

import { ConnectionStore } from "./connections";
import { CellExecutor } from "./executor";
import { NOTEBOOK_TYPES } from "./serializers";

export function registerNotebookControllers(
    context: vscode.ExtensionContext,
    getClient: () => LanguageClient | undefined,
    store: ConnectionStore,
): void {
    const executor = new CellExecutor(getClient, store, context.secrets);
    let executionOrder = 0;

    for (const notebookType of NOTEBOOK_TYPES) {
        const controller = vscode.notebooks.createNotebookController(
            `${notebookType}.controller`,
            notebookType,
            "CIM Notebook",
        );
        controller.supportedLanguages = ["sparql", "shacl"];
        controller.supportsExecutionOrder = true;
        controller.description = "Runs SPARQL and SHACL cells via CIMLangServer";
        controller.executeHandler = async (cells) => {
            for (const cell of cells) {
                const execution = controller.createNotebookCellExecution(cell);
                execution.executionOrder = ++executionOrder;
                await executor.execute(cell, execution);
            }
        };
        context.subscriptions.push(controller);
    }
}
