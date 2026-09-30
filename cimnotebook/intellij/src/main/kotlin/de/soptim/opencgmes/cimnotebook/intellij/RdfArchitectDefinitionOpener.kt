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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Shows the term in the RDFArchitect tool window when one of the language server's term links is
 * navigated to, and closes the placeholder tab again.
 *
 * A model held in RDFArchitect has no schema files, so Ctrl+Click on one of its terms goes to a
 * virtual `rdfarchitect:` link, resolved by [RdfArchitectTermFileSystem]. Navigating to it is the
 * moment the user asked to *see* the term — and the only moment we can act on: the IDE resolves a
 * Ctrl+Click target while the user is merely hovering, so nothing may happen at resolution time.
 */
class RdfArchitectDefinitionOpener : FileEditorManagerListener {
    override fun fileOpened(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        if (file.fileSystem.protocol != RdfArchitectTermFileSystem.PROTOCOL) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (!source.project.isDisposed) {
                source.closeFile(file)
            }
        }
        fieldsOfLink(file.path)?.let { open(source.project, it) }
    }

    private fun open(
        project: Project,
        fields: Map<String, String>,
    ) {
        val iri = fields["class"] ?: return
        val base = fields["base"] ?: RdfArchitectToolWindowFactory.configuredUrl()
        if (base == null) {
            // The placeholder tab is about to close, so this has to be visible.
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(
                    project,
                    "No RDFArchitect instance to show ${localNameOf(iri)} in — set the RDFArchitect URL " +
                        "under Settings → Tools → CIMNotebook.",
                    "CIMNotebook",
                )
            }
            return
        }
        project.putUserData(RdfArchitectToolWindowFactory.PENDING_TERM_KEY, iri)
        RdfArchitectToolWindowFactory.openUrl(
            project,
            RdfArchitectToolWindowFactory.termDeepLink(base, iri, fields["dataset"], fields["graph"]),
        )
    }

    private fun localNameOf(iri: String): String = iri.substringAfterLast('#').substringAfterLast('/')

    companion object {
        /**
         * The fields of a term link's path — `class`, and where known `base`, `dataset` and
         * `graph` — or null when it is not one. The first segment after the authority is the
         * language server's percent-encoded `key=value` directive, Base64url-encoded because
         * LSP4IJ percent-decodes a location URI before resolving it.
         */
        internal fun fieldsOfLink(linkPath: String): Map<String, String>? {
            val payload = linkPath.split('/').getOrNull(1) ?: return null
            if (payload.isEmpty() || !payload.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                return null
            }
            val directive =
                try {
                    String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            return fields(directive).takeIf { "class" in it }
        }

        /** The directive's percent-encoded `key=value` pairs. */
        internal fun fields(directive: String): Map<String, String> =
            directive
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
    }
}
