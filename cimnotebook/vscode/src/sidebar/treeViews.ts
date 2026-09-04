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
 * The three native configuration tree views (replacing the M6 webview form): one
 * collapsible section each for connections, validation settings, and notebook execution
 * settings, all editing the workspace's `opencgmes.jsonc` through {@link updateConfig}.
 *
 * Each provider reads the config once per refresh in `getChildren` and embeds everything
 * an item needs into its node, so `getTreeItem` is synchronous — no per-item re-reads.
 * Every mutating command resolves the config once up front (`readConfig`) and threads
 * that target into `updateConfig`, so a focus change during a wizard can't redirect the
 * write; the file text itself is still re-read at write time, so concurrent hand-edits
 * are never clobbered by a stale in-memory copy. Connection and schema-file nodes carry
 * their list *index*, so rows with duplicate names/paths affect exactly the clicked
 * entry.
 *
 * Item labels/descriptions and field validation are pure helpers in `treeItems.ts`; this
 * file is the thin `vscode.TreeDataProvider` wiring plus the command handlers (wizards,
 * QuickPicks) that drive them.
 */

import * as vscode from "vscode";

import { ConnectionStore } from "../notebook/connections";
import { runCredentialsAction } from "../notebook/credentials";
import { pickWorkspaceFiles, pickWorkspaceFolder } from "../notebook/filePicker";
import { ConnectionModel } from "./configModel";
import { openConfig, readConfig, targetConfig, updateConfig } from "./configEditor";
import {
    connectionContextValue,
    connectionDescription,
    connectionIcon,
    connectionLabel,
    effectiveStandardVocabulary,
    numberSettingDescription,
    rdfArchitectDescription,
    schemasDirectoryDescription,
    standardVocabularyDescription,
    standardVocabularyValueToWrite,
    STANDARD_VOCABULARY_OPTIONS,
    strictnessDescription,
    strictnessValueToWrite,
    STRICTNESS_LEVELS,
    validateConnectionName,
    validateOptionalUrl,
    validatePositiveIntegerOrEmpty,
    validateRequiredUrl,
} from "./treeItems";

export const CONNECTIONS_VIEW_ID = "cimnotebook.connectionsView";
export const VALIDATION_VIEW_ID = "cimnotebook.validationView";
export const EXECUTION_VIEW_ID = "cimnotebook.executionView";

const HAS_CONFIG_CONTEXT = "cimnotebook.hasConfig";

const DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
const DEFAULT_MAX_ROWS = 10_000;

const ADD_CONNECTION_COMMAND = "cimnotebook.connections.add";
const EDIT_CONNECTION_COMMAND = "cimnotebook.connections.edit";
const REMOVE_CONNECTION_COMMAND = "cimnotebook.connections.remove";
const TOGGLE_DEFAULT_CONNECTION_COMMAND = "cimnotebook.connections.toggleDefault";
const CONNECTION_CREDENTIALS_COMMAND = "cimnotebook.connections.credentials";
const OPEN_CONFIG_COMMAND = "cimnotebook.config.openFile";
const EDIT_STRICTNESS_COMMAND = "cimnotebook.config.editStrictness";
const EDIT_STANDARD_VOCABULARY_COMMAND = "cimnotebook.config.editStandardVocabulary";
const EDIT_SCHEMAS_DIRECTORY_COMMAND = "cimnotebook.config.editSchemasDirectory";
const EDIT_RDF_ARCHITECT_COMMAND = "cimnotebook.config.editRdfArchitect";
const ADD_SCHEMA_FILE_COMMAND = "cimnotebook.config.addSchemaFile";
const REMOVE_SCHEMA_FILE_COMMAND = "cimnotebook.config.removeSchemaFile";
const EDIT_QUERY_TIMEOUT_COMMAND = "cimnotebook.config.editQueryTimeout";
const EDIT_MAX_ROWS_COMMAND = "cimnotebook.config.editMaxRows";

