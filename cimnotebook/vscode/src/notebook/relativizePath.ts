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
 * Turns an absolute filesystem path into the relative, forward-slash, `./`-prefixed form
 * used for `# [endpoint=...]` directives and `opencgmes.jsonc` path fields — both are
 * resolved relative to a base directory (the notebook's folder or the config file's
 * folder) and always use `/` even on Windows. Pure (only node's `path`), so it is used
 * both by the vscode-facing file pickers and directly in node tests.
 */

import * as path from "path";

/** The subset of node's `path` used here — injectable so tests can exercise win32 rules. */
export type PathImpl = Pick<typeof path, "relative" | "isAbsolute" | "sep">;

export function relativizePath(fromDir: string, toPath: string, impl: PathImpl = path): string {
    const relative = impl.relative(fromDir, toPath);
    if (impl.isAbsolute(relative)) {
        // No relative form exists (Windows: base and target on different drives) —
        // keep the absolute path rather than fabricating a broken "./C:/..." one.
        return relative.split(impl.sep).join("/");
    }
    const slashed = relative.split(impl.sep).join("/");
    if (slashed === "") {
        return ".";
    }
    return slashed.startsWith(".") ? slashed : `./${slashed}`;
}
