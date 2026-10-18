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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reading RDFArchitect's import job status into progress and notes. */
class RdfArchitectImportTest {
    /** A status as RDFArchitect answers it while waiting for prefix decisions. */
    private val awaitingPrefixes =
        """
        {"jobId":"fd05bc0c-8b0c-41e4-a814-865fdc0c5c96","datasetName":"probe",
         "state":"AWAITING_PREFIX_RESOLUTION",
         "files":[{"index":0,"fileName":"a.ttl","sizeBytes":111,"state":"PENDING","graphUri":null},
                  {"index":1,"fileName":"b.ttl","sizeBytes":111,"state":"PENDING","graphUri":null}],
         "importedGraphUris":[],"failedImports":[],"warnings":[],
         "prefixComparison":[
           {"prefix":"ex:","workspace":null,"imported":[
             {"iri":"http://a.example/#","fileNames":["a.ttl"]},
             {"iri":"http://b.example/#","fileNames":["b.ttl"]}],"contested":true},
           {"prefix":"rdfs:","workspace":null,"imported":[
             {"iri":"http://www.w3.org/2000/01/rdf-schema#","fileNames":["a.ttl","b.ttl"]}],
            "contested":false}],
         "errorMessage":null}
        """.trimIndent()

    private fun running(vararg states: String) =
        RdfArchitectImport.JobStatus(
            "RUNNING",
            states.mapIndexed { i, state -> RdfArchitectImport.FileStatus("f$i.rdf", state) },
            null,
            null,
            null,
            null,
        )

    @Test
    fun `a contested prefix stays with the first file declaring it`() {
        val status = RdfArchitectImport.parse(awaitingPrefixes)
        assertFalse(status.isSettled)
        assertEquals(
            listOf("ex: stays bound to <http://a.example/#>; imported without a prefix: <http://b.example/#> (b.ttl)"),
            RdfArchitectImport.defaultPrefixDecisions(status),
        )
        assertEquals("Resolving namespace prefix conflicts…", RdfArchitectImport.progress(status).message)
    }

    @Test
    fun `a contested prefix stays with the workspace's namespace`() {
        val status =
            RdfArchitectImport.parse(
                """
                {"state":"AWAITING_PREFIX_RESOLUTION","prefixComparison":[
                  {"prefix":"ex:","workspace":{"iri":"http://w/#","fileNames":[]},
                   "imported":[{"iri":"http://a/#","fileNames":["a.ttl"]}],"contested":true}]}
                """.trimIndent(),
            )
        assertEquals(
            listOf("ex: stays bound to <http://w/#>; imported without a prefix: <http://a/#> (a.ttl)"),
            RdfArchitectImport.defaultPrefixDecisions(status),
        )
    }

    @Test
    fun `progress names the file being imported and counts the finished ones`() {
        assertEquals(
            RdfArchitectImport.Progress("Importing 3 of 4: f2.rdf", 0.5),
            RdfArchitectImport.progress(running("IMPORTED", "FAILED", "RUNNING", "PENDING")),
        )
        assertEquals(
            RdfArchitectImport.Progress("Imported 2 of 2 files", 1.0),
            RdfArchitectImport.progress(running("IMPORTED", "SKIPPED")),
        )
    }

    @Test
    fun `progress has no fraction before the files are known`() {
        assertEquals(RdfArchitectImport.Progress("Preparing the import…", null), RdfArchitectImport.progress(running()))
    }

    @Test
    fun `a finished import reports what it will not display`() {
        val status =
            RdfArchitectImport.parse(
                """
                {"state":"COMPLETED","warnings":[
                  {"fileName":"EQ.rdf","undisplayableProperties":["cims:foo","cims:bar"]},
                  {"fileName":"SSH.rdf","undisplayableProperties":[]}]}
                """.trimIndent(),
            )
        assertTrue(status.isSettled)
        assertEquals(listOf("EQ.rdf: cims:foo, cims:bar"), RdfArchitectImport.undisplayableProperties(status))
    }
}
