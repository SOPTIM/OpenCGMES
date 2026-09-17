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
 * The form model behind the configuration sidebar, and its mapping to/from
 * `opencgmes.jsonc` text. Pure module (no `vscode` import).
 *
 * Editing goes through jsonc-parser's `modify`/`applyEdits`, which patches individual
 * property paths: comments and formatting everywhere else in the file survive a save.
 * Only a value that actually changed is touched; a field cleared in the form removes
 * the property. Connections are diffed entry-by-entry and field-by-field (keyed by
 * {@link ConnectionModel.rawIndex}), so comments between entries, fields the form does
 * not know, and entries the lenient parse skips all survive an edit. The remaining
 * caveat: rewriting the *schemas* array replaces it wholesale, so comments inside that
 * one array are lost.
 */

import { applyEdits, modify, parse } from "jsonc-parser";

export interface ConnectionModel {
    name: string;
    url: string;
    updateUrl?: string;
    shaclUrl?: string;
    authType?: "none" | "basic";
    default?: boolean;
    /**
     * The entry's index in the raw `connections` JSON array, assigned by {@link parseConfigModel}
     * and used by {@link applyConfigModel} to edit that entry in place. Not necessarily the
     * position in {@link ConfigModel.connections}: the raw array can hold entries the lenient
     * parse skips (they stay in the file untouched). Absent on entries newly created by the UI.
     */
    rawIndex?: number;
}

export interface ConfigModel {
    // "cimvocabcheck" section
    strictness?: string;
    standardVocabulary?: string;
    schemasDirectory?: string;
    schemas?: string[];
    rdfArchitect?: string;
    // "cimnotebook" section
    queryTimeoutSeconds?: number;
    maxRows?: number;
    connections?: ConnectionModel[];
}

const FORMATTING = { formattingOptions: { insertSpaces: true, tabSize: 2 } };

/** Reads the sidebar-editable fields from config text (error-tolerant JSONC parse). */
export function parseConfigModel(text: string): ConfigModel {
    const root = (parse(text, [], { allowTrailingComma: true }) ?? {}) as Record<string, unknown>;
    const vocab = asObject(root["cimvocabcheck"]);
    const notebook = asObject(root["cimnotebook"]);
    return {
        strictness: asString(vocab["strictness"]),
        standardVocabulary: asString(vocab["standardVocabulary"]),
        schemasDirectory: asString(vocab["schemasDirectory"]),
        schemas: asStringArray(vocab["schemas"]),
        rdfArchitect: asString(vocab["rdfArchitect"]),
        queryTimeoutSeconds: asNumber(notebook["queryTimeoutSeconds"]),
        maxRows: asNumber(notebook["maxRows"]),
        connections: asConnections(notebook["connections"]),
    };
}

/**
 * Returns the config text with the model applied — property-path edits only, so
 * comments and formatting outside the changed values are preserved. Unchanged fields
 * produce no edit at all.
 */
export function applyConfigModel(text: string, model: ConfigModel): string {
    const before = parseConfigModel(text);
    let result = text.trim().length === 0 ? "{}\n" : text;

    const set = (path: (string | number)[], value: unknown, changed: boolean): void => {
        if (!changed) {
            return;
        }
        result = applyEdits(result, modify(result, path, value, FORMATTING));
    };

    set(
        ["cimvocabcheck", "strictness"],
        emptyToUndefined(model.strictness),
        emptyToUndefined(model.strictness) !== before.strictness,
    );
    set(
        ["cimvocabcheck", "standardVocabulary"],
        emptyToUndefined(model.standardVocabulary),
        emptyToUndefined(model.standardVocabulary) !== before.standardVocabulary,
    );
    set(
        ["cimvocabcheck", "schemasDirectory"],
        emptyToUndefined(model.schemasDirectory),
        emptyToUndefined(model.schemasDirectory) !== before.schemasDirectory,
    );
    set(
        ["cimvocabcheck", "schemas"],
        undefinedIfEmpty(model.schemas),
        !sameJson(undefinedIfEmpty(model.schemas), before.schemas),
    );
    set(
        ["cimvocabcheck", "rdfArchitect"],
        emptyToUndefined(model.rdfArchitect),
        emptyToUndefined(model.rdfArchitect) !== before.rdfArchitect,
    );
    set(
        ["cimnotebook", "queryTimeoutSeconds"],
        model.queryTimeoutSeconds,
        model.queryTimeoutSeconds !== before.queryTimeoutSeconds,
    );
    set(["cimnotebook", "maxRows"], model.maxRows, model.maxRows !== before.maxRows);
    result = applyConnections(result, model.connections ?? [], before.connections);
    return result;
}

