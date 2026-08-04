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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.ExecuteCommandParams
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Hands the workspace's configured schema to RDFArchitect and keeps track of what was handed over.
 *
 * The import runs in its own RDFArchitect session and reaches the tool window's browser session
 * through a snapshot; since every send starts a fresh session, the dataset is always built from
 * scratch and re-sending an updated schema needs no cleanup. What was sent is remembered per
 * project ([Handoff]) so the tool window can offer an import when nothing is over there yet, and an
 * update once the schema files have changed.
 */
object RdfArchitectSchemaHandoff {
    private val LOG = Logger.getInstance(RdfArchitectSchemaHandoff::class.java)
    private const val SERVER_ID = "cimvocabcheck-lsp"
    private const val CMD_SCHEMA_INFO = "cimvocabcheck.schemaInfo"
    private const val HANDOFF_KEY = "cimnotebook.rdfArchitect.handoff"
    private const val OPT_OUT_KEY = "cimnotebook.rdfArchitect.handoff.optOut"

    /** What was last sent, to which instance. */
    data class Handoff(
        val url: String,
        val dataset: String,
        /** The snapshot bridging into the view's session, or null when it went to that session. */
        val snapshot: String?,
        val fingerprint: String,
        val sentAt: String,
    )

    private data class SchemaInfo(
        val configFile: String,
        val schemaFiles: List<String>,
    )

    /**
     * Imports the workspace schema and opens the result in the tool window.
     *
     * @param docUri the focused document, so nearest-config resolution starts there
     * @param termIri a term to land on afterwards — used when the send was offered because that
     *     term could not be found
     */
    fun send(
        project: Project,
        base: String,
        docUri: String?,
        termIri: String? = null,
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sending schema to RDFArchitect", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Resolving workspace schema…"
                    val info = requestSchemaInfo(project, docUri)
                    if (info == null) {
                        invokeLater {
                            Messages.showInfoMessage(
                                project,
                                "No schema configured — add schemas to opencgmes.jsonc first.",
                                "CIMNotebook",
                            )
                        }
                        return
                    }
                    // With a connected view the import goes into *its* session, so the dataset is
                    // the one on screen — editable, and read live by the language server. Without
                    // one, a snapshot is still the only bridge into whatever session the view gets.
                    val session =
                        RdfArchitectSessionBridge.connection()?.takeIf { it.url == base.trimEnd('/') }
                    val client = RdfArchitectClient(base, session?.id)
                    val dataset = datasetNameFor(info.configFile)
                    indicator.text = "Importing ${info.schemaFiles.size} schema file(s)…"
                    client.importGraphs(dataset, info.schemaFiles.map(Path::of))
                    var token: String? = null
                    if (session == null) {
                        client.disableEditing(dataset)
                        indicator.text = "Creating snapshot…"
                        token = client.createSnapshot(dataset)
                    }
                    remember(
                        project,
                        Handoff(
                            url = base,
                            dataset = dataset,
                            snapshot = token,
                            fingerprint = fingerprint(info.schemaFiles),
                            sentAt =
                                java.time.Instant
                                    .now()
                                    .toString(),
                        ),
                    )
                    val url = datasetLink(base, dataset, token, termIri)
                    invokeLater { RdfArchitectToolWindowFactory.openUrl(project, url) }
                }

