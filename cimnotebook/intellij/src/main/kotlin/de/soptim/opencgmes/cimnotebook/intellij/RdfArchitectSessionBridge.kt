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

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.net.ssl.CertificateManager
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.eclipse.lsp4j.ExecuteCommandParams
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Connects the RDFArchitect window of the tool window to the language server, so a workspace can
 * validate against the datasets shown there while they are being edited.
 *
 * RDFArchitect keeps one working copy per browser session and never publishes it, so reading those
 * datasets means addressing that session. Unlike a VS Code webview — a third-party iframe that has
 * to be asked over `postMessage` — the tool window *is* the plugin's own browser, so its session
 * cookie can simply be read out of it. That asks nothing of RDFArchitect: no session endpoint, no
 * opt-in flag, no assumption about where its API is served from.
 */
object RdfArchitectSessionBridge {
    private val LOG = Logger.getInstance(RdfArchitectSessionBridge::class.java)
    private const val SERVER_ID = "cimvocabcheck-lsp"
    private const val CMD_CONNECT = "cimvocabcheck.connectRdfArchitect"

    /** Where the connected instance is remembered; the session id belongs in the password safe. */
    private const val URL_KEY = "cimnotebook.rdfArchitect.session.url"

    /** One session per project, so the password safe entry needs no distinguishing user. */
    private const val CREDENTIAL_USER = "session"
    private const val SESSION_COOKIE = "RDFA_SESSION_ID"

    /** Marks an answer from the view's script as a failure rather than a session id. */
    private const val ERROR_PREFIX = "error: "

    /** The cookie store answers on its own thread; a view that never loaded must not hang us. */
    private const val COOKIE_TIMEOUT_MS = 5000L

    /** No instance may hold a caller — least of all the EDT — for longer than this. */
    private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(10)

    /** The window this project is connected to, if any. */
    data class Connection(
        val url: String,
        val id: String,
    )

    /**
     * The connection of one project.
     *
     * Per project, not per IDE: two projects can be open on two RDFArchitect windows, and each
     * one's language server has to be told about its own. Held as user data so it goes away with
     * the project rather than outliving it in this object.
     */
    private val CONNECTION_KEY = Key.create<Connection>("cimnotebook.rdfArchitect.connection")

    /** The instance currently readable by [project], for the tool window's status text. */
    fun connection(project: Project): Connection? = project.getUserData(CONNECTION_KEY)

