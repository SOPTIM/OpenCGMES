import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from 'vscode-languageclient/node';

const CHANNEL = 'SPARQL CGMES Validator';

let client: LanguageClient | undefined;
// Created at the very start of activate() so it always appears in the Output dropdown.
let out: vscode.OutputChannel;

export function activate(context: vscode.ExtensionContext): void {
    out = vscode.window.createOutputChannel(CHANNEL);
    context.subscriptions.push(out);

    out.appendLine('Extension activating...');
    out.appendLine(`Extension path: ${context.extensionPath}`);
    out.appendLine(`VS Code version: ${vscode.version}`);

    // Command: "SPARQL CGMES Validator: Show Output" — always opens the channel.
    context.subscriptions.push(
        vscode.commands.registerCommand('sparqlValidation.showOutput', () => out.show(true))
    );

    try {
        doActivate(context);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`FATAL during activation: ${msg}`);
        out.show(true);
        vscode.window.showErrorMessage(`SPARQL CGMES Validator failed to start: ${msg}`);
    }
}

function doActivate(context: vscode.ExtensionContext): void {
    const serverJar = resolveServerJar(context);
    if (!serverJar) {
        const hint = 'Cannot find sparql-validate-lsp.jar. ' +
            'Set "sparqlValidation.serverJar" to the JAR path in VS Code settings, ' +
            'or click "Show Output" to see where it was searched.';
        out.show(true);
        vscode.window.showErrorMessage(`SPARQL CGMES Validator: ${hint}`, 'Show Output')
            .then(c => { if (c === 'Show Output') out.show(true); });
        return;
    }

    client = buildClient(serverJar);
    client.start();
    out.appendLine('Language client started — waiting for server handshake.');

    // Offer a reload when the user changes launch settings.
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            const keys = ['sparqlValidation.serverJar',
                          'sparqlValidation.javaExecutable',
                          'sparqlValidation.javaArgs'];
            if (keys.some(k => e.affectsConfiguration(k))) {
                vscode.window.showInformationMessage(
                    'SPARQL CGMES Validator: Settings changed — reload window to apply.',
                    'Reload Window'
                ).then(c => {
                    if (c === 'Reload Window') {
                        vscode.commands.executeCommand('workbench.action.reloadWindow');
                    }
                });
            }
        })
    );

    context.subscriptions.push({ dispose: () => client?.stop() });
}

export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
}

// ---- Helpers -------------------------------------------------------------------------------

function resolveServerJar(context: vscode.ExtensionContext): string | undefined {
    const config = vscode.workspace.getConfiguration('sparqlValidation');

    // 1. Explicit user setting.
    const configured = config.get<string>('serverJar', '').trim();
    if (configured) {
        out.appendLine(`[jar] Trying setting: ${configured}`);
        if (fs.existsSync(configured)) {
            out.appendLine('[jar] Found ✓');
            return configured;
        }
        out.appendLine('[jar] NOT FOUND — check the path in settings');
        vscode.window.showWarningMessage(
            `SPARQL CGMES Validator: serverJar not found: ${configured}`
        );
    }

    // 2. Bundled JAR shipped inside the extension's server/ directory.
    const bundled = context.asAbsolutePath(path.join('server', 'sparql-validate-lsp.jar'));
    out.appendLine(`[jar] Trying bundled: ${bundled}`);
    if (fs.existsSync(bundled)) {
        out.appendLine('[jar] Found ✓');
        return bundled;
    }
    out.appendLine('[jar] NOT FOUND');

    return undefined;
}

function buildClient(serverJar: string): LanguageClient {
    const config    = vscode.workspace.getConfiguration('sparqlValidation');
    const javaExe   = config.get<string>('javaExecutable', 'java');
    const extraArgs = config.get<string[]>('javaArgs', []);
    const args      = [...extraArgs, '-jar', serverJar];

    out.appendLine(`[launch] ${javaExe} ${args.join(' ')}`);

    const serverOptions: ServerOptions = {
        run: {
            command: javaExe,
            args,
            transport: TransportKind.stdio,
        },
        debug: {
            command: javaExe,
            // Attach a Java debugger on port 5005 when running under F5.
            args: ['-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005', ...args],
            transport: TransportKind.stdio,
        },
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'sparql' }],
        // Route all server output (stderr) into our output channel.
        outputChannel: out,
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/.cgmes/validation.json'),
        },
        traceOutputChannel: vscode.window.createOutputChannel(`${CHANNEL} (trace)`),
    };

    return new LanguageClient('sparqlValidation', CHANNEL, serverOptions, clientOptions);
}
