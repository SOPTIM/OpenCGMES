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
package de.soptim.opencgmes.cimnotebook.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The strings the RDFArchitect integration agrees on with RDFArchitect itself and with the language
 * server: the deep links it builds, the dataset names it constructs, and the header it reads back
 * out of a generated definition document.
 */
class RdfArchitectLinkTest {
    @Test
    fun `a term is addressed through the class parameter`() {
        assertEquals(
            "http://localhost:3000/mainpage?class=http%3A%2F%2Fiec.ch%2FTC57%2FCIM100%23ACLineSegment",
            RdfArchitectToolWindowFactory.termDeepLink(
                "http://localhost:3000",
                "http://iec.ch/TC57/CIM100#ACLineSegment",
            ),
        )
    }

    @Test
    fun `the dataset and graph pin the profile a term opens in`() {
        val url =
            RdfArchitectToolWindowFactory.termDeepLink(
                "http://localhost:3000/",
                "urn:x#T",
                "cgmes-3.0",
                "EQ profile.rdf",
            )
        assertEquals(
            "http://localhost:3000/mainpage?class=urn%3Ax%23T&dataset=cgmes-3.0&graph=EQ+profile.rdf",
            url,
        )
    }

    @Test
    fun `what is not known is left out rather than sent empty`() {
        val url = RdfArchitectToolWindowFactory.termDeepLink("http://host:3000", "urn:x#T", null, "")
        assertEquals("http://host:3000/mainpage?class=urn%3Ax%23T", url)
    }

    @Test
    fun `a snapshot link names the dataset the snapshot loads as`() {
        val link =
            RdfArchitectSchemaHandoff.datasetLink(
                "http://host:3000/",
                "grid-model",
                "ffPKWuq2hw8WKBRn5VwEOA",
                null,
            )
        assertEquals(
            "http://host:3000/?snapshot=ffPKWuq2hw8WKBRn5VwEOA" +
                "&dataset=SNAPSHOT_grid-model_ffPKWuq2hw8WKBRn5VwEOA",
            link,
        )
    }

    @Test
    fun `a dataset sent into the connected session is opened by name`() {
        val link = RdfArchitectSchemaHandoff.datasetLink("http://host:3000", "grid-model", null, null)
        assertEquals("http://host:3000/?dataset=grid-model", link)
    }

    @Test
    fun `a term the import should land on rides along`() {
        val link =
            RdfArchitectSchemaHandoff.datasetLink("http://host:3000", "grid", null, "urn:x#T")
        assertTrue(link.endsWith("&class=urn%3Ax%23T"), link)
    }

    @Test
    fun `the dataset is named after the config file's directory`() {
        assertEquals(
            "grid-model",
            RdfArchitectSchemaHandoff.datasetNameFor("/home/u/grid-model/opencgmes.jsonc"),
        )
    }

    @Test
    fun `a name RDFArchitect would not accept is sanitised`() {
        assertEquals(
            "my_grid__2026_",
            RdfArchitectSchemaHandoff.datasetNameFor("/home/u/my grid (2026)/opencgmes.jsonc"),
        )
    }

    @Test
    fun `a path with no directory falls back to a fixed name`() {
        assertEquals("cimnotebook", RdfArchitectSchemaHandoff.datasetNameFor("opencgmes.jsonc"))
    }

    @Test
    fun `a relative path falls back to the same name the VS Code extension uses`() {
        // The parent of "./opencgmes.jsonc" is ".", which is a legal dataset name and a useless
        // one — and the two editors must not disagree about what a workspace is called.
        assertEquals("cimnotebook", RdfArchitectSchemaHandoff.datasetNameFor("./opencgmes.jsonc"))
        assertEquals("cimnotebook", RdfArchitectSchemaHandoff.datasetNameFor("../opencgmes.jsonc"))
    }

    @Test
    fun `the definition header is read as the language server writes it`() {
        val fields =
            RdfArchitectDefinitionOpener().fields(
                "class=urn%3Ax%23T base=http%3A%2F%2Fhost%3A3000 dataset=cgmes-3.0 graph=EQ%20profile.rdf",
            )
        assertEquals("urn:x#T", fields["class"])
        assertEquals("http://host:3000", fields["base"])
        assertEquals("cgmes-3.0", fields["dataset"])
        // Percent-encoded because a graph is named after the file it was imported from.
        assertEquals("EQ profile.rdf", fields["graph"])
    }

    @Test
    fun `a header naming only the term is enough`() {
        val fields = RdfArchitectDefinitionOpener().fields("class=urn%3Ax%23T")
        assertEquals("urn:x#T", fields["class"])
        assertNull(fields["base"])
    }

    @Test
    fun `an answer produced before the schema loaded is not kept`() {
        // The cache is keyed by the document's revision, so a provisional answer would stand until
        // the file is edited. Terms with no profile mean the schema had not loaded yet.
        val term = RdfArchitectTermLinks.Term(0, 0, 5, "urn:x#T", emptyList())
        assertFalse(
            RdfArchitectTermLinks.worthCaching(
                RdfArchitectTermLinks.Terms("http://host:3000", "cgmes-3.0", listOf(term)),
            ),
        )
        assertFalse(RdfArchitectTermLinks.worthCaching(null))
    }

    @Test
    fun `a settled answer is kept`() {
        val profile = RdfArchitectTermLinks.Profile("EQ/3.0", "EQ profile.rdf")
        val term = RdfArchitectTermLinks.Term(0, 0, 5, "urn:x#T", listOf(profile))
        assertTrue(
            RdfArchitectTermLinks.worthCaching(
                RdfArchitectTermLinks.Terms("http://host:3000", "cgmes-3.0", listOf(term)),
            ),
        )
        // A document that simply names no term is an answer too, not a provisional one.
        assertTrue(
            RdfArchitectTermLinks.worthCaching(
                RdfArchitectTermLinks.Terms("http://host:3000", null, emptyList()),
            ),
        )
    }

    @Test
    fun `a term's local name is what a chooser shows`() {
        assertEquals(
            "ACLineSegment",
            RdfArchitectTermLinks.localNameOf("http://iec.ch/TC57/CIM100#ACLineSegment"),
        )
        assertEquals("Breaker", RdfArchitectTermLinks.localNameOf("http://example.org/t/Breaker"))
        assertEquals("ACLineSegment", RdfArchitectTermLinks.localNameOf("ACLineSegment"))
    }
}
