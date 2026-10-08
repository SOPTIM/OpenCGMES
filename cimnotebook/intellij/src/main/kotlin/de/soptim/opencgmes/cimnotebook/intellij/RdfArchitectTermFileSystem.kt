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

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the language server's go-to-definition links into RDFArchitect —
 * `rdfarchitect://term/<payload>/<profile>/<name>` — to in-memory placeholder files, so that
 * LSP4IJ has something to navigate to without anything being written to disk.
 *
 * Navigating to one never shows it: [RdfArchitectDefinitionOpener] opens the term in the tool
 * window and closes the placeholder again. A file is kept per link, so the target LSP4IJ resolves
 * while the user hovers is the same one it navigates to on the click.
 */
class RdfArchitectTermFileSystem : DeprecatedVirtualFileSystem() {
    private val files = ConcurrentHashMap<String, VirtualFile>()

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? {
        RdfArchitectDefinitionOpener.fieldsOfLink(path) ?: return null
        return files.computeIfAbsent(path) { TermLinkFile(this, it) }
    }

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

    override fun refresh(asynchronous: Boolean) {
        // Nothing to refresh: a link's placeholder never changes.
    }

    /** What the placeholder reads as where the IDE shows a target without navigating to it. */
    private class TermLinkFile(
        private val fileSystem: VirtualFileSystem,
        private val linkPath: String,
    ) : LightVirtualFile(
            linkPath.substringAfterLast('/'),
            PlainTextFileType.INSTANCE,
            "${linkPath.substringAfterLast('/')} (${profileOf(linkPath)}) — opens in RDFArchitect.\n",
        ) {
        init {
            isWritable = false
        }

        override fun getFileSystem(): VirtualFileSystem = fileSystem

        override fun getPath(): String = linkPath
    }

    companion object {
        const val PROTOCOL = "rdfarchitect"

        private fun profileOf(linkPath: String): String = linkPath.split('/').getOrElse(2) { "" }
    }
}
