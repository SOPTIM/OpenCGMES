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
package de.soptim.opencgmes.cimcheck.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.ExecuteCommandParams
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Create Config File (opencgmes.json)" action. Scaffolds an `opencgmes.json` in the project root.
 *
 * The generated file points CIMcheck at the CGMES profiles to validate against (without a schema —
 * or a `# [endpoint=...]` directive — validation is syntax-only). The template text is fetched from
 * the language server's `cimcheck.createConfig` command so the CLI and editors stay in sync, with a
 * minimal embedded fallback when the server is not running.
 */
class CreateConfigAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val target = Path.of(basePath, FILE_NAME)

        if (Files.exists(target)) {
            val choice =
                Messages.showYesNoDialog(
                    project,
                    "$FILE_NAME already exists. Overwrite it?",
                    "CIMcheck",
                    "Overwrite",
                    "Cancel",
                    null,
                )
            if (choice != Messages.YES) {
                openInEditor(project, target)
                return
            }
        }

        LanguageServerManager
            .getInstance(project)
            .getLanguageServer(SERVER_ID)
            .thenCompose { item ->
                item?.workspaceService?.executeCommand(
                    ExecuteCommandParams(CMD_CREATE_CONFIG, emptyList()),
                ) ?: java.util.concurrent.CompletableFuture
                    .completedFuture<Any?>(null)
            }.whenComplete { result, _ ->
                val content = (result as? String)?.takeIf { it.isNotBlank() } ?: FALLBACK
                ApplicationManager.getApplication().invokeLater {
                    writeAndOpen(project, target, content)
                }
            }
    }

    private fun writeAndOpen(
        project: Project,
        target: Path,
        content: String,
    ) {
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                Files.writeString(target, content)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            }
            openInEditor(project, target)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Could not create $FILE_NAME: ${ex.message}", "CIMcheck")
        }
    }

    private fun openInEditor(
        project: Project,
        target: Path,
    ) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    companion object {
        private const val SERVER_ID = "cimcheck-lsp"
        private const val CMD_CREATE_CONFIG = "cimcheck.createConfig"
        private const val FILE_NAME = "opencgmes.json"
        private val FALLBACK =
            """
            {
              "cimcheck": {
                // Point CIMcheck at your CGMES profiles; without them validation is syntax-only.
                // "schemasDirectory": "schemas",
                "strictness": "default"
              }
            }
            """.trimIndent() + "\n"
    }
}
