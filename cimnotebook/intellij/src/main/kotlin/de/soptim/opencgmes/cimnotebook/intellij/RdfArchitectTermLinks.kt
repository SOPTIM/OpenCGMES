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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The terms of a document that navigate into RDFArchitect, as the language server reports them
 * (`cimvocabcheck.rdfArchitectTerms`).
 *
 * A document validated against a model held in RDFArchitect has no schema files on disk, so
 * go-to-definition has nothing to jump to. The server answers with the term ranges instead, and
 * [RdfArchitectGotoDeclarationHandler] turns the one under the caret into a Ctrl+Click target.
 *
 * The answer is cached per document revision: Ctrl+hover asks for a target on every mouse move, and
 * that must not become a round trip to the server each time.
 */
@Service(Service.Level.PROJECT)
class RdfArchitectTermLinks(
    private val project: Project,
) {
    /** One term, with the range of the token naming it (both zero-based, end exclusive). */
    data class Term(
        val line: Int,
        val startCharacter: Int,
        val endCharacter: Int,
        val iri: String,
    )

    /** A document's terms and the RDFArchitect instance its schema comes from. */
    data class Terms(
        val baseUrl: String,
        val terms: List<Term>,
    )

    private data class Entry(
        val stamp: Long,
        val terms: Terms?,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /** The instance and term IRI at [offset], or null when nothing there opens in RDFArchitect. */
    fun termAt(
        file: VirtualFile,
        document: Document,
        offset: Int,
    ): Pair<String, String>? {
        val found = termsFor(file, document) ?: return null
        if (offset < 0 || offset > document.textLength) {
            return null
        }
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line)
        val term =
            found.terms.firstOrNull {
                it.line == line && column >= it.startCharacter && column < it.endCharacter
            } ?: return null
        return found.baseUrl to term.iri
    }

    private fun termsFor(
        file: VirtualFile,
        document: Document,
    ): Terms? {
        val uri = LSPIJUtils.toUriAsString(file)
        val stamp = document.modificationStamp
        cache[uri]?.let { if (it.stamp == stamp) return it.terms }
        if (ApplicationManager.getApplication().isDispatchThread) {
            // Ctrl+click can land here on the EDT; never block it. The refresh makes the answer
            // available a moment later, and until then the last one for this file is used.
            ApplicationManager.getApplication().executeOnPooledThread { store(uri, stamp) }
            return cache[uri]?.terms
        }
        return store(uri, stamp)
    }

    private fun store(
        uri: String,
        stamp: Long,
    ): Terms? {
        val fetched = fetch(uri)
        if (cache.size > MAX_CACHED_DOCUMENTS) {
            cache.clear()
        }
        cache[uri] = Entry(stamp, fetched)
        return fetched
    }

    private fun fetch(uri: String): Terms? =
        runCatching {
            LanguageServerManager
                .getInstance(project)
                .getLanguageServer(SERVER_ID)
                .thenCompose { item ->
                    item?.workspaceService?.executeCommand(
                        ExecuteCommandParams(CMD_RDFARCHITECT_TERMS, listOf<Any>(uri)),
                    ) ?: CompletableFuture.completedFuture(null)
                }.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .let(::parse)
        }.onFailure { LOG.debug("Could not read the RDFArchitect terms of $uri", it) }
            .getOrNull()

    /** Reads the command result, tolerating the Gson and in-process shapes. */
    private fun parse(result: Any?): Terms? {
        val base = stringOf(result, "baseUrl") ?: return null
        val raw =
            when (result) {
                is JsonObject -> result.getAsJsonArray("terms")?.toList() ?: emptyList()
                is Map<*, *> -> (result["terms"] as? List<*>) ?: emptyList<Any>()
                else -> emptyList<Any>()
            }
        return Terms(base, raw.mapNotNull(::parseTerm))
    }

    private fun parseTerm(raw: Any?): Term? {
        val element = if (raw is JsonElement && raw.isJsonObject) raw.asJsonObject else raw
        val iri = stringOf(element, "iri") ?: return null
        val line = intOf(element, "line") ?: return null
        val start = intOf(element, "startCharacter") ?: return null
        val end = intOf(element, "endCharacter") ?: return null
        return Term(line, start, end, iri)
    }

    private fun stringOf(
        holder: Any?,
        field: String,
    ): String? =
        when (holder) {
            is JsonObject -> holder.get(field)?.takeIf { it.isJsonPrimitive }?.asString
            is Map<*, *> -> holder[field] as? String
            else -> null
        }

    private fun intOf(
        holder: Any?,
        field: String,
    ): Int? =
        when (holder) {
            is JsonObject -> holder.get(field)?.takeIf { it.isJsonPrimitive }?.asInt
            is Map<*, *> -> (holder[field] as? Number)?.toInt()
            else -> null
        }

    companion object {
        private val LOG = Logger.getInstance(RdfArchitectTermLinks::class.java)

        /** Must match the server id registered in plugin.xml. */
        private const val SERVER_ID = "cimvocabcheck-lsp"
        private const val CMD_RDFARCHITECT_TERMS = "cimvocabcheck.rdfArchitectTerms"

        /** Short enough that a Ctrl+hover over a file the server is busy with stays responsive. */
        private const val TIMEOUT_MS = 800L

        /** Bounds the cache without tracking editor lifecycles; a clear costs one refetch. */
        private const val MAX_CACHED_DOCUMENTS = 64
    }
}
