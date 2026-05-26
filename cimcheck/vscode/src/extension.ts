import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from 'vscode-languageclient/node';

const CHANNEL = 'CIMcheck';

let client: LanguageClient | undefined;
// Created at the very start of activate() so it always appears in the Output dropdown.
let out: vscode.OutputChannel;

export function activate(context: vscode.ExtensionContext): void {
    out = vscode.window.createOutputChannel(CHANNEL);
    context.subscriptions.push(out);

    out.appendLine('Extension activating...');
    out.appendLine(`Extension path: ${context.extensionPath}`);
    out.appendLine(`VS Code version: ${vscode.version}`);

    // Command: "CIMcheck: Show Output" — always opens the channel.
    context.subscriptions.push(
        vscode.commands.registerCommand('cimcheck.showOutput', () => out.show(true))
    );

    try {
        doActivate(context);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`FATAL during activation: ${msg}`);
        out.show(true);
        vscode.window.showErrorMessage(`CIMcheck failed to start: ${msg}`);
    }
}

function doActivate(context: vscode.ExtensionContext): void {
    const serverJar = resolveServerJar(context);
    if (!serverJar) {
        const hint = 'Cannot find cimcheck-lsp.jar. ' +
            'Set "cimcheck.serverJar" to the JAR path in VS Code settings, ' +
            'or click "Show Output" to see where it was searched.';
        out.show(true);
        vscode.window.showErrorMessage(`CIMcheck: ${hint}`, 'Show Output')
            .then(c => { if (c === 'Show Output') out.show(true); });
        return;
    }

    client = buildClient(serverJar, context);
    client.start();
    out.appendLine('Language client started — waiting for server handshake.');

    // Offer a reload when the user changes launch settings.
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            const keys = ['cimcheck.serverJar',
                          'cimcheck.javaExecutable',
                          'cimcheck.javaArgs'];
            if (keys.some(k => e.affectsConfiguration(k))) {
                vscode.window.showInformationMessage(
                    'CIMcheck: Settings changed — reload window to apply.',
                    'Reload Window'
                ).then(c => {
                    if (c === 'Reload Window') {
                        vscode.commands.executeCommand('workbench.action.reloadWindow');
                    }
                });
            }
        })
    );
}

export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
}

// ---- Helpers -------------------------------------------------------------------------------

function resolveServerJar(context: vscode.ExtensionContext): string | undefined {
    const config = vscode.workspace.getConfiguration('cimcheck');

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
            `CIMcheck: serverJar not found: ${configured}`
        );
    }

    // 2. Bundled JAR shipped inside the extension's server/ directory.
    const bundled = context.asAbsolutePath(path.join('server', 'cimcheck-lsp.jar'));
    out.appendLine(`[jar] Trying bundled: ${bundled}`);
    if (fs.existsSync(bundled)) {
        out.appendLine('[jar] Found ✓');
        return bundled;
    }
    out.appendLine('[jar] NOT FOUND');

    return undefined;
}

function buildClient(serverJar: string, context: vscode.ExtensionContext): LanguageClient {
    const config    = vscode.workspace.getConfiguration('cimcheck');
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
            // Attach a Java debugger on localhost:5005 when running under F5.
            args: ['-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005', ...args],
            transport: TransportKind.stdio,
        },
    };

    const traceChannel = vscode.window.createOutputChannel(`${CHANNEL} (trace)`);
    context.subscriptions.push(traceChannel);

    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: 'file', language: 'sparql' },
            { scheme: 'file', language: 'shacl' },
        ],
        // Route all server output (stderr) into our output channel.
        outputChannel: out,
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/.cgmes/validation.json'),
        },
        traceOutputChannel: traceChannel,
    };

    return new LanguageClient('cimcheck', CHANNEL, serverOptions, clientOptions);
}
