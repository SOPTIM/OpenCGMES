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
 * Editor-independent notebook cell model shared by the markdown and .sparqlbook
 * serializers. Deliberately free of any `vscode` import so the format logic can be
 * unit-tested with plain `node --test`.
 */

/** Cell languages that CIMNotebook treats as executable code cells. */
export const CODE_CELL_LANGUAGES = ["sparql", "shacl"] as const;

export interface RawCell {
    kind: "markdown" | "code";
    /** Language id for code cells (usually "sparql" or "shacl"); absent for markdown. */
    language?: string;
    value: string;
    /** Opaque per-cell metadata (only round-tripped by the .sparqlbook format). */
    metadata?: Record<string, unknown>;
}

/** A parsed notebook: its cells plus document-level facts needed for faithful saving. */
export interface RawNotebook {
    cells: RawCell[];
    /** Line ending of the source file, preserved on serialization. */
    eol: "\n" | "\r\n";
}
