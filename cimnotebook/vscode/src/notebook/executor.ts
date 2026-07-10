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
 * Executes one notebook cell through the language server's
 * `cimvocabcheck.notebook.execute` workspace command and turns the response into cell
 * outputs. Which MIME types those outputs carry — and, crucially, which one VS Code then
 * picks to render — is decided in {@link outputItemsFor}.
 */

import * as vscode from "vscode";
import type { LanguageClient } from "vscode-languageclient/node";

import {
    EXECUTE_COMMAND,
    ExecError,
    ExecuteRequest,
    ExecuteResponse,
    resolveTarget,
} from "./endpoint";
import { errorSummary, MIME_MARKDOWN, outputItemsFor } from "./outputs";

export class CellExecutor {
    constructor(private readonly getClient: () => LanguageClient | undefined) {}

    async execute(
        cell: vscode.NotebookCell,
        execution: vscode.NotebookCellExecution,
    ): Promise<void> {
        execution.start(Date.now());
        try {
            const ok = await this.run(cell, execution);
            execution.end(ok, Date.now());
        } catch (err) {
            if (execution.token.isCancellationRequested) {
                // The request was torn down by the Stop button; no error to report.
                await replaceMarkdown(execution, "*Execution cancelled.*");
                execution.end(false, Date.now());
                return;
            }
            const message = err instanceof Error ? err.message : String(err);
            await execution.replaceOutput(
                new vscode.NotebookCellOutput([
                    vscode.NotebookCellOutputItem.error(new Error(message)),
                ]),
            );
            execution.end(false, Date.now());
        }
    }

    private async run(
        cell: vscode.NotebookCell,
        execution: vscode.NotebookCellExecution,
    ): Promise<boolean> {
        const client = this.getClient();
        if (!client) {
            await replaceMarkdown(
                execution,
                "**Language server is not running.** Reload the window or check the CIMNotebook output channel.",
            );
            return false;
        }

        const text = cell.document.getText();
        const target = resolveTarget(text);
        if (target.type === "none") {
            await replaceMarkdown(
                execution,
                "**No endpoint configured.** Add a directive to the cell, e.g.\n\n" +
                    "```\n# [endpoint=http://localhost:3030/dataset/query]\n```\n\n" +
                    "or point it at a local RDF or CIMXML file:\n\n" +
                    "```\n# [endpoint=./model.xml]\n```",
            );
            return false;
        }

        const request: ExecuteRequest = {
            cellUri: cell.document.uri.toString(),
            notebookUri: cell.notebook.uri.toString(),
            languageId: cell.document.languageId,
            text,
            target:
                target.type === "http"
                    ? { type: "http", url: target.url, updateUrl: target.updateUrl }
                    : { type: "files", files: target.files },
        };
        const response = await client.sendRequest<ExecuteResponse>(
            "workspace/executeCommand",
            { command: EXECUTE_COMMAND, arguments: [request] },
            execution.token,
        );
        return renderResponse(response, execution);
    }
}

async function renderResponse(
    response: ExecuteResponse | null | undefined,
    execution: vscode.NotebookCellExecution,
): Promise<boolean> {
    if (!response) {
        await replaceMarkdown(execution, "**No response from the language server.**");
        return false;
    }

    if (response.status === "CANCELLED") {
        await replaceMarkdown(execution, "*Execution cancelled.*");
        return false;
    }

    if (response.status === "ERROR" || response.error) {
        const error = response.error ?? { code: "INTERNAL", message: "Unknown error" };
        await execution.replaceOutput(
            new vscode.NotebookCellOutput([vscode.NotebookCellOutputItem.error(toError(error))]),
        );
        return false;
    }

    const items = outputItemsFor(response);
    if (items.length === 0) {
        await replaceMarkdown(
            execution,
            `**Unexpected result kind \`${response.queryKind ?? "?"}\`.**`,
        );
        return false;
    }
    await execution.replaceOutput(
        new vscode.NotebookCellOutput(
            items.map((item) => vscode.NotebookCellOutputItem.text(item.text, item.mime)),
        ),
    );
    return true;
}

/**
 * The error VS Code renders in its red output box. The server's `detail` (an endpoint's
 * response body, a Jena message) goes into `stack`, which the renderer prints below the
 * message — an accompanying markdown item would never be seen, since the items of one
 * output are alternatives and the error MIME type outranks markdown.
 */
function toError(error: ExecError): Error {
    const err = new Error(errorSummary(error));
    err.name = "CIMNotebook";
    err.stack = error.detail?.trimEnd() ?? "";
    return err;
}

function markdownItem(markdown: string): vscode.NotebookCellOutputItem {
    return vscode.NotebookCellOutputItem.text(markdown, MIME_MARKDOWN);
}

async function replaceMarkdown(
    execution: vscode.NotebookCellExecution,
    markdown: string,
): Promise<void> {
    await execution.replaceOutput(new vscode.NotebookCellOutput([markdownItem(markdown)]));
}
