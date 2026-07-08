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
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import de.soptim.opencgmes.cimnotebook.intellij.settings.CimnotebookSettings
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * "RDFArchitect" tool window: embeds the RDFArchitect instance configured in
 * Settings → Tools → CIMNotebook via JCEF. RDFArchitect is a separately deployed web application
 * (local docker-compose or a hosted instance) — the plugin does not bundle or launch it.
 *
 * Fallbacks, in order: no URL configured → placeholder linking to the settings page; JCEF not
 * supported by the IDE runtime → placeholder linking to the external browser.
 */
class RdfArchitectToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = RdfArchitectPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(ReloadAction(panel), OpenInBrowserAction()))

        // Re-check the configured URL whenever the tool window is shown, so configuring or
        // changing it in settings takes effect without restarting the IDE.
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) {
                    if (shown.id == toolWindow.id) {
                        panel.refresh()
                    }
                }
            },
        )
        panel.refresh()
    }

    private class ReloadAction(
        private val panel: RdfArchitectPanel,
    ) : AnAction("Reload", "Reload RDFArchitect", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = panel.reload()
    }

    private class OpenInBrowserAction :
        AnAction(
            "Open in Browser",
            "Open RDFArchitect in the system browser",
            AllIcons.Nodes.PpWeb,
        ) {
        override fun actionPerformed(e: AnActionEvent) {
            configuredUrl()?.let { BrowserUtil.browse(it) }
        }
    }

    companion object {
        /** The configured RDFArchitect base URL, or null when unset. */
        fun configuredUrl(): String? =
            CimnotebookSettings
                .getInstance()
                .rdfArchitectUrl
                .trim()
                .ifEmpty { null }
    }
}

/**
 * Tool window content: a JCEF browser on the configured URL, or an explanatory placeholder when
 * no URL is configured / JCEF is unavailable.
 */
class RdfArchitectPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : JPanel(BorderLayout()) {
    private var browser: JBCefBrowser? = null
    private var shownUrl: String? = null

    /** (Re)builds the content if the configured URL changed since the last refresh. */
    fun refresh() {
        val url = RdfArchitectToolWindowFactory.configuredUrl()
        if (url == shownUrl && (url == null || browser != null)) {
            return
        }
        shownUrl = url
        removeAll()
        when {
            url == null -> add(placeholder(), BorderLayout.CENTER)
            !JBCefApp.isSupported() -> add(jcefUnsupported(url), BorderLayout.CENTER)
            else -> add(browserComponent(url), BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    /** Reloads the embedded page (no-op when the placeholder is showing). */
    fun reload() {
        refresh()
        browser?.cefBrowser?.reload()
    }

    /**
     * Navigates the embedded browser to [url] (e.g. a class deep link), falling back to the
     * system browser when JCEF is unavailable or no instance is configured.
     */
    fun openUrl(url: String) {
        refresh()
        val embedded = browser
        if (embedded != null) {
            embedded.loadURL(url)
        } else {
            BrowserUtil.browse(url)
        }
    }

    private fun browserComponent(url: String): javax.swing.JComponent {
        val existing = browser
        if (existing != null) {
            existing.loadURL(url)
            return existing.component
        }
        val created = JBCefBrowser.createBuilder().setUrl(url).build()
        Disposer.register(toolWindow.disposable, created)
        browser = created
        return created.component
    }

    private fun placeholder(): javax.swing.JComponent {
        val panel = JBPanelWithEmptyText()
        panel.emptyText.appendLine("No RDFArchitect instance configured.")
        panel.emptyText.appendLine(
            "Set the RDFArchitect URL in Settings",
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
        ) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "CIMNotebook")
        }
        return panel
    }

    private fun jcefUnsupported(url: String): javax.swing.JComponent {
        val panel = JBPanelWithEmptyText()
        panel.emptyText.appendLine("The IDE runtime does not support the embedded browser (JCEF).")
        panel.emptyText.appendLine(
            "Open RDFArchitect in the system browser",
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
        ) {
            BrowserUtil.browse(url)
        }
        return panel
    }
}