                override fun onThrowable(error: Throwable) {
                    invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Send Schema to RDFArchitect failed: ${error.message}",
                            "CIMNotebook",
                        )
                    }
                }
            },
        )
    }

    /**
     * Offers to send the schema when the tool window opens, so the feature does not depend on the
     * user finding the action.
     *
     * The plugin's REST calls and the tool window's browser are *different* backend sessions, so
     * RDFArchitect cannot be asked whether the embedded app sees the schema. What can be checked is
     * what this project sent: the snapshot bridging the two still resolves as long as the backend
     * has it — an instance that restarted loses in-memory snapshots, which reads exactly like never
     * having imported. The schema files are fingerprinted as well, so a schema edited after the
     * last send offers an update instead of silently serving a stale model.
     */
    fun offerIfNeeded(
        project: Project,
        base: String,
        termIri: String?,
    ) {
        if (PropertiesComponent.getInstance(project).getBoolean(OPT_OUT_KEY)) {
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Checking RDFArchitect schema", false) {
                override fun run(indicator: ProgressIndicator) {
                    val info = requestSchemaInfo(project, null) ?: return
                    val previous = remembered(project)
                    val live = previous?.takeIf { it.url == base && stillThere(base, it) }
                    if (live?.fingerprint == fingerprint(info.schemaFiles)) {
                        return
                    }
                    invokeLater { prompt(project, base, live != null, termIri) }
                }

                override fun onThrowable(error: Throwable) {
                    // Never let the offer get in the way of opening the tool window.
                    LOG.info("RDFArchitect schema check failed: ${error.message}")
                }
            },
        )
    }

    private fun prompt(
        project: Project,
        base: String,
        stale: Boolean,
        termIri: String?,
    ) {
        val message =
            if (stale) {
                "The workspace schema changed since it was sent to RDFArchitect. Update it?"
            } else {
                "This project's schema is not in RDFArchitect yet. Import it?"
            }
        val choice =
            Messages.showYesNoCancelDialog(
                project,
                message,
                "CIMNotebook",
                if (stale) "Update" else "Import",
                "Not Now",
                "Never for This Project",
                Messages.getQuestionIcon(),
            )
        when (choice) {
            Messages.YES -> send(project, base, null, termIri)
            Messages.CANCEL -> PropertiesComponent.getInstance(project).setValue(OPT_OUT_KEY, true)
            else -> Unit
        }
    }

    private fun invokeLater(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action)

    private fun remember(
        project: Project,
        handoff: Handoff,
    ) = PropertiesComponent.getInstance(project).setValue(HANDOFF_KEY, Gson().toJson(handoff))

    private fun remembered(project: Project): Handoff? =
        PropertiesComponent
            .getInstance(project)
            .getValue(HANDOFF_KEY)
            ?.let {
                runCatching { Gson().fromJson(it, Handoff::class.java) }.getOrNull()
            }

    /**
     * Whether what was sent is still over there, which differs by how it was sent: a snapshot has to
     * still load (loading it into this throwaway session is the probe), while a dataset sent into
     * the connected view has to still be in that view's session.
     */
    private fun stillThere(
        base: String,
        handoff: Handoff,
    ): Boolean =
        runCatching {
            val session =
                RdfArchitectSessionBridge.connection()?.takeIf { it.url == base.trimEnd('/') }
            val builder = HttpRequest.newBuilder().GET()
            if (handoff.snapshot != null) {
                builder.uri(
                    URI.create(
                        base.trimEnd('/') + "/api/snapshots/" +
                            URLEncoder.encode(handoff.snapshot, StandardCharsets.UTF_8),
                    ),
                )
            } else {
                if (session == null) {
                    return@runCatching false
                }
                builder.uri(URI.create(base.trimEnd('/') + "/api/datasets"))
                builder.header("Cookie", "RDFA_SESSION_ID=" + session.id)
            }
            val response =
                HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
            response.statusCode() < 400 &&
                (handoff.snapshot != null || response.body().contains("\"" + handoff.dataset + "\""))
        }.getOrDefault(false)

    /** Identifies a set of schema files by their paths and contents, to detect edits since a send. */
    private fun fingerprint(files: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files.sorted()) {
            digest.update(file.toByteArray(StandardCharsets.UTF_8))
            digest.update(Files.readAllBytes(Path.of(file)))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Blocks (on the background task thread) for the server's schemaInfo result. */
    private fun requestSchemaInfo(
        project: Project,
        docUri: String?,
    ): SchemaInfo? {
        val result =
            LanguageServerManager
                .getInstance(project)
                .getLanguageServer(SERVER_ID)
                .thenCompose { item ->
                    if (item == null) {
                        throw IllegalStateException(
                            "CIMLangServer is not running. Open a SPARQL or SHACL file first.",
                        )
                    }
                    item.workspaceService.executeCommand(
                        ExecuteCommandParams(CMD_SCHEMA_INFO, listOfNotNull<Any>(docUri)),
                    )
                }.get(30, TimeUnit.SECONDS)
        return extractSchemaInfo(result)
    }

    /** The schemaInfo result fields, tolerating Gson and in-process shapes. */
    private fun extractSchemaInfo(result: Any?): SchemaInfo? =
        when (result) {
            is JsonObject -> {
                val config = result.get("configFile")?.takeIf { it.isJsonPrimitive }?.asString
                val files =
                    result
                        .getAsJsonArray("schemaFiles")
                        ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
                if (config != null && !files.isNullOrEmpty()) SchemaInfo(config, files) else null
            }

            is Map<*, *> -> {
                val config = result["configFile"] as? String
                val files = (result["schemaFiles"] as? List<*>)?.filterIsInstance<String>()
                if (config != null && !files.isNullOrEmpty()) SchemaInfo(config, files) else null
            }

            else -> {
                null
            }
        }

    /** Dataset name for the imported schema: the config file's directory name, sanitised. */
    private fun datasetNameFor(configFile: String): String {
        val dir =
            Path
                .of(configFile)
                .parent
                ?.fileName
                ?.toString()
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return dir?.ifEmpty { null } ?: "cimnotebook"
    }

    /**
     * The link that opens what was just imported: the dataset itself when it went into the view's
     * own session, or the snapshot that bridges into it otherwise — RDFArchitect loads a snapshot
     * under `SNAPSHOT_<dataset>_<token>`. A [termIri] is forwarded so the app lands on that term.
     */
    private fun datasetLink(
        base: String,
        dataset: String,
        token: String?,
        termIri: String?,
    ): String {
        val term = termIri?.let { "&class=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: ""
        if (token == null) {
            return base.trimEnd('/') + "/?dataset=" +
                URLEncoder.encode(dataset, StandardCharsets.UTF_8) + term
        }
        val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8)
        val encodedDataset = URLEncoder.encode("SNAPSHOT_${dataset}_$token", StandardCharsets.UTF_8)
        return base.trimEnd('/') + "/?snapshot=$encodedToken&dataset=$encodedDataset$term"
    }

    /**
     * Minimal client for the RDFArchitect REST API. RDFArchitect scopes datasets to the backend
     * session (`RDFA_SESSION_ID` cookie), so a cookie manager keeps the import, read-only, and
     * snapshot calls in one session.
     */
    private class RdfArchitectClient(
        base: String,
        private val sessionId: String? = null,
    ) {
        private val api = base.trimEnd('/') + "/api"
        private val http = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

        fun importGraphs(
            dataset: String,
            files: List<Path>,
        ) {
            val boundary = "----cimnotebook" + UUID.randomUUID().toString().replace("-", "")
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$api/datasets/${encode(dataset)}/graphs/content"))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, files)))
                    .build()
            val response = send(request)
            val failed =
                JsonParser
                    .parseString(response.body())
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.getAsJsonArray("failedImports")
            if (failed != null && !failed.isEmpty) {
                throw IOException("RDFArchitect could not parse: $failed")
            }
        }

        /** Marks the dataset read-only (DELETE on the readonly resource disables editing). */
        fun disableEditing(dataset: String) {
            send(
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$api/datasets/${encode(dataset)}/readonly"))
                    .DELETE()
                    .build(),
            )
        }

        fun createSnapshot(dataset: String): String =
            send(
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$api/snapshots"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(dataset))
                    .build(),
            ).body().trim()

        private fun send(request: HttpRequest): HttpResponse<String> {
            val withSession =
                if (sessionId == null) {
                    request
                } else {
                    // Work in the view's session, so the import lands where the user can see it.
                    HttpRequest
                        .newBuilder(request, { _, _ -> true })
                        .header("Cookie", "RDFA_SESSION_ID=" + sessionId)
                        .build()
                }
            val response = http.send(withSession, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 400) {
                throw IOException(
                    "${request.method()} ${request.uri().path} → HTTP ${response.statusCode()}" +
                        response.body().take(200).let { if (it.isEmpty()) "" else " — $it" },
                )
            }
            return response
        }

        private fun multipartBody(
            boundary: String,
            files: List<Path>,
        ): ByteArray {
            val out = ByteArrayOutputStream()

            fun write(s: String) = out.write(s.toByteArray(StandardCharsets.UTF_8))
            for (file in files) {
                write("--$boundary\r\n")
                write(
                    "Content-Disposition: form-data; name=\"files\"; " +
                        "filename=\"${file.fileName}\"\r\n",
                )
                write("Content-Type: application/octet-stream\r\n\r\n")
                out.write(Files.readAllBytes(file))
                write("\r\n")
            }
            write("--$boundary--\r\n")
            return out.toByteArray()
        }

        private fun encode(segment: String): String = URLEncoder.encode(segment, StandardCharsets.UTF_8)
    }
}
