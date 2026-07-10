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
 * "CIMNotebook: Convert Notebook" — writes the active notebook in the other format
 * (markdown `*.cimnb.md` ⇔ Zazuko `*.sparqlbook`) as a sibling file and opens it.
 * Converting never touches the source file.
 */

import * as vscode from "vscode";

import { serializeMarkdownNotebook } from "./markdown";
import { serializeSparqlBook } from "./sparqlbook";
import {
    notebookDocumentToRaw,
    NOTEBOOK_TYPE_DEFAULT,
    NOTEBOOK_TYPE_SPARQLBOOK,
    NOTEBOOK_TYPES,
} from "./serializers";

export const CONVERT_COMMAND = "cimnotebook.notebook.convert";

export function registerConvertCommand(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand(CONVERT_COMMAND, convertActiveNotebook),
    );
}

interface TargetFormat {
    label: string;
    description: string;
    serialize: (notebook: ReturnType<typeof notebookDocumentToRaw>) => string;
    targetUri: (source: vscode.Uri) => vscode.Uri;
    openAsType: string;
}

const TO_MARKDOWN: TargetFormat = {
    label: "Markdown notebook (*.cimnb.md)",
    description: "Git-friendly markdown with ```sparql / ```shacl cells",
    serialize: serializeMarkdownNotebook,
    targetUri: (source) =>
        siblingUri(source, [".cimnb.md", ".sparqlbook", ".md", ".markdown"], ".cimnb.md"),
    openAsType: NOTEBOOK_TYPE_DEFAULT,
};

const TO_SPARQLBOOK: TargetFormat = {
    label: "SPARQL Book (*.sparqlbook)",
    description: "JSON format of the Zazuko SPARQL Notebook extension",
    serialize: serializeSparqlBook,
    targetUri: (source) => siblingUri(source, [".cimnb.md", ".md", ".markdown"], ".sparqlbook"),
    openAsType: NOTEBOOK_TYPE_SPARQLBOOK,
};

async function convertActiveNotebook(): Promise<void> {
    const notebook = vscode.window.activeNotebookEditor?.notebook;
    if (!notebook || !(NOTEBOOK_TYPES as readonly string[]).includes(notebook.notebookType)) {
        vscode.window.showWarningMessage("CIMNotebook: open a CIM Notebook to convert it.");
        return;
    }

    const source = notebook.uri;
    const target = await vscode.window.showQuickPick(
        (source.path.endsWith(".sparqlbook") ? [TO_MARKDOWN] : [TO_SPARQLBOOK, TO_MARKDOWN]).map(
            (format) => ({ ...format, detail: format.targetUri(source).path }),
        ),
        { title: "Convert notebook to" },
    );
    if (!target) {
        return;
    }

    const targetUri = target.targetUri(source);
    if (targetUri.toString() === source.toString()) {
        vscode.window.showInformationMessage("CIMNotebook: notebook is already in that format.");
        return;
    }
    if (await fileExists(targetUri)) {
        const choice = await vscode.window.showWarningMessage(
            `CIMNotebook: ${vscode.workspace.asRelativePath(targetUri)} already exists.`,
            "Overwrite",
        );
        if (choice !== "Overwrite") {
            return;
        }
    }

    const content = target.serialize(notebookDocumentToRaw(notebook));
    await vscode.workspace.fs.writeFile(targetUri, Buffer.from(content, "utf8"));
    await vscode.commands.executeCommand("vscode.openWith", targetUri, target.openAsType);
}

/** Strips the first matching suffix (longest first) and appends the target suffix. */
function siblingUri(source: vscode.Uri, strip: string[], suffix: string): vscode.Uri {
    const name = source.path.slice(source.path.lastIndexOf("/") + 1);
    const match = strip
        .filter((s) => name.toLowerCase().endsWith(s))
        .sort((a, b) => b.length - a.length)[0];
    const base = match ? name.slice(0, name.length - match.length) : name;
    return source.with({ path: source.path.slice(0, -name.length) + base + suffix });
}

async function fileExists(uri: vscode.Uri): Promise<boolean> {
    try {
        await vscode.workspace.fs.stat(uri);
        return true;
    } catch {
        return false;
    }
}
