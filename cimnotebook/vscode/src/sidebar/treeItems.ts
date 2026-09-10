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
 * Pure label/description builders and field validators for the three configuration tree
 * views (`treeViews.ts`). Kept dependency-free of `vscode` — unlike the tree providers
 * themselves, which build `vscode.TreeItem`s and register commands — so this is the part
 * that gets node tests, mirroring `endpoint.ts` next to `endpointCommands.ts`.
 */

import { classifyDirective } from "../notebook/endpoint";
import { ConnectionModel } from "./configModel";

// ---- connections --------------------------------------------------------------------------

export function connectionLabel(connection: ConnectionModel): string {
    return connection.name;
}

/**
 * URL plus "· basic auth" / "· default" markers. Plain words, not `$(...)` codicons —
 * `TreeItem.description` is rendered as literal text, unlike QuickPick labels.
 */
export function connectionDescription(connection: ConnectionModel): string {
    const parts = [connection.url];
    if (connection.authType === "basic") {
        parts.push("· basic auth");
    }
    if (connection.default) {
        parts.push("· default");
    }
    return parts.join(" ");
}

export function connectionIcon(connection: ConnectionModel): string {
    return connection.default ? "star-full" : "plug";
}

/**
 * Space-separated facets for the item's `contextValue`, matched by `viewItem =~ /.../ `
 * `when` clauses in `package.json` (e.g. gating the credentials action to basic auth).
 */
export function connectionContextValue(connection: ConnectionModel): string {
    const parts = ["connection"];
    if (connection.authType === "basic") {
        parts.push("basic");
    }
    if (connection.default) {
        parts.push("default");
    }
    return parts.join(" ");
}

/**
 * A connection name must be non-empty, contain no whitespace (names are written into
 * `# [endpoint=...]` directives, whose value ends at the first whitespace), classify as
 * a *name* under `classifyDirective` in `endpoint.ts` (no `.`/`/`/`\`, not a URL — the
 * single source of truth for how a directive is read), and be unique among the other
 * connections. `currentName` excludes the connection being edited from the uniqueness
 * check.
 */
export function validateConnectionName(
    name: string,
    existingNames: string[],
    currentName?: string,
): string | undefined {
    const trimmed = name.trim();
    if (trimmed === "") {
        return "Enter a connection name.";
    }
    if (/\s/.test(trimmed)) {
        return "Connection names can't contain whitespace (they're used in # [endpoint=…] directives).";
    }
    if (classifyDirective(trimmed) !== "name") {
        return "Connection names can't contain '.', '/', or '\\' (that would look like a file path).";
    }
    if (existingNames.some((existing) => existing !== currentName && existing === trimmed)) {
        return `"${trimmed}" is already used by another connection.`;
    }
    return undefined;
}

const HTTP_URL = /^https?:\/\/\S+$/i;

export function validateRequiredUrl(url: string): string | undefined {
    return HTTP_URL.test(url.trim()) ? undefined : "Enter an http(s):// URL.";
}

/** Empty is allowed (the field is optional and gets derived); anything else must be a URL. */
export function validateOptionalUrl(url: string): string | undefined {
    const trimmed = url.trim();
    if (trimmed === "") {
        return undefined;
    }
    return HTTP_URL.test(trimmed) ? undefined : "Enter an http(s):// URL, or leave empty.";
}

// ---- validation section --------------------------------------------------------------------

export interface LevelOption {
    value: string;
    detail: string;
}

/** The `cimvocabcheck.strictness` levels, in the schema's declared order. */
export const STRICTNESS_LEVELS: LevelOption[] = [
    {
        value: "permissive",
        detail: "Only unknown-term and syntax errors; semantic checks and hints are suppressed.",
    },
    { value: "default", detail: "All checks, original severities. Only errors fail validation." },
    { value: "strict", detail: "All checks; warnings are promoted to errors." },
    { value: "pedantic", detail: "All checks; warnings and hints are promoted to errors." },
];

/** The `cimvocabcheck.standardVocabulary` modes. */
export const STANDARD_VOCABULARY_OPTIONS: LevelOption[] = [
    { value: "check", detail: "Typos in rdf/rdfs/owl/sh terms are reported as errors." },
    { value: "ignore", detail: "Terms in these namespaces are accepted without inspection." },
];

/** Effective strictness: the config's own default value doubles as the "unset" display. */
export function strictnessDescription(value: string | undefined): string {
    const trimmed = value?.trim();
    return trimmed ? trimmed : "default";
}

/** The effective mode ("check" when unset), without the "(default)" annotation. */
export function effectiveStandardVocabulary(value: string | undefined): string {
    const trimmed = value?.trim();
    return trimmed ? trimmed : "check";
}

export function standardVocabularyDescription(value: string | undefined): string {
    const effective = effectiveStandardVocabulary(value);
    return value?.trim() ? effective : `${effective} (default)`;
}

/**
 * There is no bundled default schema and no implicit directory: with the directory unset,
 * validation runs on the listed schema files alone — or on the model held in RDFArchitect when
 * one is named, or syntax-only when there is neither.
 */
export function schemasDirectoryDescription(
    value: string | undefined,
    schemaFileCount: number,
    rdfArchitect?: string,
): string {
    const trimmed = value?.trim();
    if (trimmed) {
        return trimmed;
    }
    if (schemaFileCount > 0) {
        return "not set (schema files below are used)";
    }
    return rdfArchitect?.trim()
        ? "not set (the RDFArchitect model below is used)"
        : "not set (validation is syntax-only)";
}

/**
 * The `rdfArchitect` row: a dataset name is read from the RDFArchitect view open in the IDE, a
 * link (`?dataset=` or `?snapshot=`) from the instance it names — which the description says,
 * because the difference decides whether the feature works without the view.
 */
export function rdfArchitectDescription(value: string | undefined): string {
    const trimmed = value?.trim();
    if (!trimmed) {
        return "not set";
    }
    return trimmed.includes("://") ? `${trimmed} (link)` : `${trimmed} (dataset in the open view)`;
}

/** Selecting the schema's own default value clears the field, keeping the file minimal. */
export function strictnessValueToWrite(picked: string): string | undefined {
    return picked === "default" ? undefined : picked;
}

export function standardVocabularyValueToWrite(picked: string): string | undefined {
    return picked === "check" ? undefined : picked;
}

// ---- execution section ---------------------------------------------------------------------

export function numberSettingDescription(value: number | undefined, defaultValue: number): string {
    return value !== undefined ? String(value) : `${defaultValue} (default)`;
}

/** `showInputBox` validator for the integer notebook settings (empty clears the field). */
export function validatePositiveIntegerOrEmpty(input: string): string | undefined {
    if (input.trim() === "") {
        return undefined;
    }
    return /^\d+$/.test(input.trim()) && Number(input.trim()) > 0
        ? undefined
        : "Enter a positive whole number, or leave empty to reset to the default.";
}
