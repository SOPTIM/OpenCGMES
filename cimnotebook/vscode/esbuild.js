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
        // jsonc-parser's CJS entry is a UMD wrapper that passes `require` into its
        // factory as a plain function parameter; esbuild cannot statically follow the
        // factory's internal require("./impl/…") calls and leaves them in the bundle as
        // runtime requires, which crash extension activation with "Cannot find module
        // './impl/format'". Bundling its ESM build instead makes every import static.
        alias: { "jsonc-parser": "jsonc-parser/lib/esm/main.js" },
        format: "cjs",
        platform: "node",
        target: "node20",
        sourcemap: !prod,
        minify: prod,
    })
    .catch(() => process.exit(1));
