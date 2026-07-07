// @ts-check
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

import js from "@eslint/js";
import tseslint from "typescript-eslint";
import prettier from "eslint-config-prettier";

import soptim from "./eslint-rules/index.mjs";

export default tseslint.config(
    {
        ignores: ["out/**", "dist/**", "node_modules/**", "server/**", "*.vsix"],
    },

    // TypeScript extension sources: full recommended rule set + license header.
    {
        files: ["src/**/*.ts"],
        extends: [js.configs.recommended, ...tseslint.configs.recommended, prettier],
        languageOptions: {
            ecmaVersion: 2021,
            sourceType: "module",
        },
        plugins: {
            soptim,
        },
        rules: {
            "soptim/copyright-header": "error",
            "@typescript-eslint/no-unused-vars": [
                "error",
                { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
            ],
        },
    },

    // Build scripts and the ESLint rules themselves: enforce the header only.
    {
        files: ["**/*.{js,mjs,cjs}"],
        plugins: {
            soptim,
        },
        rules: {
            "soptim/copyright-header": "error",
        },
    },
);
