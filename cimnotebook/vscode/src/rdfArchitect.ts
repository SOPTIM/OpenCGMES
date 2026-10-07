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
 * The parts of the RDFArchitect integration that are decided by strings alone: the links the
 * extension builds, the names it agrees on with RDFArchitect and with the language server, and the
 * term links the language server answers go-to-definition with.
 *
 * Kept free of `vscode` imports so it can be unit-tested in plain Node.
 */

import * as path from "path";

/**
 * The URI scheme of the language server's go-to-definition links into RDFArchitect:
 * `rdfarchitect://term/<payload>/<profile>/<name>`. Nothing backs them on disk.
 */
export const RDFA_TERM_SCHEME = "rdfarchitect";

/**
 * One spelling for one instance, so two base URLs can be compared.
 *
 * A base URL reaches the extension from three places that disagree about the trailing slash: the
 * `cimnotebook.rdfArchitectUrl` setting as typed, `new URL(...).toString()` (which *adds* one to a
 * bare origin), and the language server, whose `RdfArchitectSource` strips them. The session the
 * embedded view reports is paired with one of those spellings and then compared against another —
 * so without this, a connected session can go unrecognised and a schema meant for it is handed
 * over as a read-only snapshot instead. Stripping trailing slashes matches what the server does.
 *
 * @throws TypeError if the value is not a URL
 */
export function normalizeBaseUrl(url: string): string {
    return new URL(url.trim()).toString().replace(/\/+$/, "");
}

/**
 * RDFArchitect's deep link for a term. Every kind of term uses the `class` parameter: a class opens
 * itself, an attribute, association or enum entry opens the class declaring it.
 *
 * `dataset` and `graph` narrow the lookup — a term is routinely declared in several profiles, and
 * without them RDFArchitect opens whichever graph of whichever dataset it finds it in first.
 */
export function termDeepLink(base: string, iri: string, dataset?: string, graph?: string): string {
    const url = new URL(base);
    url.pathname = `${url.pathname.replace(/\/+$/, "")}/mainpage`;
    url.searchParams.set("class", iri);
    if (dataset) {
        url.searchParams.set("dataset", dataset);
    }
    if (graph) {
        url.searchParams.set("graph", graph);
    }
    return url.toString();
}

/**
 * Dataset name for an imported schema: the config file's directory name, sanitised.
 *
 * A relative path has no directory to name it after — `path.dirname` answers "." — and a dataset
 * called "." is not a name anybody asked for. The IntelliJ plugin resolves the same case to the
 * fallback, and the two should not disagree about what a workspace is called.
 */
export function datasetNameFor(configFile: string): string {
    const dir = path.basename(path.dirname(configFile));
    if (dir === "" || dir === "." || dir === "..") {
        return "cimnotebook";
    }
    return dir.replace(/[^A-Za-z0-9._-]/g, "_");
}

/**
 * The name RDFArchitect gives a snapshot once it is loaded, which is what the address bar shows and
 * what the language server parses back into a snapshot token — the two have to agree.
 */
export function snapshotDatasetName(dataset: string, token: string): string {
    return `SNAPSHOT_${dataset}_${token}`;
}

/**
 * The fields of a term link's path — `class`, and where known `base`, `dataset` and `graph` — or
 * undefined when it is not one. The payload is the language server's percent-encoded `key=value`
 * directive, Base64url-encoded so that decoding the URI cannot tear an encoded value apart; both
 * sides have to read it the same way.
 */
export function parseTermLink(linkPath: string): Map<string, string> | undefined {
    const payload = linkPath.split("/")[1];
    if (!payload || !/^[A-Za-z0-9_-]+$/.test(payload)) {
        return undefined;
    }
    const directive = Buffer.from(payload, "base64url").toString("utf8");
    const fields = new Map<string, string>();
    for (const pair of directive.trim().split(" ")) {
        const eq = pair.indexOf("=");
        if (eq > 0) {
            fields.set(pair.slice(0, eq), decodeURIComponent(pair.slice(eq + 1)));
        }
    }
    return fields.has("class") ? fields : undefined;
}

/**
 * The dataset names in a `GET /api/datasets` response. Current instances list each dataset as a
 * `{name, prefixes, readOnly}` object; older ones served bare strings.
 */
export function datasetNames(listing: unknown): string[] {
    if (!Array.isArray(listing)) {
        return [];
    }
    return listing
        .map((entry: unknown) =>
            typeof entry === "string"
                ? entry
                : typeof entry === "object" && entry !== null && "name" in entry
                  ? String((entry as { name: unknown }).name)
                  : "",
        )
        .filter((name) => name !== "");
}

/** The part of an IRI after its last `#` or `/`. */
export function localNameOf(iri: string): string {
    return iri.split(/[#/]/).pop() || iri;
}
