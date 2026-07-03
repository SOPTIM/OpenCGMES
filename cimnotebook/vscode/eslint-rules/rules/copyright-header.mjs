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

import {
    EXPECTED_NORMALIZED,
    buildBlockComment,
    normalizeCommentBody,
} from "../utils/copyright.mjs";

// Lines allowed to precede the header (kept at the very top of the file):
// shebangs and TypeScript triple-slash / directive comments.
const PREFIX_LINE = /^(#!.*|\/\/\s*@ts-.*|\/\/\/\s*<reference.*)$/;

/**
 * ESLint rule enforcing the SOPTIM Apache-2.0 block-comment header at the top of
 * every JavaScript/TypeScript source file. Auto-fixable via `eslint --fix`.
 */
const copyrightHeaderRule = {
    meta: {
        type: "problem",
        docs: {
            description:
                "Require the SOPTIM Apache-2.0 copyright header at the top of JS/TS files.",
            recommended: false,
        },
        fixable: "code",
        schema: [],
    },
    create(context) {
        return {
            Program() {
                const text = context.sourceCode.getText();
                const prefixLen = leadingPrefixLength(text);
                const after = text.slice(prefixLen);
                const leadingWhitespace = after.match(/^\s*/)[0].length;
                const comment = matchLeadingBlockComment(after);

                if (!comment) {
                    context.report({
                        loc: { line: 1, column: 0 },
                        message:
                            "Add the SOPTIM Apache-2.0 copyright header at the top of the file.",
                        fix(fixer) {
                            return fixer.replaceTextRange(
                                [prefixLen, prefixLen + leadingWhitespace],
                                `${buildBlockComment()}\n\n`,
                            );
                        },
                    });
                    return;
                }

                if (normalizeCommentBody(comment.text) === EXPECTED_NORMALIZED) {
                    return;
                }

                context.report({
                    loc: { line: 1, column: 0 },
                    message:
                        "Update the leading block comment to match the SOPTIM Apache-2.0 copyright header.",
                    fix(fixer) {
                        return fixer.replaceTextRange(
                            [prefixLen + comment.start, prefixLen + comment.end],
                            buildBlockComment(),
                        );
                    },
                });
            },
        };
    },
};

function leadingPrefixLength(text) {
    const lines = text.split("\n");
    let consumed = 0;
    let offset = 0;
    while (consumed < lines.length && PREFIX_LINE.test(lines[consumed])) {
        offset += lines[consumed].length + 1;
        consumed += 1;
    }
    return offset;
}

function matchLeadingBlockComment(text) {
    const match = text.match(/^(\s*)(\/\*[\s\S]*?\*\/)/);
    if (!match) return null;
    const start = match[1].length;
    return { text: match[2], start, end: start + match[2].length };
}

export default copyrightHeaderRule;
