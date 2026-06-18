/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.soptim.opencgmes.cimnotebook.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.ExecuteCommandParams

/**
 * "Explain Query (Algebra Plan)" editor action. Sends the current selection (or the whole document
 * when nothing is selected) to the CIMLangServer's `cimvocabcheck.explainQuery` command and
 * opens the returned algebra plan in a read-only editor tab.
 *
 * The server command is invoked through LSP4IJ's stable {@link LanguageServerManager} entry point
 * rather than any internal API, so it survives platform/LSP4IJ changes across supported IDE builds.
 */
class ExplainQueryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible =
            editor != null && file?.language == SparqlLanguage
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text =
            editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
                ?: editor.document.text

        LanguageServerManager
            .getInstance(project)
            .getLanguageServer(SERVER_ID)
            .thenCompose { item ->
                if (item == null) {
                    throw IllegalStateException(
                        "CIMLangServer is not running. Open a SPARQL file first.",
                    )
                }
                item.workspaceService.executeCommand(
                    ExecuteCommandParams(CMD_EXPLAIN_QUERY, listOf<Any>(text)),
                )
            }.whenComplete { result, error ->
                ApplicationManager.getApplication().invokeLater {
                    if (error != null) {
                        Messages.showErrorDialog(
                            project,
                            "Explain Query failed: ${error.message}",
                            "CIMNotebook",
                        )
                    } else {
                        openPlan(project, result?.toString() ?: "(no plan returned)")
                    }
                }
            }
    }

    private fun openPlan(
        project: Project,
        plan: String,
    ) {
        val vf = LightVirtualFile("CIMNotebook Query Plan.rq", SparqlFileType.INSTANCE, plan)
        vf.isWritable = false
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    companion object {
        private const val SERVER_ID = "cimvocabcheck-lsp"
        private const val CMD_EXPLAIN_QUERY = "cimvocabcheck.explainQuery"
    }
}
