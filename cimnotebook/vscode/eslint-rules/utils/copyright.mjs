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

/*
 * Single source of truth for the SOPTIM Apache-2.0 license header used by the
 * local `copyright-header` ESLint rule. The rendered block comment is byte-for-byte
 * identical to what the Maven (mycila / Spotless) and IntelliJ (Spotless) gates
 * produce, so every first-party file in the repo carries the same header.
 */

// Notice lines without comment markers. An empty string renders a blank comment
// line; the license URL is indented four extra spaces on purpose.
const NOTICE_LINES = [
    "Copyright (c) 2026 SOPTIM AG",
    "",
    'Licensed under the Apache License, Version 2.0 (the "License");',
    "you may not use this file except in compliance with the License.",
    "You may obtain a copy of the License at",
    "",
    "    http://www.apache.org/licenses/LICENSE-2.0",
    "",
    "Unless required by applicable law or agreed to in writing, software",
    'distributed under the License is distributed on an "AS IS" BASIS,',
    "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
    "See the License for the specific language governing permissions and",
    "limitations under the License.",
    "",
    "SPDX-License-Identifier: Apache-2.0",
];

function formatLine(line) {
    return line.trim() === "" ? " *" : ` *    ${line}`;
}

/** The exact block comment the rule inserts / expects. */
export function buildBlockComment() {
    return `/*\n${NOTICE_LINES.map(formatLine).join("\n")}\n */`;
}

function trimBlankEdges(lines) {
    let start = 0;
    let end = lines.length;
    while (start < end && lines[start].trim() === "") start += 1;
    while (end > start && lines[end - 1].trim() === "") end -= 1;
    return lines.slice(start, end);
}

/**
 * Normalize a block-comment body to its bare text (markers + indentation
 * stripped) so the check tolerates cosmetic whitespace differences (e.g. a
 * header inserted by the IntelliJ IDE) while still catching wrong/stale text.
 */
export function normalizeCommentBody(body) {
    const lines = body.replaceAll("\r", "").split("\n");
    const stripped = lines.map((line) => line.replace(/^\s*\*?\s?/, "").trim());
    return trimBlankEdges(stripped).join("\n");
}

/** The normalized text every leading header is compared against. */
export const EXPECTED_NORMALIZED = normalizeCommentBody(buildBlockComment());