export function registerConfigTreeViews(
    context: vscode.ExtensionContext,
    store: ConnectionStore,
): void {
    const refreshEmitter = new vscode.EventEmitter<void>();
    context.subscriptions.push(refreshEmitter);

    // createTreeView (not registerTreeDataProvider) for the top section so the resolved
    // config file can be surfaced as the view description — the sections follow the
    // active document's nearest config, and the user should see which file they edit.
    const connectionsView = vscode.window.createTreeView(CONNECTIONS_VIEW_ID, {
        treeDataProvider: new ConnectionsProvider(refreshEmitter.event),
    });
    context.subscriptions.push(
        connectionsView,
        vscode.window.registerTreeDataProvider(
            VALIDATION_VIEW_ID,
            new ValidationProvider(refreshEmitter.event),
        ),
        vscode.window.registerTreeDataProvider(
            EXECUTION_VIEW_ID,
            new ExecutionProvider(refreshEmitter.event),
        ),
    );

    const refresh = async (): Promise<void> => {
        refreshEmitter.fire();
        const target = await targetConfig();
        connectionsView.description = target.exists
            ? vscode.workspace.asRelativePath(target.uri)
            : undefined;
        await vscode.commands.executeCommand("setContext", HAS_CONFIG_CONTEXT, target.exists);
    };
    context.subscriptions.push(
        store.onDidChange(() => void refresh()),
        vscode.window.onDidChangeActiveTextEditor(() => void refresh()),
        vscode.window.onDidChangeActiveNotebookEditor(() => void refresh()),
    );
    void refresh();

    registerCommands(context, refresh);
}

// ---- tree data providers --------------------------------------------------------------------

/** A connection row: the rendered entry plus its position in `connections` at render time. */
interface ConnectionNode {
    connection: ConnectionModel;
    index: number;
}

class ConnectionsProvider implements vscode.TreeDataProvider<ConnectionNode> {
    constructor(readonly onDidChangeTreeData: vscode.Event<void>) {}

    async getChildren(node?: ConnectionNode): Promise<ConnectionNode[]> {
        if (node) {
            return [];
        }
        const { model } = await readConfig();
        return (model.connections ?? []).map((connection, index) => ({ connection, index }));
    }

    getTreeItem(node: ConnectionNode): vscode.TreeItem {
        const item = new vscode.TreeItem(
            connectionLabel(node.connection),
            vscode.TreeItemCollapsibleState.None,
        );
        item.description = connectionDescription(node.connection);
        item.iconPath = new vscode.ThemeIcon(connectionIcon(node.connection));
        item.contextValue = connectionContextValue(node.connection);
        item.command = {
            title: "Edit Connection",
            command: EDIT_CONNECTION_COMMAND,
            arguments: [node],
        };
        return item;
    }
}

interface SchemaFileNode {
    kind: "schemaFile";
    file: string;
    /** Position in `schemas` at render time — removal targets exactly this occurrence. */
    index: number;
    openUri: vscode.Uri;
}

type ValidationNode =
    | { kind: "strictness"; description: string }
    | { kind: "standardVocabulary"; description: string }
    | { kind: "schemasDirectory"; description: string }
    | { kind: "rdfArchitect"; description: string }
    | { kind: "schemasParent"; files: SchemaFileNode[] }
    | SchemaFileNode;

class ValidationProvider implements vscode.TreeDataProvider<ValidationNode> {
    constructor(readonly onDidChangeTreeData: vscode.Event<void>) {}

    async getChildren(node?: ValidationNode): Promise<ValidationNode[]> {
        if (node) {
            return node.kind === "schemasParent" ? node.files : [];
        }
        const { model, uri, exists } = await readConfig();
        if (!exists) {
            // Empty tree → the view's viewsWelcome "Create Config File" prompt shows
            // instead of rows whose first edit would silently create a bare config.
            return [];
        }
        const files = (model.schemas ?? []).map((file, index): SchemaFileNode => ({
            kind: "schemaFile",
            file,
            index,
            openUri: vscode.Uri.joinPath(uri, "..", file),
        }));
        return [
            { kind: "strictness", description: strictnessDescription(model.strictness) },
            {
                kind: "standardVocabulary",
                description: standardVocabularyDescription(model.standardVocabulary),
            },
            {
                kind: "schemasDirectory",
                description: schemasDirectoryDescription(
                    model.schemasDirectory,
                    files.length,
                    model.rdfArchitect,
                ),
            },
            { kind: "schemasParent", files },
            { kind: "rdfArchitect", description: rdfArchitectDescription(model.rdfArchitect) },
        ];
    }

