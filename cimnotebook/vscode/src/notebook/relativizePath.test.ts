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

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import * as path from "path";

import { relativizePath } from "./relativizePath";

describe("relativizePath", () => {
    it("relativizes a descendant path with a ./ prefix", () => {
        const from = path.join("/ws", "notebooks");
        const to = path.join("/ws", "notebooks", "data", "model.xml");
        assert.equal(relativizePath(from, to), "./data/model.xml");
    });

    it("keeps the ../ form for paths outside the base directory", () => {
        const from = path.join("/ws", "notebooks");
        const to = path.join("/ws", "data", "model.xml");
        assert.equal(relativizePath(from, to), "../data/model.xml");
    });

    it("returns ./name for a direct sibling file", () => {
        const from = path.join("/ws", "notebooks");
        const to = path.join("/ws", "notebooks", "model.xml");
        assert.equal(relativizePath(from, to), "./model.xml");
    });

    it("returns . for the base directory itself, not an empty string", () => {
        const dir = path.join("/ws", "notebooks");
        assert.equal(relativizePath(dir, dir), ".");
    });

    it("keeps a Windows cross-drive path absolute instead of prefixing ./", () => {
        assert.equal(
            relativizePath("C:\\ws\\notebooks", "D:\\data\\model.xml", path.win32),
            "D:/data/model.xml",
        );
        // Same drive still relativizes normally.
        assert.equal(
            relativizePath("C:\\ws\\notebooks", "C:\\ws\\notebooks\\model.xml", path.win32),
            "./model.xml",
        );
    });
});
