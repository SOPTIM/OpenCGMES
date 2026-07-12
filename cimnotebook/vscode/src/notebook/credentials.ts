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
 * Credentials for `authType: "basic"` connections. Stored exclusively in VS Code
 * SecretStorage (key `cimnotebook.auth.<connectionName>`) — never in `opencgmes.jsonc`,
 * never in settings, never in git. They leave the client only inside an execute request
 * to the local language server, which turns them into the HTTP Authorization header.
 */

import * as vscode from "vscode";

import { ConnectionInfo, ExecAuth } from "./endpoint";

function secretKey(connectionName: string): string {
    return `cimnotebook.auth.${connectionName}`;
}

/**
 * The credentials to attach for a connection: stored ones when present, otherwise (when
 * `interactive`) a one-time prompt with an offer to save. Returns undefined for
 * connections without basic auth, or when the user cancels the prompt — execution then
 * proceeds anonymously and surfaces the endpoint's 401 as AUTH_FAILED.
 */
export async function authFor(
    secrets: vscode.SecretStorage,
    connection: ConnectionInfo,
    interactive: boolean,
): Promise<ExecAuth | undefined> {
    if (connection.authType?.toLowerCase() !== "basic") {
        return undefined;
    }
    const stored = await secrets.get(secretKey(connection.name));
    if (stored) {
        try {
            const parsed = JSON.parse(stored) as { username: string; password: string };
            return { type: "basic", username: parsed.username, password: parsed.password };
        } catch {
            await secrets.delete(secretKey(connection.name));
        }
    }
    if (!interactive) {
        return undefined;
    }
    const entered = await promptForCredentials(connection.name);
    if (!entered) {
        return undefined;
    }
    const save = await vscode.window.showQuickPick(["Yes", "No"], {
        title: `Save credentials for "${connection.name}" in VS Code secret storage?`,
    });
    if (save === "Yes") {
        await storeCredentials(secrets, connection.name, entered);
    }
    return entered;
}

/** Prompts for and stores credentials (the *Set Connection Credentials* command). */
export async function setCredentials(
    secrets: vscode.SecretStorage,
    connectionName: string,
): Promise<boolean> {
    const entered = await promptForCredentials(connectionName);
    if (!entered) {
        return false;
    }
    await storeCredentials(secrets, connectionName, entered);
    return true;
}

/** Deletes stored credentials (the *Clear Connection Credentials* command). */
export async function clearCredentials(
    secrets: vscode.SecretStorage,
    connectionName: string,
): Promise<void> {
    await secrets.delete(secretKey(connectionName));
}

async function promptForCredentials(connectionName: string): Promise<ExecAuth | undefined> {
    const username = await vscode.window.showInputBox({
        title: `Username for connection "${connectionName}"`,
        ignoreFocusOut: true,
    });
    if (username === undefined || username === "") {
        return undefined;
    }
    const password = await vscode.window.showInputBox({
        title: `Password for "${username}" @ "${connectionName}"`,
        password: true,
        ignoreFocusOut: true,
    });
    if (password === undefined) {
        return undefined;
    }
    return { type: "basic", username, password };
}

async function storeCredentials(
    secrets: vscode.SecretStorage,
    connectionName: string,
    auth: ExecAuth,
): Promise<void> {
    await secrets.store(
        secretKey(connectionName),
        JSON.stringify({ username: auth.username, password: auth.password }),
    );
}
