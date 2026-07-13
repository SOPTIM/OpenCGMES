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

import * as vscode from "vscode";
import * as path from "path";
import * as fs from "fs";
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from "vscode-languageclient/node";

import { registerNotebookSerializers } from "./notebook/serializers";
import { registerNotebookControllers } from "./notebook/controller";
import { registerConvertCommand } from "./notebook/convert";
import { ConnectionStore } from "./notebook/connections";
import { registerEndpointCommands } from "./notebook/endpointCommands";
import { registerCellStatusBar } from "./notebook/statusBar";
import { registerConfigTreeViews } from "./sidebar/treeViews";

const CHANNEL = "CIMNotebook";

let client: LanguageClient | undefined;
// Created at the very start of activate() so it always appears in the Output dropdown.
let out: vscode.LogOutputChannel;

export function activate(context: vscode.ExtensionContext): void {
    out = vscode.window.createOutputChannel(CHANNEL, { log: true });
    context.subscriptions.push(out);

    out.appendLine("Extension activating...");
    out.appendLine(`Extension path: ${context.extensionPath}`);
    out.appendLine(`VS Code version: ${vscode.version}`);

    // Command: "CIMNotebook: Show Output" — always opens the channel.
    context.subscriptions.push(
        vscode.commands.registerCommand("cimnotebook.showOutput", () => out.show(true)),
    );

    // Command: "CIMNotebook: Explain Query" — show the static algebra plan for the current query.
    context.subscriptions.push(
        vscode.commands.registerCommand("cimnotebook.explainQuery", explainQuery),
    );

    // Command: "CIMNotebook: Create Config File" — scaffold opencgmes.jsonc in the workspace root.
    context.subscriptions.push(
        vscode.commands.registerCommand("cimnotebook.createConfig", createConfig),
    );

    // Native CIM Notebooks (markdown + .sparqlbook formats; cells are validated through
    // the vscode-notebook-cell entries of the LSP documentSelector below and executed
    // via the server's cimvocabcheck.notebook.execute command).
    registerNotebookSerializers(context);
    const connectionStore = new ConnectionStore(context, () => client);
    registerNotebookControllers(context, () => client, connectionStore);
    registerConvertCommand(context);
    registerEndpointCommands(context, connectionStore);
    registerCellStatusBar(context, connectionStore);
    registerConfigTreeViews(context, connectionStore);

    try {
        doActivate(context, connectionStore);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`FATAL during activation: ${msg}`);
        out.show(true);
        vscode.window.showErrorMessage(`CIMNotebook failed to start: ${msg}`);
    }
}

function doActivate(context: vscode.ExtensionContext, connectionStore: ConnectionStore): void {
    const serverJar = resolveServerJar(context);
    if (!serverJar) {
        const hint =
            "Cannot find cimvocabcheck-lsp.jar. " +
            'Set "cimnotebook.serverJar" to the JAR path in VS Code settings, ' +
            'or click "Show Output" to see where it was searched.';
        out.show(true);
        vscode.window.showErrorMessage(`CIMNotebook: ${hint}`, "Show Output").then((c) => {
            if (c === "Show Output") out.show(true);
        });
        return;
    }

    client = buildClient(serverJar, context);
    client.start().then(
        () => {
            // The notebook defaults live in workspace state, so a freshly started server knows
            // none of them — replay them, or directive-less cells would validate syntax-only.
            void connectionStore.syncNotebookDefaults();
        },
        (err: unknown) => {
            const msg = err instanceof Error ? err.message : String(err);
            out.appendLine(`Language server failed to start: ${msg}`);
        },
    );
    out.appendLine("Language client started — waiting for server handshake.");

    // Offer a reload when the user changes launch settings.
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((e) => {
            const keys = [
                "cimnotebook.serverJar",
                "cimnotebook.javaExecutable",
                "cimnotebook.javaArgs",
            ];
            if (keys.some((k) => e.affectsConfiguration(k))) {
                vscode.window
                    .showInformationMessage(
                        "CIMNotebook: Settings changed — reload window to apply.",
                        "Reload Window",
                    )
                    .then((c) => {
                        if (c === "Reload Window") {
                            vscode.commands.executeCommand("workbench.action.reloadWindow");
                        }
                    });
            }
        }),
    );
}

export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
}

/**
 * Scaffolds an `opencgmes.jsonc` in the workspace root. CIMNotebook works without it, but there is no
 * bundled default schema, so validation stays syntax-only until the file points at CGMES profiles.
 * The template text comes from the language server's `cimvocabcheck.createConfig` command so the CLI and
 * editors stay in sync. A plain `opencgmes.json` (no comments) is also recognised by CIMNotebook — if one
 * already exists, it is treated as the existing config instead of creating a second `opencgmes.jsonc`
 * alongside it.
 */