    getTreeItem(node: ValidationNode): vscode.TreeItem {
        switch (node.kind) {
            case "strictness":
                return leaf("Strictness", node.description, {
                    command: EDIT_STRICTNESS_COMMAND,
                    contextValue: "strictness",
                    icon: "settings-gear",
                });
            case "standardVocabulary":
                return leaf("Standard vocabulary check", node.description, {
                    command: EDIT_STANDARD_VOCABULARY_COMMAND,
                    contextValue: "standardVocabulary",
                    icon: "settings-gear",
                });
            case "schemasDirectory":
                return leaf("Schemas directory", node.description, {
                    command: EDIT_SCHEMAS_DIRECTORY_COMMAND,
                    contextValue: "schemasDirectory",
                    icon: "folder",
                });
            case "rdfArchitect":
                return leaf("RDFArchitect model", node.description, {
                    command: EDIT_RDF_ARCHITECT_COMMAND,
                    contextValue: "rdfArchitect",
                    icon: "type-hierarchy",
                });
            case "schemasParent": {
                const item = new vscode.TreeItem(
                    "Schema files",
                    vscode.TreeItemCollapsibleState.Expanded,
                );
                item.contextValue = "schemasParent";
                return item;
            }
            case "schemaFile": {
                const item = new vscode.TreeItem(node.file, vscode.TreeItemCollapsibleState.None);
                item.iconPath = new vscode.ThemeIcon("file");
                item.contextValue = "schemaFile";
                item.command = {
                    title: "Open Schema File",
                    command: "vscode.open",
                    arguments: [node.openUri],
                };
                return item;
            }
        }
    }
}

type ExecutionNode = { kind: "queryTimeoutSeconds" | "maxRows"; description: string };

class ExecutionProvider implements vscode.TreeDataProvider<ExecutionNode> {
    constructor(readonly onDidChangeTreeData: vscode.Event<void>) {}

    async getChildren(node?: ExecutionNode): Promise<ExecutionNode[]> {
        if (node) {
            return [];
        }
        const { model, exists } = await readConfig();
        if (!exists) {
            return [];
        }
        return [
            {
                kind: "queryTimeoutSeconds",
                description: numberSettingDescription(
                    model.queryTimeoutSeconds,
                    DEFAULT_QUERY_TIMEOUT_SECONDS,
                ),
            },
            {
                kind: "maxRows",
                description: numberSettingDescription(model.maxRows, DEFAULT_MAX_ROWS),
            },
        ];
    }

    getTreeItem(node: ExecutionNode): vscode.TreeItem {
        if (node.kind === "queryTimeoutSeconds") {
            return leaf("Query timeout (seconds)", node.description, {
                command: EDIT_QUERY_TIMEOUT_COMMAND,
                contextValue: "queryTimeoutSeconds",
            });
        }
        return leaf("Max rows / triples", node.description, {
            command: EDIT_MAX_ROWS_COMMAND,
            contextValue: "maxRows",
        });
    }
}

function leaf(
    label: string,
    description: string,
    options: { command: string; contextValue: string; icon?: string },
): vscode.TreeItem {
    const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
    item.description = description;
    item.contextValue = options.contextValue;
    if (options.icon) {
        item.iconPath = new vscode.ThemeIcon(options.icon);
    }
    item.command = { title: "Edit", command: options.command };
    return item;
}

// ---- commands --------------------------------------------------------------------------------

