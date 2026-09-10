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

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Shows the term in the RDFArchitect tool window whenever one of the language server's generated
 * definition documents is opened.
 *
 * A model held in RDFArchitect has no schema files, so Ctrl+Click on one of its terms goes to a
 * document the server renders from the loaded schema. Opening that document is the moment the user
 * asked to *see* the term — and the only moment we can act on: the IDE resolves a Ctrl+Click target
 * while the user is merely hovering, so nothing may happen at resolution time.
 */
class RdfArchitectDefinitionOpener : FileEditorManagerListener {
    override fun fileOpened(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        val header = firstLine(file) ?: return
        if (!header.startsWith(MARKER)) {
            return
        }
        open(source.project, fields(header.removePrefix(MARKER)))
    }

    private fun firstLine(file: VirtualFile): String? {
        if (file.extension != "ttl") {
            return null
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return if (document.lineCount == 0) {
            null
        } else {
            document.getText(
                com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(0),
                    document.getLineEndOffset(0),
                ),
            )
        }
    }

    /** The header's percent-encoded `key=value` pairs. */
    internal fun fields(header: String): Map<String, String> =
        header
            .trim()
            .split(' ')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) {
                    null
                } else {
                    pair.substring(0, eq) to
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                }
            }.toMap()

    private fun open(
        project: Project,
        fields: Map<String, String>,
    ) {
        val iri = fields["class"] ?: return
        val base = fields["base"] ?: RdfArchitectToolWindowFactory.configuredUrl() ?: return
        project.putUserData(RdfArchitectToolWindowFactory.PENDING_TERM_KEY, iri)
        RdfArchitectToolWindowFactory.openUrl(
            project,
            RdfArchitectToolWindowFactory.termDeepLink(base, iri, fields["dataset"], fields["graph"]),
        )
    }

    companion object {
        /** Marks the header line of a generated RDFArchitect definition document. */
        private const val MARKER = "#! rdfarchitect "
    }
}
