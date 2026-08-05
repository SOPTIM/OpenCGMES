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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import de.soptim.opencgmes.cimnotebook.intellij.settings.CimnotebookSettings
import java.awt.BorderLayout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
        toolWindow.setTitleActions(
            listOf(
                SendSchemaToRdfArchitectAction(),
                ReconnectSessionAction(panel),
                ReloadAction(panel),
                OpenInBrowserAction(),
            ),
        )
        // Asking the instance whether the remembered session is still there is a network call, and
        // this runs on the EDT — an instance that is down would otherwise freeze the IDE for as
        // long as the probe takes.
        ApplicationManager.getApplication().executeOnPooledThread {
            RdfArchitectSessionBridge.restore(project)
        }

        // Opening the tool window is the moment to notice that RDFArchitect has no schema of this
        // project yet, or an outdated one. A term the user was navigating to is picked up so the
        // import can land there (see OpenInRdfArchitectAction).
        configuredUrl()?.let { base ->
            val termIri = project.getUserData(PENDING_TERM_KEY)
            project.putUserData(PENDING_TERM_KEY, null)
            RdfArchitectSchemaHandoff.offerIfNeeded(project, base, termIri)
        }

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

    /** Drops the connection and reloads, which makes the view report its session again. */
    private class ReconnectSessionAction(
        private val panel: RdfArchitectPanel,
    ) : AnAction(
            "Reconnect Session",
            "Read the RDFArchitect session of this view again, e.g. after restarting the instance",
            AllIcons.Actions.Refresh,
        ) {
        override fun actionPerformed(e: AnActionEvent) {
            e.project?.let { RdfArchitectSessionBridge.disconnect(it) }
            panel.reload()
        }
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
        /**
         * The term a deep link is heading for, set before the tool window is opened so a first-time
         * schema import can land on it. Read and cleared when the tool window content is created.
         */
        val PENDING_TERM_KEY: Key<String> = Key.create("cimnotebook.rdfArchitect.pendingTerm")

        /** The instance root of a URL the view may have been navigated to. */
        fun baseOf(url: String): String = configuredUrl()?.trimEnd('/') ?: url.trimEnd('/')

        /**
         * RDFArchitect's deep link for a term. Every kind of term uses the `class` parameter: a
         * class opens itself, an attribute, association or enum entry opens the class declaring it.
         *
         * [dataset] and [graph] narrow the lookup — a term is routinely declared in several
         * profiles, and without them RDFArchitect opens whichever graph it finds it in first.
         */
        fun termDeepLink(
            base: String,
            iri: String,
            dataset: String? = null,
            graph: String? = null,
        ): String {
            val url = StringBuilder(base.trimEnd('/'))
            url.append("/mainpage?class=").append(encode(iri))
            dataset?.takeIf { it.isNotBlank() }?.let { url.append("&dataset=").append(encode(it)) }
            graph?.takeIf { it.isNotBlank() }?.let { url.append("&graph=").append(encode(it)) }
            return url.toString()
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        /** The configured RDFArchitect base URL, or null when unset. */
        fun configuredUrl(): String? =
            CimnotebookSettings
                .getInstance()
                .rdfArchitectUrl
                .trim()
                .ifEmpty { null }

        /**
         * Opens [url] in the RDFArchitect tool window, falling back to the system browser when
         * the tool window (or JCEF) is unavailable.
         */
        fun openUrl(
            project: Project,
            url: String,
        ) {
            val toolWindow =
                ToolWindowManager
                    .getInstance(project)
                    .getToolWindow(OpenRdfArchitectAction.TOOL_WINDOW_ID)
            if (toolWindow == null) {
                BrowserUtil.browse(url)
                return
            }
            toolWindow.activate {
                val panel =
                    toolWindow.contentManager.contents
                        .firstOrNull()
                        ?.component as? RdfArchitectPanel
                panel?.openUrl(url) ?: BrowserUtil.browse(url)
            }
        }
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
        // The tool window is our own browser, so its RDFArchitect session can be read directly and
        // handed to the language server — that is what makes live datasets readable.
        RdfArchitectSessionBridge.attach(project, created, RdfArchitectToolWindowFactory.baseOf(url))
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
