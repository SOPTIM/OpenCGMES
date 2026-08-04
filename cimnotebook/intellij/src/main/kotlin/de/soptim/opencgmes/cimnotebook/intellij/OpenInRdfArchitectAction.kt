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

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.ExecuteCommandParams

/**
 * "Open in RDFArchitect" editor action. Resolves the schema term under the caret via the
 * CIMLangServer's `cimvocabcheck.termInfo` command and opens RDFArchitect's class deep link
 * (`/mainpage?class=<iri>`) in the RDFArchitect tool window. RDFArchitect locates the class
 * across the schemas loaded in its session.
 */
class OpenInRdfArchitectAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val language = e.getData(CommonDataKeys.PSI_FILE)?.language
        e.presentation.isEnabledAndVisible =
            editor != null && (language == SparqlLanguage || language == ShaclLanguage)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val base = RdfArchitectToolWindowFactory.configuredUrl()
        if (base == null) {
            Messages.showErrorDialog(
                project,
                "No RDFArchitect instance configured — set the RDFArchitect URL under " +
                    "Settings → Tools → CIMNotebook.",
                "CIMNotebook",
            )
            return
        }

        val uri = LSPIJUtils.toUriAsString(virtualFile)
        val position = LSPIJUtils.toPosition(editor.caretModel.offset, editor.document)

        LanguageServerManager
            .getInstance(project)
            .getLanguageServer(SERVER_ID)
            .thenCompose { item ->
                if (item == null) {
                    throw IllegalStateException(
                        "CIMLangServer is not running. Open a SPARQL or SHACL file first.",
                    )
                }
                item.workspaceService.executeCommand(
                    ExecuteCommandParams(
                        CMD_TERM_INFO,
                        listOf<Any>(uri, position.line, position.character),
                    ),
                )
            }.whenComplete { result, error ->
                ApplicationManager.getApplication().invokeLater {
                    val iri = extractIri(result)
                    when {
                        error != null -> {
                            Messages.showErrorDialog(
                                project,
                                "Open in RDFArchitect failed: ${error.message}",
                                "CIMNotebook",
                            )
                        }

                        iri == null -> {
                            Messages.showInfoMessage(
                                project,
                                "No schema term at the cursor position.",
                                "CIMNotebook",
                            )
                        }

                        else -> {
                            openDeepLink(project, base, iri)
                        }
                    }
                }
            }
    }

    private fun openDeepLink(
        project: Project,
        base: String,
        iri: String,
    ) {
        // If this is what first opens the tool window and the schema was never sent, the import
        // offered there should land on the term the user asked for.
        project.putUserData(RdfArchitectToolWindowFactory.PENDING_TERM_KEY, iri)
        RdfArchitectToolWindowFactory.openUrl(
            project,
            RdfArchitectToolWindowFactory.termDeepLink(base, iri),
        )
    }

    /** The `iri` field of a termInfo result, tolerating Gson and in-process shapes. */
    private fun extractIri(result: Any?): String? =
        when (result) {
            is JsonObject -> {
                result.get("iri")?.takeIf { it.isJsonPrimitive }?.asString
            }

            is Map<*, *> -> {
                result["iri"] as? String
            }

            else -> {
                null
            }
        }

    companion object {
        private const val SERVER_ID = "cimvocabcheck-lsp"
        private const val CMD_TERM_INFO = "cimvocabcheck.termInfo"
    }
}