async function createConfig(): Promise<void> {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        vscode.window.showWarningMessage(
            "CIMNotebook: open a folder to create opencgmes.jsonc in.",
        );
        return;
    }
    // Prefer an existing plain opencgmes.json over creating a second, competing opencgmes.jsonc.
    let target = vscode.Uri.joinPath(folder.uri, "opencgmes.jsonc");
    let exists = await fileExists(target);
    if (!exists) {
        const jsonTarget = vscode.Uri.joinPath(folder.uri, "opencgmes.json");
        if (await fileExists(jsonTarget)) {
            target = jsonTarget;
            exists = true;
        }
    }
    if (exists) {
        const choice = await vscode.window.showWarningMessage(
            `CIMNotebook: ${vscode.workspace.asRelativePath(target)} already exists.`,
            "Open",
            "Overwrite",
        );
        if (choice === "Open") {
            await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(target));
            return;
        }
        if (choice !== "Overwrite") {
            return;
        }
    }
    try {
        let content: string | undefined;
        if (client) {
            content = await client.sendRequest<string>("workspace/executeCommand", {
                command: "cimvocabcheck.createConfig",
                arguments: [],
            });
        }
        await vscode.workspace.fs.writeFile(
            target,
            Buffer.from(content ?? '{\n  "cimvocabcheck": {}\n}\n', "utf8"),
        );
        await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(target));
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`Create Config failed: ${msg}`);
        vscode.window.showErrorMessage(`CIMNotebook: Create Config failed: ${msg}`);
    }
}

async function fileExists(uri: vscode.Uri): Promise<boolean> {
    try {
        await vscode.workspace.fs.stat(uri);
        return true;
    } catch {
        return false;
    }
}

/**
 * Sends the current selection (or the whole document when nothing is selected) to the language
 * server's `cimvocabcheck.explainQuery` command and opens the returned algebra plan in a read-only
 * editor tab beside the query.
 */
async function explainQuery(): Promise<void> {
    if (!client) {
        vscode.window.showWarningMessage("CIMNotebook: language server is not running.");
        return;
    }
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage("CIMNotebook: open a SPARQL query to explain.");
        return;
    }
    const sel = editor.selection;
    const text = sel.isEmpty ? editor.document.getText() : editor.document.getText(sel);
    try {
        const plan = await client.sendRequest<string>("workspace/executeCommand", {
            command: "cimvocabcheck.explainQuery",
            arguments: [text],
        });
        const doc = await vscode.workspace.openTextDocument({
            content: plan ?? "(no plan returned)",
            language: "sparql",
        });
        await vscode.window.showTextDocument(doc, {
            viewColumn: vscode.ViewColumn.Beside,
            preview: true,
        });
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`Explain Query failed: ${msg}`);
        vscode.window.showErrorMessage(`CIMNotebook: Explain Query failed: ${msg}`);
    }
}

// ---- Helpers -------------------------------------------------------------------------------

function resolveServerJar(context: vscode.ExtensionContext): string | undefined {
    const config = vscode.workspace.getConfiguration("cimnotebook");

    // 1. Explicit user setting.
    const configured = config.get<string>("serverJar", "").trim();
    if (configured) {
        out.appendLine(`[jar] Trying setting: ${configured}`);
        if (fs.existsSync(configured)) {
            out.appendLine("[jar] Found ✓");
            return configured;
        }
        out.appendLine("[jar] NOT FOUND — check the path in settings");
        vscode.window.showWarningMessage(`CIMNotebook: serverJar not found: ${configured}`);
    }

    // 2. Bundled JAR shipped inside the extension's server/ directory.
    const bundled = context.asAbsolutePath(path.join("server", "cimvocabcheck-lsp.jar"));
    out.appendLine(`[jar] Trying bundled: ${bundled}`);
    if (fs.existsSync(bundled)) {
        out.appendLine("[jar] Found ✓");
        return bundled;
    }
    out.appendLine("[jar] NOT FOUND");

    return undefined;
}

function buildClient(serverJar: string, context: vscode.ExtensionContext): LanguageClient {
    const config = vscode.workspace.getConfiguration("cimnotebook");
    const javaExe = config.get<string>("javaExecutable", "java");
    const extraArgs = config.get<string[]>("javaArgs", []);
    const args = [...extraArgs, "-jar", serverJar];

    out.appendLine(`[launch] ${javaExe} ${args.join(" ")}`);

    const serverOptions: ServerOptions = {
        run: {
            command: javaExe,
            args,
            transport: TransportKind.stdio,
        },
        debug: {
            command: javaExe,
            // Attach a Java debugger on localhost:5005 when running under F5.
            args: [
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005",
                ...args,
            ],
            transport: TransportKind.stdio,
        },
    };

    const traceChannel = vscode.window.createOutputChannel(`${CHANNEL} (trace)`, { log: true });
    context.subscriptions.push(traceChannel);

    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: "file", language: "sparql" },
            { scheme: "file", language: "shacl" },
            { scheme: "file", language: "turtle" },
            { scheme: "file", pattern: "**/*.ttl" },
            { scheme: "file", pattern: "**/*.shacl" },
            // SPARQL Notebook (and any notebook) cells: forwarded as ordinary text documents
            // under the vscode-notebook-cell scheme, validated per-cell by the server.
            { scheme: "vscode-notebook-cell", language: "sparql" },
            { scheme: "vscode-notebook-cell", language: "shacl" },
        ],
        // Route all server output (stderr) into our output channel.
        outputChannel: out,
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher("**/opencgmes.{jsonc,json}"),
        },
        traceOutputChannel: traceChannel,
    };

    return new LanguageClient("cimnotebook", CHANNEL, serverOptions, clientOptions);
}
