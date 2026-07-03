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

const esbuild = require("esbuild");

const prod = process.argv.includes("--production");

esbuild
    .build({
        entryPoints: ["src/extension.ts"],
        bundle: true,
        outfile: "out/extension.js",
        external: ["vscode"], // provided by VS Code at runtime — never bundle
        format: "cjs",
        platform: "node",
        target: "node20",
        sourcemap: !prod,
        minify: prod,
    })
    .catch(() => process.exit(1));
