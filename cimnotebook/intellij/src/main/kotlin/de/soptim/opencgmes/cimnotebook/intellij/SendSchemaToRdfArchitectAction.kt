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

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.redhat.devtools.lsp4ij.LSPIJUtils

/**
 * "CIMNotebook: Send Schema to RDFArchitect". Asks the CIMLangServer for the workspace schema
 * files (`cimvocabcheck.schemaInfo`) and imports them into RDFArchitect, so the workspace schema is
 * browsable there without a manual import: into the tool window's own session when it is connected,
 * where the dataset stays editable and is read live, and otherwise as a read-only snapshot that the
 * browser session loads.
 *
 * Available from Tools, from the RDFArchitect tool window's toolbar, and offered automatically
 * when that tool window opens (see [RdfArchitectSchemaHandoff]).
 *
 * The presentation is set here rather than left to `plugin.xml`, because the tool window builds its
 * own instance for the title bar and only the one `ActionManager` creates is given the text and
 * description declared there — an instance without them is a blank button with no tooltip. The
 * menu entry keeps the longer, `CIMNotebook:`-prefixed wording from `plugin.xml`, which overrides
 * these on that instance.
 */
class SendSchemaToRdfArchitectAction :
    AnAction(
        "Send Schema",
        "Import the workspace schema files into RDFArchitect",
        AllIcons.Actions.Upload,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
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
        // Nearest-config resolution starts from the focused document when one is open.
        val docUri = e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { LSPIJUtils.toUriAsString(it) }
        RdfArchitectSchemaHandoff.send(project, base, docUri)
    }
}