    /**
     * Reads the session of [browser] on every load and hands it to the language server, so
     * navigating the view — or RDFArchitect handing out a new session — keeps the connection
     * current.
     *
     * [base] is asked each time rather than passed as a value: the browser is reused when the
     * configured instance changes, and a session reported under the previous instance is one the
     * language server pairs with the wrong URL and then declines to use.
     */
    fun attach(
        project: Project,
        browser: JBCefBrowser,
        base: () -> String,
    ) {
        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        query.addHandler { answer ->
            when {
                answer.isNullOrBlank() -> {
                    Unit
                }

                answer.startsWith(ERROR_PREFIX) -> {
                    LOG.info(
                        "RDFArchitect at ${base()} did not answer which session it uses (" +
                            answer.removePrefix(ERROR_PREFIX) +
                            "); reading the session cookie instead.",
                    )
                }

                else -> {
                    connect(project, base(), answer)
                }
            }
            null
        }
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int,
                ) {
                    if (frame?.isMain != true) {
                        return
                    }
                    readSessionCookie(project, browser, base())
                    askTheApp(cefBrowser, query)
                }
            },
            browser.cefBrowser,
        )
    }

    /**
     * Reads `RDFA_SESSION_ID` straight out of the embedded browser's cookie store.
     *
     * This is the reliable half of the handshake: it needs nothing of RDFArchitect — no session
     * endpoint, no opt-in flag, no assumption about where its API is served from — only that the
     * page set the cookie, which it must have to have a session at all. The cookie is `HttpOnly`,
     * hence the explicit flag; nothing leaves the IDE.
     */
    private fun readSessionCookie(
        project: Project,
        browser: JBCefBrowser,
        url: String,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                browser
                    .jbCefCookieManager
                    .getCookies(url, true)
                    .get(COOKIE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .firstOrNull { it.name == SESSION_COOKIE }
                    ?.value
                    ?.takeIf { it.isNotBlank() }
            }.onFailure {
                LOG.info("Could not read the RDFArchitect session cookie at $url: ${it.message}")
            }.getOrNull()
                ?.let { connect(project, url, it) }
        }
    }

    /**
     * Asks the app itself which session it is in, as a second opinion — it answers from the backend
     * it is actually configured to talk to, which the cookie store cannot tell us about.
     */
    private fun askTheApp(
        cefBrowser: CefBrowser?,
        query: JBCefJSQuery,
    ) {
        cefBrowser?.executeJavaScript(
            """
            (function () {
                const config = window.__RDFARCHITECT_CONFIG__ || {};
                const api = (config.PUBLIC_BACKEND_URL || '/api').replace(/\/+${'$'}/, '');
                fetch(api + '/session', { credentials: 'include' })
                    .then(r => r.ok ? r.json() : Promise.reject('HTTP ' + r.status))
                    .then(s => {
                        const answer = (s && s.id) ? s.id : '${ERROR_PREFIX}no id in the answer';
                        ${query.inject("answer")}
                    })
                    .catch(e => {
                        const answer = '${ERROR_PREFIX}' + e;
                        ${query.inject("answer")}
                    });
            })();
            """.trimIndent(),
            cefBrowser.url,
            0,
        )
    }

    /** Remembers the connection, tells the language server, and refreshes the tool window title. */
    fun connect(
        project: Project,
        url: String,
        sessionId: String,
    ) {
        val next = Connection(url.trimEnd('/'), sessionId)
        if (connection(project) == next) {
            return
        }
        project.putUserData(CONNECTION_KEY, next)
        remember(project, next)
        send(project, next)
        LOG.info("Connected to the RDFArchitect session at ${next.url}")
    }

    /**
     * Persists the connection: the instance in the project's own properties, the session id in the
     * password safe.
     *
     * The id is a credential — it is what makes a browser session's live datasets readable — so it
     * has no business in `workspace.xml`, which is plain text and travels with a copied project.
     */
    private fun remember(
        project: Project,
        session: Connection,
    ) {
        PropertiesComponent.getInstance(project).setValue(URL_KEY, session.url)
        PasswordSafe.instance.set(credentialsOf(project), Credentials(CREDENTIAL_USER, session.id))
    }

    /** Forgets both halves of what was remembered. */
    private fun forget(project: Project) {
        PropertiesComponent.getInstance(project).unsetValue(URL_KEY)
        PasswordSafe.instance.set(credentialsOf(project), null)
    }

    /**
     * Where this project's session id lives in the password safe.
     *
     * The Plugin Verifier reports one deprecated-API usage for this call on IDEs newer than the
     * 2024.2 baseline: there the constructor still takes a `requestor` class, and the overload that
     * replaced it does not exist yet to compile against.
     */
    private fun credentialsOf(project: Project): CredentialAttributes =
        CredentialAttributes(
            generateServiceName("CIMNotebook RDFArchitect", project.locationHash),
            CREDENTIAL_USER,
        )

    /**
     * Restores the connection remembered for this project, so validation keeps working across an
     * IDE restart without opening the tool window first.
     *
     * A backend session outlives the browser that created it, but not the backend itself. Asking
     * the instance which session an id names tells the two apart: a different answer means the
     * remembered session is gone.
     *
     * Reaches the network, so it must not be called on the EDT.
     */
    fun restore(project: Project) {
        val url = PropertiesComponent.getInstance(project).getValue(URL_KEY)
        val id = PasswordSafe.instance.get(credentialsOf(project))?.getPasswordAsString()
        if (url.isNullOrBlank() || id.isNullOrBlank()) {
            return
        }
        if (isAlive(url, id)) {
            val restored = Connection(url, id)
            project.putUserData(CONNECTION_KEY, restored)
            send(project, restored)
            LOG.info("Reconnected to the RDFArchitect session at $url")
        } else {
            forget(project)
            project.putUserData(CONNECTION_KEY, null)
            LOG.info("The remembered RDFArchitect session at $url is gone")
        }
    }

    /** Forgets the connection, locally and in the language server. */
    fun disconnect(project: Project) {
        project.putUserData(CONNECTION_KEY, null)
        forget(project)
        send(project, null)
    }

    private fun send(
        project: Project,
        session: Connection?,
    ) {
        runCatching {
            LanguageServerManager
                .getInstance(project)
                .getLanguageServer(SERVER_ID)
                .thenCompose { item ->
                    item?.workspaceService?.executeCommand(
                        ExecuteCommandParams(
                            CMD_CONNECT,
                            if (session == null) {
                                emptyList()
                            } else {
                                listOf<Any>(session.url, session.id)
                            },
                        ),
                    ) ?: java.util.concurrent.CompletableFuture
                        .completedFuture(null)
                }.get(10, TimeUnit.SECONDS)
        }.onFailure { LOG.info("Could not hand the RDFArchitect session to the server: ${it.message}") }
    }

    /**
     * Sends one request over a client that trusts what the IDE trusts, and releases that client
     * again.
     *
     * An RDFArchitect behind a company CA is not trusted by the JVM's own store, and the plain
     * client would fail the handshake even though the user has already accepted the certificate in
     * the IDE. Going through the platform's [CertificateManager] uses the IDE's trust store — and
     * its "accept this certificate?" flow — instead of a private one nobody can see.
     *
     * A client owns a selector thread and an executor, and these probes run on every project open,
     * every tool-window open and every reconnect; left to the garbage collector, those threads pile
     * up for as long as the IDE is open. The connect timeout is not optional either: an instance
     * that is simply gone would otherwise hold its caller for as long as the operating system takes
     * to give up on the connection.
     */
    internal fun probe(request: HttpRequest): HttpResponse<String> {
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .sslContext(CertificateManager.getInstance().sslContext)
                .build()
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        } finally {
            client.close()
        }
    }

    /**
     * Whether the instance still knows this session — it answers with the caller's own id.
     *
     * The id goes into the cookie exactly as it was read out of the browser, which is how every
     * other caller sends it (the language server, the schema handoff, the VS Code extension).
     * Encoding it here instead would address a session RDFArchitect has never heard of for any id
     * containing a character that gets escaped, earn a fresh one in reply, and report a perfectly
     * live session as gone on every project open.
     */
    private fun isAlive(
        url: String,
        id: String,
    ): Boolean =
        runCatching {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(url.trimEnd('/') + "/api/session"))
                    .header("Cookie", "$SESSION_COOKIE=$id")
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build()
            val response = probe(request)
            response.statusCode() < 400 && response.body().contains(id)
        }.getOrDefault(false)
}
