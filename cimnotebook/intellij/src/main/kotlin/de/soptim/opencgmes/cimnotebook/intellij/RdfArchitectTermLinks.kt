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
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.JList

/**
 * The terms of a document that navigate into RDFArchitect, as the language server reports them
 * (`cimvocabcheck.rdfArchitectTerms`).
 *
 * Backs "Open in RDFArchitect": the action needs to know which profiles declare the term under the
 * caret, so it can ask which one to open rather than letting RDFArchitect pick whichever graph it
 * finds the term in first.
 *
 * The answer is cached per document revision, so repeated use of the action costs one round trip.
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
        val profiles: List<Profile>,
    )

    /** One profile a term is declared in, and the RDFArchitect graph holding that profile. */
    data class Profile(
        val label: String,
        val graph: String,
    ) {
        override fun toString(): String = label
    }

    /**
     * A document's terms and the RDFArchitect instance its schema comes from.
     *
     * @param baseUrl null when the config names a dataset without saying which instance holds it —
     *   the tool window's configured URL answers that
     * @param dataset the dataset to open terms in, or null when the schema is a snapshot link —
     *   which every session that loads it names differently
     */
    data class Terms(
        val baseUrl: String?,
        val dataset: String?,
        val terms: List<Term>,
    )

    /** A term to open, resolved from a caret offset. */
    data class Target(
        val baseUrl: String,
        val dataset: String?,
        val iri: String,
        val profiles: List<Profile>,
    )

    private data class Entry(
        val stamp: Long,
        val terms: Terms?,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * The term at a line/column of the revision [stamp]. The position is read on the EDT and looked
     * up off it, because the lookup can wait on the language server.
     */
    fun termAt(
        file: VirtualFile,
        stamp: Long,
        line: Int,
        column: Int,
    ): Target? {
        val found = termsFor(file, stamp) ?: return null
        val term =
            found.terms.firstOrNull {
                it.line == line && column >= it.startCharacter && column < it.endCharacter
            } ?: return null
        // The server names the instance only when the config does, or when a session is connected;
        // otherwise the tool window's own URL is the answer.
        val baseUrl = found.baseUrl ?: RdfArchitectToolWindowFactory.configuredUrl() ?: return null
        return Target(baseUrl, found.dataset, term.iri, term.profiles)
    }

    private fun termsFor(
        file: VirtualFile,
        stamp: Long,
    ): Terms? {
        val uri = LSPIJUtils.toUriAsString(file)
        cache[uri]?.let { if (it.stamp == stamp) return it.terms }
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

    /**
     * Reads the command result, tolerating the Gson and in-process shapes.
     *
     * An answer without a `baseUrl` is still an answer: the server says which instance holds the
     * dataset only when the config does, or when a window is connected.
     */
    private fun parse(result: Any?): Terms? {
        val holder = objectOf(result) ?: return null
        if (holder !is JsonObject && holder !is Map<*, *>) {
            return null
        }
        return Terms(
            stringOf(holder, "baseUrl"),
            stringOf(holder, "dataset"),
            jsonArray(holder, "terms").mapNotNull(::parseTerm),
        )
    }

    private fun parseTerm(raw: Any?): Term? {
        val element = objectOf(raw) ?: return null
        val iri = stringOf(element, "iri") ?: return null
        val line = intOf(element, "line") ?: return null
        val start = intOf(element, "startCharacter") ?: return null
        val end = intOf(element, "endCharacter") ?: return null
        return Term(line, start, end, iri, jsonArray(element, "profiles").mapNotNull(::parseProfile))
    }

    private fun parseProfile(raw: Any?): Profile? {
        val element = objectOf(raw) ?: return null
        val label = stringOf(element, "label") ?: return null
        val graph = stringOf(element, "graph") ?: return null
        return Profile(label, graph)
    }

    /** The array under [field], in either the Gson or the in-process shape. */
    private fun jsonArray(
        holder: Any?,
        field: String,
    ): List<Any?> =
        when (holder) {
            is JsonObject -> holder.getAsJsonArray(field)?.toList() ?: emptyList()
            is Map<*, *> -> (holder[field] as? List<*>) ?: emptyList<Any>()
            else -> emptyList()
        }

    /** Unwraps a Gson object element; other shapes are read as-is. */
    private fun objectOf(raw: Any?): Any? = if (raw is JsonElement) raw.takeIf { it.isJsonObject }?.asJsonObject else raw

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

        /**
         * Opens [target] in the RDFArchitect tool window, asking which profile first when the term
         * is declared in more than one.
         *
         * Shared by Ctrl+Click and the "Open in RDFArchitect" action so both land in the same
         * place. Must be called on the EDT.
         *
         * @param editor where to anchor the chooser popup, or null to centre it on the window
         */
        fun open(
            project: Project,
            editor: Editor?,
            target: Target,
        ) {
            if (target.profiles.size <= 1) {
                openIn(project, target, target.profiles.firstOrNull())
                return
            }
            val popup =
                JBPopupFactory
                    .getInstance()
                    .createPopupChooserBuilder(target.profiles)
                    .setTitle("Open ${localNameOf(target.iri)} in Profile")
                    .setRenderer(ProfileRenderer())
                    .setItemChosenCallback { openIn(project, target, it) }
                    .createPopup()
            if (editor != null) {
                popup.showInBestPositionFor(editor)
            } else {
                popup.showCenteredInCurrentWindow(project)
            }
        }

        private fun openIn(
            project: Project,
            target: Target,
            profile: Profile?,
        ) {
            // If this is what first opens the tool window and the schema was never sent, the import
            // offered there should land on the term the user asked for.
            project.putUserData(RdfArchitectToolWindowFactory.PENDING_TERM_KEY, target.iri)
            RdfArchitectToolWindowFactory.openUrl(
                project,
                RdfArchitectToolWindowFactory.termDeepLink(
                    target.baseUrl,
                    target.iri,
                    target.dataset,
                    profile?.graph,
                ),
            )
        }

        /** The part of an IRI after its last `#` or `/`. */
        fun localNameOf(iri: String): String = iri.substringAfterLast('#').substringAfterLast('/')

        /** Shows a profile by name, with the graph holding it greyed out beside it. */
        private class ProfileRenderer : ColoredListCellRenderer<Profile>() {
            override fun customizeCellRenderer(
                list: JList<out Profile>,
                value: Profile?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) {
                    return
                }
                append(value.label)
                append("  ${value.graph}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        /** Must match the server id registered in plugin.xml. */
        private const val SERVER_ID = "cimvocabcheck-lsp"
        private const val CMD_RDFARCHITECT_TERMS = "cimvocabcheck.rdfArchitectTerms"

        /** Short enough that a Ctrl+hover over a file the server is busy with stays responsive. */
        private const val TIMEOUT_MS = 800L

        /** Bounds the cache without tracking editor lifecycles; a clear costs one refetch. */
        private const val MAX_CACHED_DOCUMENTS = 64
    }
}
