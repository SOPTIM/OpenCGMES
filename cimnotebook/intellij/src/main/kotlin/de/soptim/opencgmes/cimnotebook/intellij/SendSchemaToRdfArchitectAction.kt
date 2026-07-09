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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.redhat.devtools.lsp4ij.LSPIJUtils
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * "CIMNotebook: Send Schema to RDFArchitect". Asks the CIMLangServer for the workspace schema
 * files (`cimvocabcheck.schemaInfo`), imports them into a fresh RDFArchitect session as a
 * read-only dataset, snapshots that dataset, and opens the snapshot link in the RDFArchitect tool
 * window — the embedded browser session loads the snapshot and selects the dataset, so the
 * workspace schema is browsable without a manual import.
 */
class SendSchemaToRdfArchitectAction : AnAction() {
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

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sending schema to RDFArchitect", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Resolving workspace schema…"
                    val info = requestSchemaInfo(project, docUri)
                    if (info == null) {
                        showInfo(
                            project,
                            "No schema configured — add schemas to opencgmes.jsonc first.",
                        )
                        return
                    }
                    val client = RdfArchitectClient(base)
                    val dataset = datasetNameFor(info.configFile)
                    indicator.text = "Importing ${info.schemaFiles.size} schema file(s)…"
                    client.importGraphs(dataset, info.schemaFiles.map(Path::of))
                    client.disableEditing(dataset)
                    indicator.text = "Creating snapshot…"
                    val token = client.createSnapshot(dataset)
                    val url = snapshotLink(base, dataset, token)
                    ApplicationManager.getApplication().invokeLater {
                        RdfArchitectToolWindowFactory.openUrl(project, url)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
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

    private fun showInfo(
        project: Project,
        message: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "CIMNotebook")
        }
    }

    private data class SchemaInfo(
        val configFile: String,
        val schemaFiles: List<String>,
    )

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
     * The link that loads the snapshot into the browser session and preselects the dataset —
     * RDFArchitect loads a snapshot under `SNAPSHOT_<dataset>_<token>`.
     */
    private fun snapshotLink(
        base: String,
        dataset: String,
        token: String,
    ): String {
        val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8)
        val encodedDataset = URLEncoder.encode("SNAPSHOT_${dataset}_$token", StandardCharsets.UTF_8)
        return base.trimEnd('/') + "/?snapshot=$encodedToken&dataset=$encodedDataset"
    }

    /**
     * Minimal client for the RDFArchitect REST API. RDFArchitect scopes datasets to the backend
     * session (`RDFA_SESSION_ID` cookie), so a cookie manager keeps the import, read-only, and
     * snapshot calls in one session.
     */
    private class RdfArchitectClient(
        base: String,
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
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
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

    companion object {
        private const val SERVER_ID = "cimvocabcheck-lsp"
        private const val CMD_SCHEMA_INFO = "cimvocabcheck.schemaInfo"
    }
}