function registerCommands(context: vscode.ExtensionContext, refresh: () => Promise<void>): void {
    context.subscriptions.push(
        vscode.commands.registerCommand(ADD_CONNECTION_COMMAND, () =>
            addConnection().then(refresh),
        ),
        vscode.commands.registerCommand(EDIT_CONNECTION_COMMAND, (n?: ConnectionNode) =>
            editConnection(n).then(refresh),
        ),
        vscode.commands.registerCommand(REMOVE_CONNECTION_COMMAND, (n?: ConnectionNode) =>
            removeConnection(n).then(refresh),
        ),
        vscode.commands.registerCommand(TOGGLE_DEFAULT_CONNECTION_COMMAND, (n?: ConnectionNode) =>
            toggleDefaultConnection(n).then(refresh),
        ),
        vscode.commands.registerCommand(CONNECTION_CREDENTIALS_COMMAND, (n?: ConnectionNode) =>
            manageConnectionCredentials(context, n),
        ),
        vscode.commands.registerCommand(OPEN_CONFIG_COMMAND, () => openConfig()),
        vscode.commands.registerCommand(EDIT_STRICTNESS_COMMAND, () =>
            editStrictness().then(refresh),
        ),
        vscode.commands.registerCommand(EDIT_STANDARD_VOCABULARY_COMMAND, () =>
            editStandardVocabulary().then(refresh),
        ),
        vscode.commands.registerCommand(EDIT_SCHEMAS_DIRECTORY_COMMAND, () =>
            editSchemasDirectory().then(refresh),
        ),
        vscode.commands.registerCommand(EDIT_RDF_ARCHITECT_COMMAND, () =>
            editRdfArchitect().then(refresh),
        ),
        vscode.commands.registerCommand(ADD_SCHEMA_FILE_COMMAND, () =>
            addSchemaFile().then(refresh),
        ),
        vscode.commands.registerCommand(REMOVE_SCHEMA_FILE_COMMAND, (n?: SchemaFileNode) =>
            removeSchemaFile(n).then(refresh),
        ),
        vscode.commands.registerCommand(EDIT_QUERY_TIMEOUT_COMMAND, () =>
            editQueryTimeout().then(refresh),
        ),
        vscode.commands.registerCommand(EDIT_MAX_ROWS_COMMAND, () => editMaxRows().then(refresh)),
    );
}

// ---- connections ---------------------------------------------------------------------------

async function runConnectionWizard(
    existingNames: string[],
    prefill?: ConnectionModel,
): Promise<ConnectionModel | undefined> {
    const name = await vscode.window.showInputBox({
        title: prefill ? `Edit connection "${prefill.name}" — name` : "New connection — name",
        value: prefill?.name ?? "",
        placeHolder: "local-fuseki",
        validateInput: (v) => validateConnectionName(v, existingNames, prefill?.name),
    });
    if (name === undefined) {
        return undefined;
    }

    const url = await vscode.window.showInputBox({
        title: "Query URL",
        value: prefill?.url ?? "",
        placeHolder: "http://localhost:3030/ds/query",
        validateInput: validateRequiredUrl,
    });
    if (url === undefined) {
        return undefined;
    }

    const updateUrl = await vscode.window.showInputBox({
        title: "Update URL (optional — derived from the query URL when empty)",
        value: prefill?.updateUrl ?? "",
        validateInput: validateOptionalUrl,
    });
    if (updateUrl === undefined) {
        return undefined;
    }

    const shaclUrl = await vscode.window.showInputBox({
        title: "SHACL URL (optional — derived from the query URL when empty)",
        value: prefill?.shaclUrl ?? "",
        validateInput: validateOptionalUrl,
    });
    if (shaclUrl === undefined) {
        return undefined;
    }

    const authOptions =
        prefill?.authType === "basic" ? ["Basic auth", "None"] : ["None", "Basic auth"];
    const authPick = await vscode.window.showQuickPick(authOptions, { title: "Authentication" });
    if (authPick === undefined) {
        return undefined;
    }

    const defaultOptions = prefill?.default ? ["Yes", "No"] : ["No", "Yes"];
    const defaultPick = await vscode.window.showQuickPick(defaultOptions, {
        title: "Set as the workspace default connection?",
    });
    if (defaultPick === undefined) {
        return undefined;
    }

    return {
        name: name.trim(),
        url: url.trim(),
        updateUrl: updateUrl.trim() || undefined,
        shaclUrl: shaclUrl.trim() || undefined,
        authType: authPick === "Basic auth" ? "basic" : undefined,
        default: defaultPick === "Yes" ? true : undefined,
    };
}

/**
 * The clicked row's position in the (possibly changed) list: its render-time index when
 * the entry there still matches, else the first name match. Duplicate names — legal in a
 * hand-edited file — thus affect exactly the clicked entry, not every namesake.
 */
function connectionIndex(list: ConnectionModel[], node: ConnectionNode): number {
    if (list[node.index]?.name === node.connection.name) {
        return node.index;
    }
    return list.findIndex((c) => c.name === node.connection.name);
}

/** Makes `index` the single default connection; every other entry loses the flag. */
function makeSingleDefault(list: ConnectionModel[], index: number): void {
    list.forEach((c, i) => {
        c.default = i === index ? true : undefined;
    });
}

