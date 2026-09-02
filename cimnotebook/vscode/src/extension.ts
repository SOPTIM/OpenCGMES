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
import * as crypto from "crypto";
import * as os from "os";
import * as tls from "tls";
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from "vscode-languageclient/node";
import {
    datasetNameFor,
    localNameOf,
    normalizeBaseUrl,
    parseDefinitionHeader,
    snapshotDatasetName,
    termDeepLink,
} from "./rdfArchitect";

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
// Singleton RDFArchitect panel — reopening the command reveals it instead of stacking panels.
let rdfArchitectPanel: vscode.WebviewPanel | undefined;
// The instance the panel currently shows. The panel outlives the call that created it and can be
// re-pointed at another instance, so its message handler has to read this rather than close over
// the base it was created with — otherwise a session is reported under the wrong URL and the
// language server, which pairs the two, declines to use it.
let rdfArchitectPanelBase: string | undefined;
// Kept for workspaceState, which remembers what was last sent to RDFArchitect.
let extensionContext: vscode.ExtensionContext;

export function activate(context: vscode.ExtensionContext): void {
    extensionContext = context;
    out = vscode.window.createOutputChannel(CHANNEL, { log: true });
    trustSystemCertificates();
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

    // Commands: "CIMNotebook: Open RDFArchitect" — embed the configured RDFArchitect instance in a
    // webview panel, or open it in the external browser. "Open in RDFArchitect" deep-links the
    // schema term under the cursor.
    context.subscriptions.push(
        vscode.commands.registerCommand("cimnotebook.openRdfArchitect", openRdfArchitect),
        vscode.commands.registerCommand(
            "cimnotebook.openRdfArchitectExternal",
            openRdfArchitectExternal,
        ),
        vscode.commands.registerCommand("cimnotebook.openInRdfArchitect", openInRdfArchitect),
        vscode.commands.registerCommand(
            "cimnotebook.openTermInRdfArchitect",
            openTermInRdfArchitect,
        ),
        vscode.commands.registerCommand("cimnotebook.sendSchemaToRdfArchitect", () =>
            sendSchemaToRdfArchitect(),
        ),
        vscode.commands.registerCommand("cimnotebook.reconnectRdfArchitect", reconnectRdfArchitect),
    );

    try {
        doActivate(context, connectionStore);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`FATAL during activation: ${msg}`);
        out.show(true);
        vscode.window.showErrorMessage(`CIMNotebook failed to start: ${msg}`);
    }
}

/**
 * Adds the machine's own CA certificates to the ones this extension host trusts.
 *
 * An RDFArchitect behind a company CA is trusted by the machine (and so by the panel, which uses
 * the system store) but not by Node, which ships its own list — the extension's REST calls would
 * fail where the panel works. Node exposes the OS store from v22.15, so no configuration and no
 * `NODE_EXTRA_CA_CERTS` are needed on those versions; where it is missing the calls behave as
 * before.
 *
 * The default CA list is per *process*, and VS Code runs every extension of a window in one
 * extension host — so this is the machine's certificates for all of them, not just for ours.
 * Confining it would mean a per-request agent, and an agent means an HTTP library: `fetch` alone
 * cannot carry one. It is kept because it only ever *adds* the certificates the operating system
 * already vouches for, and because `http.systemCertificates` — which is what VS Code itself uses
 * to decide the same question for its own requests — turns it off for a user who meant it.
 */
function trustSystemCertificates(): void {
    if (!vscode.workspace.getConfiguration("http").get<boolean>("systemCertificates", true)) {
        return;
    }
    if (!tls.getCACertificates || !tls.setDefaultCACertificates) {
        out.appendLine("Node cannot read the system certificate store — using its bundled CAs.");
        return;
    }
    try {
        const bundled = tls.getCACertificates("default");
        const system = tls.getCACertificates("system");
        const added = system.filter((cert) => !bundled.includes(cert));
        if (added.length === 0) {
            return;
        }
        tls.setDefaultCACertificates([...bundled, ...added]);
        out.appendLine(`Trusting ${added.length} certificate(s) from the system store.`);
    } catch (err) {
        out.appendLine(`Could not read the system certificate store: ${err}`);
    }
}

/** The trust arguments the running language server was launched with. */
let launchedTrustArgs: string[] = [];

/**
 * Whether a configuration change means the language server is now running with the wrong
 * certificates — its trust arguments follow `cimnotebook.rdfArchitectUrl`, but a running JVM does
 * not. Answering this rather than watching the setting keeps the reload prompt away from the
 * ordinary case: pointing at `http://localhost:3000` changes nothing about trust.
 */
function trustArgsWentStale(e: vscode.ConfigurationChangeEvent): boolean {
    if (!e.affectsConfiguration("cimnotebook.rdfArchitectUrl")) {
        return false;
    }
    const extraArgs = vscode.workspace
        .getConfiguration("cimnotebook")
        .get<string[]>("javaArgs", []);
    return systemTrustJavaArgs(extraArgs).join(" ") !== launchedTrustArgs.join(" ");
}

