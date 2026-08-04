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

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.pom.PomNamedTarget
import com.intellij.pom.references.PomService
import com.intellij.psi.PsiElement

/**
 * Makes Ctrl+Click on a CIM term open it in the RDFArchitect tool window, for documents whose
 * schema comes from RDFArchitect.
 *
 * Such a document has no schema files on disk, so the language server has no declaration to return
 * and Ctrl+Click would simply do nothing. The term is resolved to a synthetic navigation target
 * instead — the navigation runs when the user actually clicks, not while merely hovering with Ctrl
 * held, which is when the IDE asks for the target.
 *
 * Documents backed by files or a SPARQL endpoint are untouched: the server answers those itself and
 * [RdfArchitectTermLinks] reports no terms for them.
 */
class RdfArchitectGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val language = sourceElement?.containingFile?.language ?: return null
        if (language != SparqlLanguage && language != ShaclLanguage) {
            return null
        }
        val document = editor?.document ?: return null
        val project = editor.project ?: sourceElement.project
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val (baseUrl, iri) =
            project.service<RdfArchitectTermLinks>().termAt(file, document, offset) ?: return null
        return arrayOf(PomService.convertToPsi(project, RdfArchitectTermTarget(project, baseUrl, iri)))
    }

    override fun getActionText(context: DataContext): String? = null

    /**
     * A navigation target that lives in RDFArchitect rather than in a file.
     *
     * A [PomNamedTarget] is used rather than a fake PSI element because the platform navigates it
     * by calling [navigate] — there is no source file, offset, or editor for it to open instead.
     */
    private class RdfArchitectTermTarget(
        private val project: Project,
        private val baseUrl: String,
        private val iri: String,
    ) : PomNamedTarget {
        override fun isValid(): Boolean = true

        override fun canNavigate(): Boolean = true

        override fun canNavigateToSource(): Boolean = true

        override fun getName(): String = iri.substringAfterLast('#').ifEmpty { iri }

        override fun navigate(requestFocus: Boolean) {
            // If this is what first opens the tool window and the schema was never sent, the import
            // offered there should land on the term the user asked for (see OpenInRdfArchitect).
            project.putUserData(RdfArchitectToolWindowFactory.PENDING_TERM_KEY, iri)
            RdfArchitectToolWindowFactory.openUrl(
                project,
                RdfArchitectToolWindowFactory.termDeepLink(baseUrl, iri),
            )
        }
    }
}
