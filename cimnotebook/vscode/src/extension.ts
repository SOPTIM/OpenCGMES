import * as vscode from "vscode";
import * as path from "path";
import * as fs from "fs";
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from "vscode-languageclient/node";

const CHANNEL = "CIMNotebook";

let client: LanguageClient | undefined;
// Created at the very start of activate() so it always appears in the Output dropdown.
let out: vscode.OutputChannel;

export function activate(context: vscode.ExtensionContext): void {
    out = vscode.window.createOutputChannel(CHANNEL);
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

    // Command: "CIMNotebook: Create Config File" — scaffold opencgmes.json in the workspace root.
    context.subscriptions.push(
        vscode.commands.registerCommand("cimnotebook.createConfig", createConfig),
    );

    try {
        doActivate(context);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`FATAL during activation: ${msg}`);
        out.show(true);
        vscode.window.showErrorMessage(`CIMNotebook failed to start: ${msg}`);
    }
}

function doActivate(context: vscode.ExtensionContext): void {
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
    client.start();
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
 * Scaffolds an `opencgmes.json` in the workspace root. CIMNotebook works without it (validating against
 * the bundled CGMES 3.0 schemas); the generated file is fully commented and exists for customisation.
 * The template text comes from the language server's `cimvocabcheck.createConfig` command so the CLI and
 * editors stay in sync.
 */
async function createConfig(): Promise<void> {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        vscode.window.showWarningMessage("CIMNotebook: open a folder to create opencgmes.json in.");
        return;
    }
    const target = vscode.Uri.joinPath(folder.uri, "opencgmes.json");
    try {
        await vscode.workspace.fs.stat(target);
        const choice = await vscode.window.showWarningMessage(
            "CIMNotebook: opencgmes.json already exists.",
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
    } catch {
        // Does not exist yet — fall through and create it.
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

    const traceChannel = vscode.window.createOutputChannel(`${CHANNEL} (trace)`);
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
            fileEvents: vscode.workspace.createFileSystemWatcher("**/opencgmes.json"),
        },
        traceOutputChannel: traceChannel,
    };

    return new LanguageClient("cimnotebook", CHANNEL, serverOptions, clientOptions);
}