/**
 * Java arguments that make the language server trust the machine's certificates, since it runs in
 * its own JVM with its own store — the reason an RDFArchitect behind a company CA can work in the
 * panel and still fail to load a schema.
 *
 * Nothing is added when the user configured a truststore themselves; theirs wins. On macOS there is
 * no equivalent that can be set safely from here, so the CA has to be added to the JDK's `cacerts`
 * (or a truststore named in `cimnotebook.javaArgs`).
 *
 * Only done for an RDFArchitect reached over `https`, because on Linux this *replaces* the JVM's
 * trust store rather than adding to it: a distribution store that an administrator has pruned would
 * take HTTPS SPARQL endpoints down with it, for users who never opened RDFArchitect at all.
 */
function systemTrustJavaArgs(configured: string[]): string[] {
    if (configured.some((arg) => arg.startsWith("-Djavax.net.ssl.trustStore"))) {
        return [];
    }
    const rdfArchitect = vscode.workspace
        .getConfiguration("cimnotebook")
        .get<string>("rdfArchitectUrl", "")
        .trim();
    if (!rdfArchitect.startsWith("https://")) {
        return [];
    }
    if (os.platform() === "win32") {
        return ["-Djavax.net.ssl.trustStoreType=Windows-ROOT"];
    }
    // Distributions keep a Java view of the system store in sync with it, and on a stock
    // installation it is a superset of the JDK's own list.
    const systemJavaStores = [
        "/etc/ssl/certs/java/cacerts", // Debian, Ubuntu (ca-certificates-java)
        "/etc/pki/java/cacerts", // Fedora, RHEL
    ];
    if (os.platform() === "linux") {
        const store = systemJavaStores.find((path) => fs.existsSync(path));
        if (store) {
            return [
                `-Djavax.net.ssl.trustStore=${store}`,
                "-Djavax.net.ssl.trustStorePassword=changeit",
            ];
        }
    }
    return [];
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
    // A backend session outlives the editor, so a workspace that was reading live datasets can
    // keep doing so without opening the panel again — once the server is up to be told about it.
    client.start().then(
        () => {
            // The notebook defaults live in workspace state, so a freshly started server knows
            // none of them — replay them, or directive-less cells would validate syntax-only.
            void connectionStore.syncNotebookDefaults();
            // The JAR's file date says when it was copied here; the server says which build it is.
            // A bundle packaged before a change looks exactly like a feature that does not work.
            const server = client?.initializeResult?.serverInfo;
            if (server) {
                out.appendLine(`[server] ${server.name} ${server.version ?? "(unversioned)"}`);
            }
            return restoreRdfArchitectSession();
        },
        (err: unknown) => {
            const msg = err instanceof Error ? err.message : String(err);
            out.appendLine(`Language server failed to start: ${msg}`);
        },
    );
    out.appendLine("Language client started — waiting for server handshake.");

    // Ctrl+Click on a term of an RDFArchitect-held model goes to a document the server renders;
    // opening one is what shows the term in the panel.
    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor(openRdfArchitectFromDefinition),
    );

    // Offer a reload when the user changes launch settings.
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((e) => {
            const keys = [
                "cimnotebook.serverJar",
                "cimnotebook.javaExecutable",
                "cimnotebook.javaArgs",
            ];
            if (keys.some((k) => e.affectsConfiguration(k)) || trustArgsWentStale(e)) {
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

// ---- RDFArchitect --------------------------------------------------------------------------

/**
 * Opens the configured RDFArchitect instance (an external deployment — RDFArchitect is not bundled)
 * inside a webview panel. The app is loaded in an iframe; because VS Code webviews are a third-party
 * browsing context, the instance's session cookie must allow cross-site use for the embedded app to
 * keep its state — the toolbar's "Open in Browser" button is the escape hatch when embedding does
 * not work against a given deployment.
 */
async function openRdfArchitect(): Promise<void> {
    const url = await resolveRdfArchitectUrl();
    if (url) {
        showRdfArchitectPanel(url, url, false);
    }
}

/**
 * "Open in RDFArchitect" editor action: asks the language server for the schema term under the
 * cursor (`cimvocabcheck.termInfo`) and opens RDFArchitect's deep link (`/mainpage?class=<iri>`)
 * in the webview panel. RDFArchitect locates the term across the schemas loaded in its session —
 * a class opens directly, an attribute, association or enum entry opens its declaring class.
 *
 * When the schema itself comes from RDFArchitect the term's profiles are known, so this offers the
 * same choice Ctrl+Click does rather than landing in whichever profile RDFArchitect finds first.
 */
async function openInRdfArchitect(): Promise<void> {
    if (!client) {
        vscode.window.showWarningMessage("CIMNotebook: language server is not running.");
        return;
    }
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage("CIMNotebook: open a SPARQL or SHACL document first.");
        return;
    }
    const pos = editor.selection.active;
    try {
        const known = await termAtPosition(editor.document, pos);
        if (known) {
            await openTermInRdfArchitect(known.baseUrl, known.dataset, known.iri, known.profiles);
            return;
        }
        const base = await resolveRdfArchitectUrl();
        if (!base) {
            return;
        }
        const info = await client.sendRequest<{ iri?: string } | null>("workspace/executeCommand", {
            command: "cimvocabcheck.termInfo",
            arguments: [editor.document.uri.toString(), pos.line, pos.character],
        });
        const iri = info?.iri;
        if (!iri) {
            vscode.window.showInformationMessage(
                "CIMNotebook: no schema term at the cursor position.",
            );
            return;
        }
        showRdfArchitectPanel(base, termDeepLink(base, iri), true, iri);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`Open in RDFArchitect failed: ${msg}`);
        vscode.window.showErrorMessage(`CIMNotebook: Open in RDFArchitect failed: ${msg}`);
    }
}

/**
 * The RDFArchitect-backed term at a position, with its profiles, or undefined when the document's
 * schema does not come from RDFArchitect (or nothing is at that position).
 */
async function termAtPosition(
    doc: vscode.TextDocument,
    pos: vscode.Position,
): Promise<
    { baseUrl: string; dataset?: string; iri: string; profiles: TermProfile[] } | undefined
> {
    const found = await rdfArchitectTerms(doc);
    const term = found?.terms.find(
        (t) =>
            t.line === pos.line &&
            pos.character >= t.startCharacter &&
            pos.character < t.endCharacter,
    );
    if (!found || !term) {
        return undefined;
    }
    // Without a connected session the server cannot say which instance holds the dataset; the
    // action's own fallback (the setting, prompting if unset) takes it from here.
    const base = found.baseUrl ?? (await resolveRdfArchitectUrl());
    return base
        ? {
              baseUrl: base,
              dataset: found.dataset ?? undefined,
              iri: term.iri,
              profiles: term.profiles ?? [],
          }
        : undefined;
}

/**
 * Opens one term in the RDFArchitect panel. The instance comes from wherever the schema is read
 * from, so this lands in the RDFArchitect the document is actually validated against, whatever the
 * `cimnotebook.rdfArchitectUrl` setting says.
 *
 * A term declared in several profiles is ambiguous — without saying which graph to open,
 * RDFArchitect shows whichever one it finds the term in first — so the choice is put to the user.
 */
async function openTermInRdfArchitect(
    base: string,
    dataset: string | undefined,
    iri: string,
    profiles: TermProfile[] = [],
): Promise<void> {
    if (!base || !iri) {
        return;
    }
    const profile = profiles.length > 1 ? await pickProfile(iri, profiles) : profiles[0];
    if (profiles.length > 1 && !profile) {
        return; // dismissed
    }
    const url = termDeepLink(base, iri, dataset, profile?.graph);
    showRdfArchitectPanel(base, url, true, iri);
}

/** Asks which profile's copy of a term to open. */
async function pickProfile(iri: string, profiles: TermProfile[]): Promise<TermProfile | undefined> {
    const items = profiles.map((profile) => ({
        label: profile.label,
        description: profile.graph,
        profile,
    }));
    const picked = await vscode.window.showQuickPick(items, {
        title: `Open ${localNameOf(iri)} in RDFArchitect`,
        placeHolder: `Declared in ${profiles.length} profiles — which one?`,
        matchOnDescription: true,
    });
    return picked?.profile;
}

/** One profile a term is declared in, and the RDFArchitect graph holding that profile. */
interface TermProfile {
    label: string;
    graph: string;
}

/** A document's terms and the RDFArchitect instance its schema comes from. */
interface RdfArchitectTerms {
    /** Absent when the config names a dataset without saying which instance holds it. */
    baseUrl?: string | null;
    /** Absent when the schema is a snapshot link, which every session names differently. */
    dataset?: string | null;
    terms: {
        line: number;
        startCharacter: number;
        endCharacter: number;
        iri: string;
        profiles: TermProfile[];
    }[];
}

/** The language server's answer for a document, or undefined when it is not RDFArchitect-backed. */
async function rdfArchitectTerms(doc: vscode.TextDocument): Promise<RdfArchitectTerms | undefined> {
    if (!client) {
        return undefined;
    }
    try {
        const found = await client.sendRequest<RdfArchitectTerms | null>(
            "workspace/executeCommand",
            {
                command: "cimvocabcheck.rdfArchitectTerms",
                arguments: [doc.uri.toString()],
            },
        );
        return found ?? undefined;
    } catch (err) {
        out.appendLine(`Could not resolve the RDFArchitect terms of ${doc.uri.fsPath}: ${err}`);
        return undefined;
    }
}

/**
 * Shows the term in the RDFArchitect panel whenever one of the language server's generated
 * definition documents is opened.
 *
 * A model held in RDFArchitect has no schema files, so `Ctrl+Click` on one of its terms goes to a
 * document the server renders from the loaded schema. Opening that document is the moment the user
 * asked to *see* the term — and the only moment we can act on: both editors resolve a `Ctrl+Click`
 * target while the user is merely hovering, so nothing may happen at resolution time. Hovering
 * loads the document but never activates an editor for it, which is exactly the distinction this
 * hooks into.
 */
function openRdfArchitectFromDefinition(editor: vscode.TextEditor | undefined): void {
    const doc = editor?.document;
    if (!doc || doc.lineCount === 0) {
        return;
    }
    const fields = parseDefinitionHeader(doc.lineAt(0).text);
    if (!fields) {
        return;
    }
    const iri = fields.get("class");
    if (!iri) {
        return;
    }
    const base =
        fields.get("base") ??
        vscode.workspace.getConfiguration("cimnotebook").get<string>("rdfArchitectUrl", "").trim();
    if (!base) {
        out.appendLine(
            `No RDFArchitect instance to show ${iri} in — set cimnotebook.rdfArchitectUrl.`,
        );
        return;
    }
    const url = termDeepLink(base, iri, fields.get("dataset"), fields.get("graph"));
    showRdfArchitectPanel(base, url, true, iri);
}

/**
 * "Send Schema to RDFArchitect": asks the language server for the workspace schema files
 * (`cimvocabcheck.schemaInfo`), imports them into a fresh RDFArchitect session as a read-only
 * dataset, snapshots that dataset, and opens the snapshot link in the webview panel — the
 * embedded browser session loads the snapshot and selects the dataset, so the workspace schema is
 * browsable without a manual import.
 */
async function sendSchemaToRdfArchitect(termIri?: string): Promise<void> {
    if (!client) {
        vscode.window.showWarningMessage("CIMNotebook: language server is not running.");
        return;
    }
    const base = await resolveRdfArchitectUrl();
    if (!base) {
        return;
    }
    try {
        const info = await workspaceSchemaInfo();
        if (!info) {
            vscode.window.showWarningMessage(
                "CIMNotebook: no schema configured — add schemas to opencgmes.jsonc first.",
            );
            return;
        }
        const handoff = await vscode.window.withProgress(
            {
                location: vscode.ProgressLocation.Notification,
                title: "CIMNotebook: sending schema to RDFArchitect…",
            },
            () => importSchemaAndSnapshot(base, info.configFile, info.schemaFiles, termIri),
        );
        await extensionContext.workspaceState.update(HANDOFF_KEY, handoff.record);
        showRdfArchitectPanel(base, handoff.url, true);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        out.appendLine(`Send Schema to RDFArchitect failed: ${msg}`);
        vscode.window.showErrorMessage(`CIMNotebook: Send Schema to RDFArchitect failed: ${msg}`);
    }
}

/**
 * The workspace's configured schema, as the language server resolves it for the active document
 * (`cimvocabcheck.schemaInfo`), or undefined when no schema is configured.
 */
async function workspaceSchemaInfo(): Promise<
    { configFile: string; schemaFiles: string[] } | undefined
> {
    const docUri = vscode.window.activeTextEditor?.document.uri.toString();
    const info = await client?.sendRequest<{
        configFile?: string;
        schemaFiles?: string[];
    } | null>("workspace/executeCommand", {
        command: "cimvocabcheck.schemaInfo",
        arguments: docUri ? [docUri] : [],
    });
    return info?.configFile && info.schemaFiles?.length
        ? { configFile: info.configFile, schemaFiles: info.schemaFiles }
        : undefined;
}

/**
 * Imports the schema files into RDFArchitect and returns the link to open, plus the record of what
 * was sent.
 *
 * Where they land depends on whether the panel's session is connected. With one, the REST calls run
 * in that session and the dataset stays editable, so later edits are read live. Without one they
 * run in a fresh, empty session of their own, and a read-only snapshot is the cross-session bridge
 * into whatever session the panel gets. Either way the dataset is built from scratch, so re-sending
 * an updated schema needs no cleanup of the previous one.
 */
async function importSchemaAndSnapshot(
    base: string,
    configFile: string,
    schemaFiles: string[],
    termIri?: string,
): Promise<{ url: string; record: SchemaHandoff }> {
    const session = connectedSession?.url === base ? connectedSession.id : undefined;
    const api = new RdfArchitectClient(base, session);
    const dataset = datasetNameFor(configFile);
    await api.importGraphs(dataset, schemaFiles);
    const url = new URL(base);
    let snapshot: string | undefined;
    if (session) {
        // The import landed in the panel's own session, so it can simply open the dataset — and
        // because it stays editable there, changes are picked up live by the language server.
        url.searchParams.set("dataset", dataset);
    } else {
        // No connected window: bridge into whatever session the panel gets via a snapshot, and
        // keep that copy read-only since nothing would ever read edits back out of it.
        await api.disableEditing(dataset);
        snapshot = await api.createSnapshot(dataset);
        url.searchParams.set("snapshot", snapshot);
        // RDFArchitect loads a snapshot under SNAPSHOT_<dataset>_<token>; preselect it.
        url.searchParams.set("dataset", snapshotDatasetName(dataset, snapshot));
    }
    // Sending the schema because a term could not be found: land on that term afterwards.
    if (termIri) {
        url.searchParams.set("class", termIri);
    }
    return {
        url: url.toString(),
        record: {
            url: base,
            dataset,
            snapshot,
            fingerprint: await schemaFingerprint(schemaFiles),
            sentAt: new Date().toISOString(),
        },
    };
}

// ---- The RDFArchitect view's session ---------------------------------------------------------

/** RDFArchitect's session cookie; its value is the session id the embedded app hands over. */
const RDFA_SESSION_COOKIE = "RDFA_SESSION_ID";

/** Where the connected instance is remembered; the session id belongs in secret storage. */
const SESSION_URL_KEY = "cimnotebook.rdfArchitect.session.url";

/** The RDFArchitect window this workspace is connected to. */
interface RdfArchitectSession {
    url: string;
    id: string;
}

/**
 * The secret-storage key for this workspace's session id.
 *
 * Secret storage is per extension, not per workspace, so the key carries the workspace — otherwise
 * two windows on two projects would overwrite each other's session.
 */
function sessionSecretKey(): string {
    const workspace =
        vscode.workspace.workspaceFile?.toString() ??
        vscode.workspace.workspaceFolders?.[0]?.uri.toString() ??
        "";
    return `cimnotebook.rdfArchitect.session:${workspace}`;
}

/** Remembers the connection: the instance in workspace state, the session id in secret storage. */
async function rememberSession(session: RdfArchitectSession | undefined): Promise<void> {
    if (!session) {
        await extensionContext.workspaceState.update(SESSION_URL_KEY, undefined);
        await extensionContext.secrets.delete(sessionSecretKey());
        return;
    }
    await extensionContext.workspaceState.update(SESSION_URL_KEY, session.url);
    await extensionContext.secrets.store(sessionSecretKey(), session.id);
}

/** What was remembered for this workspace, if the two halves are both still there. */
async function rememberedSession(): Promise<RdfArchitectSession | undefined> {
    const url = extensionContext.workspaceState.get<string>(SESSION_URL_KEY);
    const id = url ? await extensionContext.secrets.get(sessionSecretKey()) : undefined;
    // Normalised on the way out too: a session remembered by an earlier build was filed under
    // whichever spelling the panel happened to be opened with.
    return url && id ? { url: tolerantBaseUrl(url), id } : undefined;
}

/**
 * {@link normalizeBaseUrl}, but a value that is not a URL is passed through rather than thrown.
 * Comparing two odd spellings of the same odd value still works; failing here would not.
 */
function tolerantBaseUrl(url: string): string {
    try {
        return normalizeBaseUrl(url);
    } catch {
        return url.trim().replace(/\/+$/, "");
    }
}

let connectedSession: RdfArchitectSession | undefined;
let connectionStatus: vscode.StatusBarItem | undefined;

/**
 * Connects the RDFArchitect window the panel shows to the language server.
 *
 * Datasets in RDFArchitect belong to a browser session and are never published, so validating
 * against the model *as it is being edited* means reading that session. The embedded app reports
 * which session it uses (see {@link rdfArchitectHtml}), and this hands it to the server, which then
 * resolves `"rdfArchitect": "<dataset>"` against it. The id is a credential for that session: it
 * stays in secret storage and in the server's memory, and is never written to a config file.
 */
async function connectRdfArchitectSession(url: string, id: string): Promise<void> {
    if (connectedSession?.url === url && connectedSession.id === id) {
        return;
    }
    connectedSession = { url, id };
    await rememberSession(connectedSession);
    await sendConnectionToServer(connectedSession);
    out.appendLine(`Connected to the RDFArchitect session at ${url}.`);
    updateConnectionStatus();
}

/** Tells the language server which window to read, or that there is none. */
async function sendConnectionToServer(session: RdfArchitectSession | undefined): Promise<void> {
    if (!client) {
        return;
    }
    try {
        await client.sendRequest("workspace/executeCommand", {
            command: "cimvocabcheck.connectRdfArchitect",
            arguments: session ? [session.url, session.id] : [],
        });
    } catch (err) {
        out.appendLine(`Could not hand the RDFArchitect session to the server: ${err}`);
    }
}

/**
 * Restores the connection remembered for this workspace, so validation keeps working across an
 * editor restart without opening the panel first.
 *
 * A backend session outlives the browser that created it, but not the backend itself — and a
 * webview that lost its cookie silently gets a *new* session. Asking the instance which session
 * the id names distinguishes the two: a different answer means the remembered one is gone.
 */
async function restoreRdfArchitectSession(): Promise<void> {
    const remembered = await rememberedSession();
    if (!remembered) {
        return;
    }
    if (await sessionIsAlive(remembered)) {
        connectedSession = remembered;
        await sendConnectionToServer(remembered);
        out.appendLine(`Reconnected to the RDFArchitect session at ${remembered.url}.`);
    } else {
        await rememberSession(undefined);
        out.appendLine(
            "The remembered RDFArchitect session is gone — open the panel to reconnect.",
        );
    }
    updateConnectionStatus();
}

/** Whether the instance still knows this session (it answers with the id of the caller's own). */
async function sessionIsAlive(session: RdfArchitectSession): Promise<boolean> {
    try {
        const api = `${new URL(session.url).toString().replace(/\/+$/, "")}/api`;
        const res = await fetch(`${api}/session`, {
            headers: { Cookie: `${RDFA_SESSION_COOKIE}=${session.id}` },
        });
        if (!res.ok) {
            return false;
        }
        return ((await res.json()) as { id?: string }).id === session.id;
    } catch {
        return false;
    }
}

/**
 * Explains why the panel could not report a session, which decides what the user can do about it.
 *
 * The app answering is not something the panel can force: an instance that predates live datasets
 * has no session endpoint at all, and a newer one stays silent unless its deployment opted into the
 * handshake. Probing the endpoint from here tells the two apart — this session is not the panel's,
 * but its existence is what is being asked.
 */
async function reportMissingSession(base: string): Promise<void> {
    if (connectedSession?.url === base) {
        return; // already connected; a late retry lost the race
    }
    const api = `${new URL(base).toString().replace(/\/+$/, "")}/api`;
    let reason: string;
    try {
        const res = await fetch(`${api}/session`);
        reason = res.ok
            ? "the instance did not report its session to the panel — its deployment has to set " +
              "PUBLIC_EMBED_SESSION_HANDSHAKE=true for an embedded view to ask"
            : `the instance answered ${res.status} for /api/session — it is likely older than ` +
              "live-dataset support";
    } catch (err) {
        reason = `the instance could not be reached: ${err instanceof Error ? err.message : err}`;
    }
    out.appendLine(`No live RDFArchitect session: ${reason}.`);
    if (connectionStatus) {
        connectionStatus.tooltip =
            `No live session from ${base}.\n${reason}.\n` +
            "A dataset named in opencgmes.jsonc cannot be read; a snapshot link still works.";
    }
}

/** Reconnects on demand: re-probes the remembered session, else opens the panel to get a new one. */
async function reconnectRdfArchitect(): Promise<void> {
    connectedSession = undefined;
    await restoreRdfArchitectSession();
    if (!connectedSession) {
        await openRdfArchitect();
    }
}

/** A quiet indicator of whether live datasets can be read, with the reconnect command behind it. */
function updateConnectionStatus(): void {
    if (!connectionStatus) {
        connectionStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        connectionStatus.command = "cimnotebook.reconnectRdfArchitect";
        extensionContext.subscriptions.push(connectionStatus);
    }
    if (connectedSession) {
        connectionStatus.text = "$(plug) RDFArchitect";
        connectionStatus.tooltip =
            `Reading live datasets from ${connectedSession.url}.\n` +
            "Click to reconnect (e.g. after restarting RDFArchitect).";
    } else {
        connectionStatus.text = "$(debug-disconnect) RDFArchitect";
        connectionStatus.tooltip =
            "Not connected — a dataset named in opencgmes.jsonc cannot be read.\n" +
            "Click to connect by opening the RDFArchitect panel.";
    }
    connectionStatus.show();
}

// ---- Keeping RDFArchitect's copy of the schema in step ---------------------------------------

/** What "Send Schema to RDFArchitect" last handed to which instance, per workspace. */
interface SchemaHandoff {
    /** The instance the schema went to; a different URL means a different (empty) RDFArchitect. */
    url: string;
    dataset: string;
    /** The snapshot bridging into the panel's session, or undefined when it went to that session. */
    snapshot?: string;
    fingerprint: string;
    sentAt: string;
}

const HANDOFF_KEY = "cimnotebook.rdfArchitect.handoff";
const HANDOFF_OPT_OUT_KEY = "cimnotebook.rdfArchitect.handoff.optOut";

/**
 * Offers to send the workspace schema when the RDFArchitect panel opens, so the feature does not
 * depend on the user knowing that the command exists.
 *
 * The extension's REST calls and the panel's embedded browser are *different* backend sessions, so
 * there is no way to ask RDFArchitect whether the panel can see the schema. What can be checked is
 * what this workspace sent: the snapshot is the bridge, and it still resolves as long as the
 * backend has it — an instance that restarted loses in-memory snapshots, which reads exactly like
 * never having imported. Beyond that, the schema files are fingerprinted so a schema edited after
 * the last send offers an update instead of silently serving a stale model.
 *
 * @param base the RDFArchitect instance the panel was opened against
 * @param termIri a term the user was trying to reach, re-opened after an import
 */
async function offerSchemaSync(base: string, termIri?: string): Promise<void> {
    if (!client || extensionContext.workspaceState.get<boolean>(HANDOFF_OPT_OUT_KEY)) {
        return;
    }
    try {
        const info = await workspaceSchemaInfo();
        if (!info) {
            return;
        }
        const previous = extensionContext.workspaceState.get<SchemaHandoff>(HANDOFF_KEY);
        const inSync =
            previous?.url === base && (await handoffIsAlive(base, previous)) ? previous : undefined;
        const fingerprint = await schemaFingerprint(info.schemaFiles);
        if (inSync?.fingerprint === fingerprint) {
            return;
        }
        const accept = inSync ? "Update" : "Import";
        const message = inSync
            ? "CIMNotebook: the workspace schema changed since it was sent to RDFArchitect."
            : "CIMNotebook: this workspace's schema is not in RDFArchitect yet.";
        const choice = await vscode.window.showInformationMessage(
            message,
            accept,
            "Not now",
            "Never for this workspace",
        );
        if (choice === accept) {
            await sendSchemaToRdfArchitect(termIri);
        } else if (choice === "Never for this workspace") {
            await extensionContext.workspaceState.update(HANDOFF_OPT_OUT_KEY, true);
        }
    } catch (err) {
        // Never let the offer break opening the panel.
        out.appendLine(`Schema sync check failed: ${err instanceof Error ? err.message : err}`);
    }
}

/**
 * Whether what was sent is still over there — which differs by how it was sent: a snapshot has to
 * still load (loading it into this throwaway session is the probe), while a dataset sent into the
 * connected window has to still be in that window's session.
 */
async function handoffIsAlive(base: string, handoff: SchemaHandoff): Promise<boolean> {
    try {
        const api = `${new URL(base).toString().replace(/\/+$/, "")}/api`;
        if (handoff.snapshot) {
            const res = await fetch(`${api}/snapshots/${encodeURIComponent(handoff.snapshot)}`);
            return res.ok;
        }
        if (connectedSession?.url !== base) {
            return false;
        }
        const res = await fetch(`${api}/datasets`, {
            headers: { Cookie: `${RDFA_SESSION_COOKIE}=${connectedSession.id}` },
        });
        return res.ok && ((await res.json()) as string[]).includes(handoff.dataset);
    } catch {
        return false;
    }
}

/** Identifies a set of schema files by their paths and contents, to detect edits since the send. */
async function schemaFingerprint(files: string[]): Promise<string> {
    const hash = crypto.createHash("sha256");
    for (const file of [...files].sort()) {
        hash.update(file);
        hash.update(await fs.promises.readFile(file));
    }
    return hash.digest("hex");
}

/**
 * Minimal client for the RDFArchitect REST API. RDFArchitect scopes datasets to the backend
 * session (`RDFA_SESSION_ID` cookie), so the cookie returned by the first response is replayed on
 * every subsequent request to keep all calls in one session.
 */
class RdfArchitectClient {
    private readonly api: string;
    private readonly cookies = new Map<string, string>();

    /**
     * @param sessionId a session to work in (the embedded view's), instead of a fresh one of our
     *     own — what puts an imported dataset where the user can actually see and edit it
     */
    constructor(base: string, sessionId?: string) {
        this.api = `${new URL(base).toString().replace(/\/+$/, "")}/api`;
        if (sessionId) {
            this.cookies.set(RDFA_SESSION_COOKIE, sessionId);
        }
    }

    async importGraphs(dataset: string, files: string[]): Promise<void> {
        const form = new FormData();
        for (const file of files) {
            const data = await fs.promises.readFile(file);
            form.append("files", new Blob([data]), path.basename(file));
        }
        const res = await this.request(`/datasets/${encodeURIComponent(dataset)}/graphs/content`, {
            method: "PUT",
            body: form,
        });
        const result = (await res.json()) as { failedImports?: string[] };
        if (result.failedImports?.length) {
            throw new Error(`RDFArchitect could not parse: ${result.failedImports.join(", ")}`);
        }
    }

    /** Marks the dataset read-only (DELETE on the readonly resource disables editing). */
    async disableEditing(dataset: string): Promise<void> {
        await this.request(`/datasets/${encodeURIComponent(dataset)}/readonly`, {
            method: "DELETE",
        });
    }

    async createSnapshot(dataset: string): Promise<string> {
        const res = await this.request("/snapshots", { method: "POST", body: dataset });
        return (await res.text()).trim();
    }

    private async request(pathname: string, init: RequestInit): Promise<Response> {
        const headers = new Headers(init.headers);
        if (this.cookies.size > 0) {
            headers.set(
                "Cookie",
                [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join("; "),
            );
        }
        const res = await fetch(this.api + pathname, { ...init, headers });
        for (const setCookie of res.headers.getSetCookie()) {
            const [pair] = setCookie.split(";");
            const eq = pair.indexOf("=");
            if (eq > 0) {
                this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
            }
        }
        if (!res.ok) {
            const body = await res.text().catch(() => "");
            throw new Error(
                `${init.method} ${pathname} → HTTP ${res.status}${body ? ` — ${body.slice(0, 200)}` : ""}`,
            );
        }
        return res;
    }
}

/** RDFArchitect's term deep link: {@code <base>/mainpage?class=<iri>} takes any schema term. */

/**
 * Reveals the singleton RDFArchitect panel. With {@code navigate}, the embedded app is (re)loaded
 * at {@code url}; otherwise an already-open panel keeps its current page and session.
 *
 * Opening the panel for the first time also offers to send the workspace schema (see
 * {@link offerSchemaSync}) — the offer runs detached so it never delays the panel.
 *
 * @param base the configured instance URL, without the deep-link parameters {@code url} may carry
 * @param termIri a term the user was navigating to, handed to the offer so an import can land there
 */
function showRdfArchitectPanel(
    rawBase: string,
    url: string,
    navigate: boolean,
    termIri?: string,
): void {
    // The single point where an instance becomes *this panel's* instance, and so the spelling the
    // reported session is filed under. Callers hand in a base from three sources — the setting, the
    // language server, a definition document's header — that disagree about the trailing slash.
    const base = tolerantBaseUrl(rawBase);
    if (rdfArchitectPanel) {
        if (navigate) {
            rdfArchitectPanelBase = base;
            rdfArchitectPanel.webview.html = rdfArchitectHtml(url);
        }
        rdfArchitectPanel.reveal();
        return;
    }
    rdfArchitectPanelBase = base;
    void offerSchemaSync(base, termIri);
    const panel = vscode.window.createWebviewPanel(
        "cimnotebook.rdfArchitect",
        "RDFArchitect",
        vscode.ViewColumn.Beside,
        {
            enableScripts: true,
            // Keep the embedded app (and its in-memory session) alive while the tab is hidden.
            retainContextWhenHidden: true,
        },
    );
    panel.webview.html = rdfArchitectHtml(url);
    panel.webview.onDidReceiveMessage((msg: { command?: string; url?: string; id?: string }) => {
        if (msg.command === "openExternal" && msg.url) {
            void vscode.env.openExternal(vscode.Uri.parse(msg.url));
        } else if (msg.command === "sendSchema") {
            void sendSchemaToRdfArchitect();
        } else if (msg.command === "session" && msg.id) {
            void connectRdfArchitectSession(rdfArchitectPanelBase ?? base, msg.id);
        } else if (msg.command === "sessionUnavailable") {
            void reportMissingSession(rdfArchitectPanelBase ?? base);
        }
    });
    panel.onDidDispose(() => {
        rdfArchitectPanel = undefined;
        rdfArchitectPanelBase = undefined;
    });
    rdfArchitectPanel = panel;
}

/** Opens the configured RDFArchitect instance in the system browser. */
async function openRdfArchitectExternal(): Promise<void> {
    const url = await resolveRdfArchitectUrl();
    if (url) {
        await vscode.env.openExternal(vscode.Uri.parse(url));
    }
}

/**
 * Returns the RDFArchitect base URL from settings, prompting for one (and persisting the answer)
 * when unset. Returns undefined when the user cancels or the URL is invalid.
 */
async function resolveRdfArchitectUrl(): Promise<string | undefined> {
    const config = vscode.workspace.getConfiguration("cimnotebook");
    let url = config.get<string>("rdfArchitectUrl", "").trim();
    if (!url) {
        const entered = await vscode.window.showInputBox({
            title: "RDFArchitect instance URL",
            prompt: "URL of a running RDFArchitect instance (saved to the cimnotebook.rdfArchitectUrl setting).",
            placeHolder: "http://localhost:3000",
            ignoreFocusOut: true,
        });
        if (!entered || !entered.trim()) {
            return undefined;
        }
        url = entered.trim();
        await config.update("rdfArchitectUrl", url, vscode.ConfigurationTarget.Global);
    }
    try {
        // Validates the URL and normalises it to the one spelling every base URL is compared in.
        return normalizeBaseUrl(url);
    } catch {
        vscode.window.showErrorMessage(
            `CIMNotebook: invalid RDFArchitect URL "${url}" — fix the cimnotebook.rdfArchitectUrl setting.`,
        );
        return undefined;
    }
}

/** Webview page: a slim toolbar (reload / open in browser) above an iframe hosting the app. */
function rdfArchitectHtml(url: string): string {
    const origin = new URL(url).origin;
    const nonce = Array.from({ length: 32 }, () =>
        "abcdefghijklmnopqrstuvwxyz0123456789".charAt(Math.floor(Math.random() * 36)),
    ).join("");
    return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; frame-src ${origin}; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
    <style>
        html, body { height: 100%; margin: 0; padding: 0; }
        body { display: flex; flex-direction: column; }
        #toolbar {
            display: flex; gap: 0.5em; align-items: center; padding: 2px 6px;
            font-family: var(--vscode-font-family); font-size: 0.85em;
            color: var(--vscode-descriptionForeground);
            background: var(--vscode-editorWidget-background);
            border-bottom: 1px solid var(--vscode-editorWidget-border, transparent);
        }
        #toolbar button {
            background: none; border: none; cursor: pointer; padding: 2px 6px;
            color: var(--vscode-textLink-foreground); font-size: inherit;
        }
        #toolbar button:hover { text-decoration: underline; }
        #app { flex: 1; border: none; width: 100%; }
    </style>
</head>
<body>
    <div id="toolbar">
        <span>${origin}</span>
        <button id="sendSchema">Send Schema</button>
        <button id="reload">Reload</button>
        <button id="external">Open in Browser</button>
    </div>
    <iframe id="app" src="${url}" allow="clipboard-read; clipboard-write"></iframe>
    <script nonce="${nonce}">
        const vscodeApi = acquireVsCodeApi();
        const appOrigin = ${JSON.stringify(origin)};
        // Datasets belong to the embedded app's backend session, so the language server needs that
        // session to read them. The app answers this request when its deployment allows it
        // (PUBLIC_EMBED_SESSION_HANDSHAKE); we retry because it may not be listening yet.
        let sessionAsked = 0;
        const askForSession = () => {
            const frame = document.getElementById("app");
            if (sessionAsked > 20) {
                vscodeApi.postMessage({ command: "sessionUnavailable" });
                return;
            }
            sessionAsked++;
            // The frame may have no window yet; that is a reason to come back, not to give up —
            // returning here once would end the handshake for good, and silently, since the
            // "unavailable" report is on the retry path too.
            if (frame.contentWindow) {
                frame.contentWindow.postMessage({ type: "rdfa:session-request" }, appOrigin);
            }
            setTimeout(askForSession, 500);
        };
        window.addEventListener("message", event => {
            if (event.origin === appOrigin && event.data && event.data.type === "rdfa:session") {
                sessionAsked = Infinity; // answered; stop asking
                vscodeApi.postMessage({ command: "session", id: event.data.id });
            }
        });
        document.getElementById("app").addEventListener("load", () => {
            sessionAsked = 0;
            askForSession();
        });
        document.getElementById("reload").addEventListener("click", () => {
            const frame = document.getElementById("app");
            frame.src = frame.src;
        });
        document.getElementById("external").addEventListener("click", () => {
            vscodeApi.postMessage({ command: "openExternal", url: ${JSON.stringify(url)} });
        });
        document.getElementById("sendSchema").addEventListener("click", () => {
            vscodeApi.postMessage({ command: "sendSchema" });
        });
    </script>
</body>
</html>`;
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
        // The build date pins down which server is answering — a jar packaged before an extension
        // change looks exactly like a feature that does not work.
        out.appendLine(`[jar] Found ✓ (built ${fs.statSync(bundled).mtime.toISOString()})`);
        return bundled;
    }
    out.appendLine("[jar] NOT FOUND");

    return undefined;
}

function buildClient(serverJar: string, context: vscode.ExtensionContext): LanguageClient {
    const config = vscode.workspace.getConfiguration("cimnotebook");
    const javaExe = config.get<string>("javaExecutable", "java");
    const extraArgs = config.get<string[]>("javaArgs", []);
    launchedTrustArgs = systemTrustJavaArgs(extraArgs);
    const args = [...launchedTrustArgs, ...extraArgs, "-jar", serverJar];

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
