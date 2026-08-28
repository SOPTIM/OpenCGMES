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

import {
    connectionContextValue,
    connectionDescription,
    connectionIcon,
    connectionLabel,
    effectiveStandardVocabulary,
    numberSettingDescription,
    rdfArchitectDescription,
    schemasDirectoryDescription,
    standardVocabularyDescription,
    standardVocabularyValueToWrite,
    strictnessDescription,
    strictnessValueToWrite,
    validateConnectionName,
    validateOptionalUrl,
    validatePositiveIntegerOrEmpty,
    validateRequiredUrl,
} from "./treeItems";

describe("connection label/description/icon/contextValue", () => {
    it("plain connection", () => {
        const c = { name: "local", url: "http://localhost:3030/ds/query" };
        assert.equal(connectionLabel(c), "local");
        assert.equal(connectionDescription(c), "http://localhost:3030/ds/query");
        assert.equal(connectionIcon(c), "plug");
        assert.equal(connectionContextValue(c), "connection");
    });

    it("default connection with basic auth", () => {
        const c = {
            name: "prod",
            url: "https://h/query",
            authType: "basic" as const,
            default: true,
        };
        // Plain words, no $(...) codicons: TreeItem.description renders literal text.
        assert.equal(connectionDescription(c), "https://h/query · basic auth · default");
        assert.equal(connectionIcon(c), "star-full");
        assert.equal(connectionContextValue(c), "connection basic default");
    });
});

describe("validateConnectionName", () => {
    it("rejects empty names", () => {
        assert.match(validateConnectionName("  ", []) ?? "", /Enter a connection name/);
    });

    it("rejects names that look like file paths or URLs", () => {
        assert.match(validateConnectionName("a.b", []) ?? "", /can't contain/);
        assert.match(validateConnectionName("a/b", []) ?? "", /can't contain/);
        assert.match(validateConnectionName("a\\b", []) ?? "", /can't contain/);
        assert.match(validateConnectionName("http://h/query", []) ?? "", /can't contain/);
    });

    it("rejects names with whitespace (directives end at the first space)", () => {
        assert.match(validateConnectionName("my conn", []) ?? "", /whitespace/);
    });

    it("rejects duplicates but allows keeping the current name while editing", () => {
        assert.match(validateConnectionName("prod", ["prod", "dev"]) ?? "", /already used/);
        assert.equal(validateConnectionName("prod", ["prod", "dev"], "prod"), undefined);
    });

    it("accepts a fresh, valid name", () => {
        assert.equal(validateConnectionName("local-fuseki", ["prod"]), undefined);
    });
});

describe("validateRequiredUrl / validateOptionalUrl", () => {
    it("required: rejects empty and non-http(s) values", () => {
        assert.ok(validateRequiredUrl(""));
        assert.ok(validateRequiredUrl("ftp://h/x"));
        assert.equal(validateRequiredUrl("http://h/query"), undefined);
        assert.equal(validateRequiredUrl("https://h/query"), undefined);
    });

    it("optional: accepts empty, still validates non-empty values", () => {
        assert.equal(validateOptionalUrl(""), undefined);
        assert.equal(validateOptionalUrl("   "), undefined);
        assert.equal(validateOptionalUrl("https://h/update"), undefined);
        assert.ok(validateOptionalUrl("not-a-url"));
    });
});

describe("strictness / standardVocabulary description and write-value mapping", () => {
    it("strictness shows the effective value, unset reads as its own 'default' level", () => {
        assert.equal(strictnessDescription(undefined), "default");
        assert.equal(strictnessDescription(""), "default");
        assert.equal(strictnessDescription("strict"), "strict");
    });

    it("standardVocabulary annotates the implicit default", () => {
        assert.equal(standardVocabularyDescription(undefined), "check (default)");
        assert.equal(standardVocabularyDescription("ignore"), "ignore");
        assert.equal(effectiveStandardVocabulary(undefined), "check");
        assert.equal(effectiveStandardVocabulary("ignore"), "ignore");
    });

    it("schemasDirectory unset means the listed schema files alone, or syntax-only with none", () => {
        assert.equal(
            schemasDirectoryDescription(undefined, 0),
            "not set (validation is syntax-only)",
        );
        assert.equal(schemasDirectoryDescription("", 0), "not set (validation is syntax-only)");
        assert.equal(
            schemasDirectoryDescription(undefined, 2),
            "not set (schema files below are used)",
        );
        assert.equal(schemasDirectoryDescription("profiles", 0), "profiles");
    });

    it("schemasDirectory says so when the model comes from RDFArchitect instead", () => {
        assert.equal(
            schemasDirectoryDescription(undefined, 0, "cgmes-3.0"),
            "not set (the RDFArchitect model below is used)",
        );
        // Schema files win: they are what the config actually loads.
        assert.equal(
            schemasDirectoryDescription(undefined, 2, "cgmes-3.0"),
            "not set (schema files below are used)",
        );
    });

    it("the RDFArchitect row says whether the view has to be open for it", () => {
        assert.equal(rdfArchitectDescription(undefined), "not set");
        assert.equal(rdfArchitectDescription("  "), "not set");
        assert.equal(rdfArchitectDescription("cgmes-3.0"), "cgmes-3.0 (dataset in the open view)");
        assert.equal(
            rdfArchitectDescription("http://localhost:3000/?snapshot=abc"),
            "http://localhost:3000/?snapshot=abc (link)",
        );
    });

    it("picking the schema-default value clears the field; anything else is written", () => {
        assert.equal(strictnessValueToWrite("default"), undefined);
        assert.equal(strictnessValueToWrite("strict"), "strict");
        assert.equal(standardVocabularyValueToWrite("check"), undefined);
        assert.equal(standardVocabularyValueToWrite("ignore"), "ignore");
    });
});

describe("numberSettingDescription / validatePositiveIntegerOrEmpty", () => {
    it("shows the value, or the default annotated when unset", () => {
        assert.equal(numberSettingDescription(45, 30), "45");
        assert.equal(numberSettingDescription(undefined, 30), "30 (default)");
    });

    it("accepts empty (clears) and positive integers; rejects everything else", () => {
        assert.equal(validatePositiveIntegerOrEmpty(""), undefined);
        assert.equal(validatePositiveIntegerOrEmpty("  "), undefined);
        assert.equal(validatePositiveIntegerOrEmpty("42"), undefined);
        assert.ok(validatePositiveIntegerOrEmpty("0"));
        assert.ok(validatePositiveIntegerOrEmpty("-1"));
        assert.ok(validatePositiveIntegerOrEmpty("abc"));
        assert.ok(validatePositiveIntegerOrEmpty("4.5"));
    });
});
