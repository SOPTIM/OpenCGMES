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

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reads a SPARQL or SHACL file's RDFArchitect terms as soon as it is opened.
 *
 * Ctrl+hover asks [RdfArchitectGotoDeclarationHandler] for a target on the EDT, where waiting on
 * the language server is not allowed — so the first hover over a file can only answer from what is
 * already cached. Without this, that first hover silently offers nothing: no underline, no hint
 * that anything was supposed to happen.
 */
class RdfArchitectTermPrefetch : FileEditorManagerListener {
    override fun fileOpened(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        if (file.fileType != SparqlFileType.INSTANCE && file.fileType != ShaclFileType.INSTANCE) {
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        source.project.service<RdfArchitectTermLinks>().prefetch(file, document)
    }
}