/**
 * Applies connection changes as the narrowest possible edits. Entries are paired with the file's
 * raw array by {@link ConnectionModel.rawIndex} and diffed field-by-field, so everything the form
 * did not change — comments between entries, fields it does not know, entries the lenient parse
 * skipped — survives. Removals go highest-index-first (earlier indices stay valid), additions are
 * appended. Only when the file has no connections array at all is one written as a whole.
 */
function applyConnections(
    text: string,
    after: ConnectionModel[],
    beforeConnections: ConnectionModel[] | undefined,
): string {
    let result = text;
    const edit = (path: (string | number)[], value: unknown): void => {
        result = applyEdits(result, modify(result, path, value, FORMATTING));
    };

    if (beforeConnections === undefined) {
        const created = after.map(normalizeConnection);
        if (created.length > 0) {
            edit(["cimnotebook", "connections"], created);
        }
        return result;
    }

    const beforeByRaw = new Map<number, ConnectionModel>();
    for (const connection of beforeConnections) {
        if (connection.rawIndex !== undefined) {
            beforeByRaw.set(connection.rawIndex, connection);
        }
    }
    const keptRaw = new Set<number>();

    for (const connection of after) {
        const beforeEntry =
            connection.rawIndex !== undefined ? beforeByRaw.get(connection.rawIndex) : undefined;
        if (connection.rawIndex === undefined || beforeEntry === undefined) {
            continue; // an addition — appended below, after removals
        }
        keptRaw.add(connection.rawIndex);
        const beforeFields = editableFields(beforeEntry);
        const afterFields = editableFields(connection);
        for (const key of Object.keys(afterFields) as (keyof EditableFields)[]) {
            if (afterFields[key] !== beforeFields[key]) {
                edit(["cimnotebook", "connections", connection.rawIndex, key], afterFields[key]);
            }
        }
    }

    const removed = [...beforeByRaw.keys()].filter((i) => !keptRaw.has(i)).sort((a, b) => b - a);
    for (const rawIndex of removed) {
        edit(["cimnotebook", "connections", rawIndex], undefined);
    }

    for (const connection of after) {
        if (connection.rawIndex === undefined) {
            edit(["cimnotebook", "connections", -1], normalizeConnection(connection));
        }
    }
    return result;
}

type EditableFields = ReturnType<typeof editableFields>;

/** A connection's form-editable fields in their normalized written form, for field diffing. */
function editableFields(connection: ConnectionModel) {
    const normalized = normalizeConnection(connection);
    return {
        name: normalized.name,
        url: normalized.url,
        updateUrl: normalized.updateUrl,
        shaclUrl: normalized.shaclUrl,
        authType: normalized.authType,
        default: normalized.default,
    };
}

/** Drops empty/false optional fields so the written JSON stays minimal. */
function normalizeConnection(connection: ConnectionModel): ConnectionModel {
    const normalized: ConnectionModel = {
        name: connection.name.trim(),
        url: connection.url.trim(),
    };
    if (connection.updateUrl?.trim()) {
        normalized.updateUrl = connection.updateUrl.trim();
    }
    if (connection.shaclUrl?.trim()) {
        normalized.shaclUrl = connection.shaclUrl.trim();
    }
    if (connection.authType === "basic") {
        normalized.authType = "basic";
    }
    if (connection.default === true) {
        normalized.default = true;
    }
    return normalized;
}

// ---- lenient readers ---------------------------------------------------------------------

function asObject(value: unknown): Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value)
        ? (value as Record<string, unknown>)
        : {};
}

function asString(value: unknown): string | undefined {
    return typeof value === "string" ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
    return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function asStringArray(value: unknown): string[] | undefined {
    if (!Array.isArray(value)) {
        return undefined;
    }
    const strings = value.filter((v): v is string => typeof v === "string");
    return strings.length > 0 ? strings : undefined;
}

function asConnections(value: unknown): ConnectionModel[] | undefined {
    if (!Array.isArray(value)) {
        return undefined;
    }
    const connections: ConnectionModel[] = [];
    value.forEach((entry, rawIndex) => {
        const obj = asObject(entry);
        const name = asString(obj["name"]);
        const url = asString(obj["url"]);
        if (name === undefined || url === undefined) {
            return;
        }
        connections.push({
            name,
            url,
            updateUrl: asString(obj["updateUrl"]),
            shaclUrl: asString(obj["shaclUrl"]),
            authType: obj["authType"] === "basic" ? "basic" : undefined,
            default: obj["default"] === true ? true : undefined,
            rawIndex,
        });
    });
    return connections;
}

function emptyToUndefined(value: string | undefined): string | undefined {
    return value === undefined || value.trim() === "" ? undefined : value.trim();
}

function undefinedIfEmpty<T>(value: T[] | undefined): T[] | undefined {
    return value !== undefined && value.length > 0 ? value : undefined;
}

function sameJson(a: unknown, b: unknown): boolean {
    return JSON.stringify(a) === JSON.stringify(b);
}