async function addConnection(): Promise<void> {
    const state = await readConfig();
    const existingNames = (state.model.connections ?? []).map((c) => c.name);
    const connection = await runConnectionWizard(existingNames);
    if (!connection) {
        return;
    }
    await updateConfig(state, (m) => {
        const list = [...(m.connections ?? [])];
        list.push(connection);
        if (connection.default) {
            makeSingleDefault(list, list.length - 1);
        }
        m.connections = list;
    });
}

async function editConnection(node?: ConnectionNode): Promise<void> {
    if (!node) {
        return;
    }
    const state = await readConfig();
    const existingNames = (state.model.connections ?? []).map((c) => c.name);
    const updated = await runConnectionWizard(existingNames, node.connection);
    if (!updated) {
        return;
    }
    await updateConfig(state, (m) => {
        const list = [...(m.connections ?? [])];
        const index = connectionIndex(list, node);
        if (index === -1) {
            return;
        }
        // Keep the raw-array position so applyConfigModel edits the entry in place instead of
        // treating the wizard result as a new connection.
        list[index] = { ...updated, rawIndex: list[index].rawIndex };
        if (updated.default) {
            makeSingleDefault(list, index);
        }
        m.connections = list;
    });
}

async function removeConnection(node?: ConnectionNode): Promise<void> {
    if (!node) {
        return;
    }
    const confirm = await vscode.window.showWarningMessage(
        `Remove connection "${node.connection.name}"?`,
        { modal: true },
        "Remove",
    );
    if (confirm !== "Remove") {
        return;
    }
    const state = await readConfig();
    await updateConfig(state, (m) => {
        const list = [...(m.connections ?? [])];
        const index = connectionIndex(list, node);
        if (index !== -1) {
            list.splice(index, 1);
        }
        m.connections = list;
    });
}

async function toggleDefaultConnection(node?: ConnectionNode): Promise<void> {
    if (!node) {
        return;
    }
    const makeDefault = !node.connection.default;
    const state = await readConfig();
    await updateConfig(state, (m) => {
        const list = [...(m.connections ?? [])];
        const index = connectionIndex(list, node);
        if (index === -1) {
            return;
        }
        if (makeDefault) {
            makeSingleDefault(list, index);
        } else {
            list[index].default = undefined;
        }
        m.connections = list;
    });
}

async function manageConnectionCredentials(
    context: vscode.ExtensionContext,
    node?: ConnectionNode,
): Promise<void> {
    if (!node) {
        return;
    }
    const name = node.connection.name;
    const action = await vscode.window.showQuickPick(["Set credentials", "Clear credentials"], {
        title: `Credentials for "${name}"`,
    });
    if (action !== undefined) {
        await runCredentialsAction(
            context.secrets,
            name,
            action === "Set credentials" ? "set" : "clear",
        );
    }
}

// ---- validation section ----------------------------------------------------------------------

async function editStrictness(): Promise<void> {
    const state = await readConfig();
    const current = strictnessDescription(state.model.strictness);
    const items = STRICTNESS_LEVELS.map((level) => ({
        label: level.value,
        description: level.value === current ? "Current" : undefined,
        detail: level.detail,
    }));
    const pick = await vscode.window.showQuickPick(items, { title: "Strictness" });
    if (!pick) {
        return;
    }
    await updateConfig(state, (m) => {
        m.strictness = strictnessValueToWrite(pick.label);
    });
}

async function editStandardVocabulary(): Promise<void> {
    const state = await readConfig();
    const current = effectiveStandardVocabulary(state.model.standardVocabulary);
    const items = STANDARD_VOCABULARY_OPTIONS.map((option) => ({
        label: option.value,
        description: option.value === current ? "Current" : undefined,
        detail: option.detail,
    }));
    const pick = await vscode.window.showQuickPick(items, { title: "Standard vocabulary check" });
    if (!pick) {
        return;
    }
    await updateConfig(state, (m) => {
        m.standardVocabulary = standardVocabularyValueToWrite(pick.label);
    });
}

