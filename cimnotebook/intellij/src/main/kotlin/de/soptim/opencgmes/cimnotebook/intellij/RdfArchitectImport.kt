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

import com.google.gson.Gson

/**
 * Reading RDFArchitect's import job status (`GET .../graphs/content/imports/<jobId>`): parsing it,
 * and turning it into a progress line and into notes worth showing the user.
 */
object RdfArchitectImport {
    private val GSON = Gson()

    /** A namespace binding of an import's prefix comparison. */
    data class PrefixBinding(
        val iri: String?,
        val fileNames: List<String>?,
    )

    data class PrefixComparison(
        val prefix: String?,
        val workspace: PrefixBinding?,
        val imported: List<PrefixBinding>?,
        val contested: Boolean,
    )

    data class FileStatus(
        val fileName: String?,
        /** PENDING, RUNNING, IMPORTED, FAILED or SKIPPED. */
        val state: String?,
    )

    data class Warning(
        val fileName: String?,
        val undisplayableProperties: List<String>?,
    )

    /** The parts of the job status the plugin reads; Gson leaves absent fields null. */
    data class JobStatus(
        /** RUNNING, SCANNING_PREFIXES, AWAITING_PREFIX_RESOLUTION, COMPLETED, CANCELLED or FAILED. */
        val state: String?,
        val files: List<FileStatus>?,
        val failedImports: List<String>?,
        val warnings: List<Warning>?,
        val prefixComparison: List<PrefixComparison>?,
        val errorMessage: String?,
    ) {
        val isSettled: Boolean get() = state == "COMPLETED" || state == "FAILED" || state == "CANCELLED"
    }

    /** A progress line, and how far through its files the import is (0–1) once they are known. */
    data class Progress(
        val message: String,
        val fraction: Double?,
    )

    fun parse(json: String): JobStatus = GSON.fromJson(json, JobStatus::class.java)

    fun progress(status: JobStatus): Progress {
        when (status.state) {
            "SCANNING_PREFIXES" -> return Progress("Checking namespace prefixes…", 0.0)
            "AWAITING_PREFIX_RESOLUTION" -> return Progress("Resolving namespace prefix conflicts…", 0.0)
        }
        val files = status.files.orEmpty()
        if (files.isEmpty()) {
            return Progress("Preparing the import…", null)
        }
        val done = files.count { it.state != "PENDING" && it.state != "RUNNING" }
        val fraction = done.toDouble() / files.size
        val current = files.firstOrNull { it.state == "RUNNING" } ?: files.firstOrNull { it.state == "PENDING" }
        return if (current != null) {
            Progress("Importing ${done + 1} of ${files.size}: ${current.fileName}", fraction)
        } else {
            Progress("Imported $done of ${files.size} files", fraction)
        }
    }

    /**
     * What answering an import's prefix conflicts with no decisions does, one line per contested
     * prefix: the prefix stays with the workspace's namespace, or else with the first file declaring
     * it, and every other namespace claiming it is imported without a prefix.
     */
    fun defaultPrefixDecisions(status: JobStatus): List<String> =
        status.prefixComparison.orEmpty().filter { it.contested }.map { comparison ->
            val imported = comparison.imported.orEmpty()
            val holder = comparison.workspace?.iri ?: imported.firstOrNull()?.iri
            val others =
                imported
                    .filter { it.iri != holder }
                    .joinToString(", ") { "<${it.iri}> (${it.fileNames.orEmpty().joinToString(", ")})" }
            "${comparison.prefix} stays bound to <$holder>; imported without a prefix: $others"
        }

    /** The properties a finished import stored but RDFArchitect will not display, one line per file. */
    fun undisplayableProperties(status: JobStatus): List<String> =
        status.warnings
            .orEmpty()
            .filter { !it.undisplayableProperties.isNullOrEmpty() }
            .map { "${it.fileName}: ${it.undisplayableProperties!!.joinToString(", ")}" }
}
