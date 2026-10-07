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

import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import {
    datasetNameFor,
    datasetNames,
    localNameOf,
    normalizeBaseUrl,
    parseTermLink,
    snapshotDatasetName,
    termDeepLink,
} from "../rdfArchitect";

describe("normalizeBaseUrl", () => {
    it("gives one spelling to the forms a base URL arrives in", () => {
        // The setting as typed, new URL().toString() (which adds the slash), and the language
        // server's own form (which strips it) must all compare equal.
        const spellings = [
            "http://localhost:3000",
            "http://localhost:3000/",
            "http://localhost:3000//",
            new URL("http://localhost:3000").toString(),
            "  http://localhost:3000/  ",
        ];
        for (const spelling of spellings) {
            assert.equal(normalizeBaseUrl(spelling), "http://localhost:3000");
        }
    });

    it("keeps a deployment path, without its trailing slash", () => {
        assert.equal(normalizeBaseUrl("https://host/rdfa/"), "https://host/rdfa");
    });

    it("rejects something that is not a URL", () => {
        assert.throws(() => normalizeBaseUrl("not a url"));
    });
});

describe("termDeepLink", () => {
    it("addresses a term through the class parameter", () => {
        assert.equal(
            termDeepLink("http://localhost:3000", "http://iec.ch/TC57/CIM100#ACLineSegment"),
            "http://localhost:3000/mainpage?class=http%3A%2F%2Fiec.ch%2FTC57%2FCIM100%23ACLineSegment",
        );
    });

    it("pins the profile with the dataset and graph when they are known", () => {
        const url = new URL(
            termDeepLink("http://localhost:3000/", "urn:x#T", "cgmes-3.0", "EQ profile.rdf"),
        );
        assert.equal(url.pathname, "/mainpage");
        assert.equal(url.searchParams.get("dataset"), "cgmes-3.0");
        // The graph is a file name, so it can carry spaces — they must survive the round trip.
        assert.equal(url.searchParams.get("graph"), "EQ profile.rdf");
    });

    it("leaves out what is not known, rather than sending it empty", () => {
        const url = new URL(termDeepLink("http://host:3000", "urn:x#T", undefined, ""));
        assert.equal(url.searchParams.has("dataset"), false);
        assert.equal(url.searchParams.has("graph"), false);
    });

    it("keeps an instance served under a sub-path", () => {
        assert.match(
            termDeepLink("https://tools.corp/rdfa/", "urn:x#T"),
            /^https:\/\/tools\.corp\/rdfa\/mainpage\?/,
        );
    });
});

describe("datasetNameFor", () => {
    it("names the dataset after the config file's directory", () => {
        assert.equal(datasetNameFor("/home/u/projects/grid-model/opencgmes.jsonc"), "grid-model");
    });

    it("replaces what RDFArchitect would not accept in a name", () => {
        assert.equal(datasetNameFor("/home/u/my grid (2026)/opencgmes.jsonc"), "my_grid__2026_");
    });

    it("falls back when there is no directory to name it after", () => {
        assert.equal(datasetNameFor("opencgmes.jsonc"), "cimnotebook");
        assert.equal(datasetNameFor("./opencgmes.jsonc"), "cimnotebook");
        assert.equal(datasetNameFor("../opencgmes.jsonc"), "cimnotebook");
    });
});

describe("snapshotDatasetName", () => {
    it("builds the name RDFArchitect gives a loaded snapshot", () => {
        assert.equal(
            snapshotDatasetName("grid-model", "ffPKWuq2hw8WKBRn5VwEOA"),
            "SNAPSHOT_grid-model_ffPKWuq2hw8WKBRn5VwEOA",
        );
    });

    it("keeps the token last, which is how the language server recovers it", () => {
        // The dataset name may contain underscores, so only the trailing token is addressable —
        // see RdfArchitectSource.snapshotTokenOf, which reads it by length from the end.
        const name = snapshotDatasetName("my_grid_model", "iLdGrIScuO2wWUtWvNDvwQ");
        assert.equal(name.slice(-22), "iLdGrIScuO2wWUtWvNDvwQ");
        assert.equal(name.charAt(name.length - 23), "_");
    });
});

describe("parseTermLink", () => {
    /** A link path as the language server builds it: payload, profile, local name. */
    const linkPath = (directive: string) =>
        `/${Buffer.from(directive, "utf8").toString("base64url")}/CoreEquipment-EU/T`;

    it("reads the fields the language server encodes", () => {
        const fields = parseTermLink(
            linkPath(
                "class=urn%3Ax%23T base=http%3A%2F%2Fhost%3A3000 dataset=cgmes-3.0 graph=EQ%20profile.rdf",
            ),
        );
        assert.equal(fields?.get("class"), "urn:x#T");
        assert.equal(fields?.get("base"), "http://host:3000");
        assert.equal(fields?.get("dataset"), "cgmes-3.0");
        // Percent-encoded because a graph is named after the file it was imported from.
        assert.equal(fields?.get("graph"), "EQ profile.rdf");
    });

    it("accepts a link that names only the term", () => {
        const fields = parseTermLink(linkPath("class=urn%3Ax%23T"));
        assert.equal(fields?.get("class"), "urn:x#T");
        assert.equal(fields?.has("base"), false);
    });

    it("rejects anything that does not name a term", () => {
        assert.equal(parseTermLink(linkPath("base=http%3A%2F%2Fhost")), undefined);
        assert.equal(parseTermLink("/not base64!/EQ/T"), undefined);
        assert.equal(parseTermLink(""), undefined);
    });
});

describe("localNameOf", () => {
    it("takes the part after the last separator", () => {
        assert.equal(localNameOf("http://iec.ch/TC57/CIM100#ACLineSegment"), "ACLineSegment");
        assert.equal(localNameOf("http://example.org/terms/Breaker"), "Breaker");
    });

    it("returns the value itself when there is no separator", () => {
        assert.equal(localNameOf("ACLineSegment"), "ACLineSegment");
    });
});

describe("datasetNames", () => {
    it("reads the names of dataset objects", () => {
        const listing = [
            { name: "cgmes-3.0", prefixes: { cim: "http://iec.ch/TC57/CIM100#" }, readOnly: false },
            { name: "draft", prefixes: {}, readOnly: true },
        ];
        assert.deepEqual(datasetNames(listing), ["cgmes-3.0", "draft"]);
    });

    it("reads the bare names older instances serve", () => {
        assert.deepEqual(datasetNames(["cgmes-3.0", "draft"]), ["cgmes-3.0", "draft"]);
    });

    it("does not mistake a prefix for a dataset", () => {
        assert.deepEqual(datasetNames([{ name: "a", prefixes: { cim: "urn:x" } }]), ["a"]);
    });

    it("answers no names for anything but a list", () => {
        assert.deepEqual(datasetNames({ error: "nope" }), []);
        assert.deepEqual(datasetNames(null), []);
    });
});