async function editSchemasDirectory(): Promise<void> {
    const state = await readConfig();
    const configDir = vscode.Uri.joinPath(state.uri, "..");
    const choice = await vscode.window.showQuickPick(
        [
            { label: "$(folder-opened) Browse for a folder…", action: "browse" as const },
            {
                label: "$(discard) Clear (no schemas directory)",
                action: "clear" as const,
            },
            { label: "$(edit) Type a path…", action: "type" as const },
        ],
        { title: "Schemas directory" },
    );
    if (!choice) {
        return;
    }
    let value: string | undefined;
    if (choice.action === "browse") {
        value = await pickWorkspaceFolder({ baseUri: configDir, title: "Schemas directory" });
        if (value === undefined) {
            return;
        }
    } else if (choice.action === "type") {
        const typed = await vscode.window.showInputBox({
            title: "Schemas directory (relative to the config file)",
            value: state.model.schemasDirectory ?? "",
            placeHolder: "./schemas",
        });
        if (typed === undefined) {
            return;
        }
        value = typed;
    } else {
        value = "";
    }
    await updateConfig(state, (m) => {
        m.schemasDirectory = value?.trim() ? value.trim() : undefined;
    });
}

/**
 * Edits `cimvocabcheck.rdfArchitect`: the workspace validates against a model curated in
 * RDFArchitect instead of against schema files. A bare dataset name is read from the RDFArchitect
 * view open in the editor, as it is edited; a link pins a fixed source and needs no view.
 */
async function editRdfArchitect(): Promise<void> {
    const state = await readConfig();
    const typed = await vscode.window.showInputBox({
        title: "RDFArchitect model (dataset name, or a ?dataset=/?snapshot= link)",
        prompt: "Leave empty to validate against schema files instead.",
        value: state.model.rdfArchitect ?? "",
        placeHolder: "cgmes-3.0",
    });
    if (typed === undefined) {
        return;
    }
    await updateConfig(state, (m) => {
        m.rdfArchitect = typed.trim() ? typed.trim() : undefined;
    });
}

/** `./schemas/x.rdf` and `schemas/x.rdf` resolve to the same file — compare without `./`. */
function canonicalPath(p: string): string {
    return p.startsWith("./") ? p.slice(2) : p;
}

async function addSchemaFile(): Promise<void> {
    const state = await readConfig();
    const configDir = vscode.Uri.joinPath(state.uri, "..");
    const picked = await pickWorkspaceFiles({
        pattern: "**/*.{rdf,ttl,owl}",
        baseUri: configDir,
        title: "Add schema file(s)",
        canPickMany: true,
    });
    if (!picked || picked.length === 0) {
        return;
    }
    await updateConfig(state, (m) => {
        const merged = [...(m.schemas ?? [])];
        const seen = new Set(merged.map(canonicalPath));
        for (const file of picked) {
            if (!seen.has(canonicalPath(file))) {
                merged.push(file);
                seen.add(canonicalPath(file));
            }
        }
        m.schemas = merged;
    });
}

async function removeSchemaFile(node?: SchemaFileNode): Promise<void> {
    if (!node) {
        return;
    }
    const state = await readConfig();
    await updateConfig(state, (m) => {
        const list = [...(m.schemas ?? [])];
        // The render-time index when that entry is unchanged, else the first value match —
        // duplicates are removed one occurrence at a time.
        const index = list[node.index] === node.file ? node.index : list.indexOf(node.file);
        if (index !== -1) {
            list.splice(index, 1);
        }
        m.schemas = list;
    });
}

// ---- execution section ------------------------------------------------------------------------

async function editQueryTimeout(): Promise<void> {
    const state = await readConfig();
    const input = await vscode.window.showInputBox({
        title: `Query timeout, in seconds (empty resets to the default: ${DEFAULT_QUERY_TIMEOUT_SECONDS})`,
        value:
            state.model.queryTimeoutSeconds !== undefined
                ? String(state.model.queryTimeoutSeconds)
                : "",
        validateInput: validatePositiveIntegerOrEmpty,
    });
    if (input === undefined) {
        return;
    }
    await updateConfig(state, (m) => {
        m.queryTimeoutSeconds = input.trim() ? Number(input.trim()) : undefined;
    });
}

async function editMaxRows(): Promise<void> {
    const state = await readConfig();
    const input = await vscode.window.showInputBox({
        title: `Max rows / triples (empty resets to the default: ${DEFAULT_MAX_ROWS})`,
        value: state.model.maxRows !== undefined ? String(state.model.maxRows) : "",
        validateInput: validatePositiveIntegerOrEmpty,
    });
    if (input === undefined) {
        return;
    }
    await updateConfig(state, (m) => {
        m.maxRows = input.trim() ? Number(input.trim()) : undefined;
    });
}
