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

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Supplies the Ctrl+hover (quick-navigate) tooltip for SPARQL and SHACL files.
 *
 * Without this, IntelliJ has no [com.intellij.lang.documentation.DocumentationProvider]
 * for these languages and falls back to a generic `LSP Psi Element "…" [file]` description
 * of the go-to-definition target resolved by LSP4IJ. LSP4IJ ships an equivalent provider
 * only for its semantic-token-backed files; languages with their own parser definition must
 * register one themselves. This provider asks the language server for hover documentation
 * at the source position, so Ctrl+hover shows the same content as the regular editor hover.
 *
 * Deliberately built on LSP4IJ's public API only (`LanguageServerManager`, `LSPIJUtils`,
 * `LSPDocumentationHelper`) — the shortcut of reusing LSP4IJ's `LSPDocumentationTarget`
 * fails the plugin verifier's internal-API gate (`getHtml()` is `@ApiStatus.Internal`).
 */
class CimnotebookDocumentationProvider : AbstractDocumentationProvider() {
    override fun getQuickNavigateInfo(
        element: PsiElement?,
        originalElement: PsiElement?,
    ): String? = lspHoverHtml(originalElement) ?: fallbackInfo(element, originalElement)

    private fun lspHoverHtml(originalElement: PsiElement?): String? {
        // Blocking waits below; quick-navigate info is normally computed on a background
        // thread, but if some code path ever asks on the EDT, degrade instead of freezing.
        if (ApplicationManager.getApplication().isDispatchThread) return null

        val file = originalElement?.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null

        val serverItem =
            LanguageServerManager
                .getInstance(file.project)
                .getLanguageServer(SERVER_ID)
                .await(SERVER_TIMEOUT_MS) ?: return null
        val server = serverItem.initializedServer.await(SERVER_TIMEOUT_MS) ?: return null
        val params =
            HoverParams(
                TextDocumentIdentifier(LSPIJUtils.toUriAsString(virtualFile)),
                LSPIJUtils.toPosition(originalElement.textOffset, document),
            )
        val hover =
            server.textDocumentService
                .hover(params)
                .await(HOVER_TIMEOUT_MS) ?: return null
        val contents = LSPDocumentationHelper.getValidMarkupContents(hover)
        if (contents.isEmpty()) return null
        return LSPDocumentationHelper
            .convertToHtml(contents, serverItem, file)
            .trim()
            .ifEmpty { null }
    }

    /** Minimal `token in file` info for when the server returns no hover content. */
    private fun fallbackInfo(
        element: PsiElement?,
        originalElement: PsiElement?,
    ): String? {
        val name = originalElement?.text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val targetFile = element?.containingFile?.takeIf { it != originalElement.containingFile }
        val html =
            StringBuilder("<html><code>")
                .append(StringUtil.escapeXmlEntities(name))
                .append("</code>")
        if (targetFile != null) {
            html
                .append(" in <code>")
                .append(StringUtil.escapeXmlEntities(targetFile.name))
                .append("</code>")
        }
        return html.append("</html>").toString()
    }

    private fun <T> CompletableFuture<T>.await(timeoutMs: Long): T? =
        try {
            get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            cancel(true)
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: CancellationException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    companion object {
        /** Must match the server id registered in plugin.xml. */
        private const val SERVER_ID = "cimvocabcheck-lsp"

        /** The server is already running for any file shown in an editor; this only
         * covers a race with a server restart. */
        private const val SERVER_TIMEOUT_MS = 1000L

        /** Tooltips must stay snappy — on timeout fall back to the minimal info. */
        private const val HOVER_TIMEOUT_MS = 1000L
    }
}
