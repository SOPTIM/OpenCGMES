/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.soptim.opencgmes.cimcheck.intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import de.soptim.opencgmes.cimcheck.intellij.settings.CimcheckSettings
import java.io.File
import java.nio.file.Files

/**
 * Launches cimcheck-lsp.jar as a subprocess and connects to it via stdio.
 *
 * JAR resolution order (same as the VS Code extension):
 *  1. Explicit path in Settings > Tools > CIMcheck > Server JAR.
 *  2. cimcheck-lsp.jar bundled inside this plugin (extracted to IntelliJ's
 *     system cache directory on first use, keyed by plugin version).
 */
class CimcheckServerConnectionProvider : LanguageServerFactory {

    override fun createConnectionProvider(project: Project): ProcessStreamConnectionProvider {
        val settings = CimcheckSettings.getInstance()
        val javaExe  = resolveJavaExecutable(settings.javaExecutable)
        val jarPath  = resolveServerJar(settings.serverJar)
        val extra    = settings.javaArgs.trim()
            .splitToSequence("\\s+".toRegex())
            .filter(String::isNotBlank)
            .toList()
        val cmd      = listOf(javaExe) + extra + listOf("-jar", jarPath)
        val workDir  = project.basePath

        // ProcessStreamConnectionProvider is abstract in LSP4IJ 0.7+;
        // subclass it inline and supply the command list via getCommands().
        return object : ProcessStreamConnectionProvider() {
            override fun getCommands(): List<String>  = cmd
            override fun getWorkingDirectory(): String? = workDir
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Resolves the java executable to use for launching the LSP server.
     *
     * Resolution order:
     *  1. Explicit value from Settings > Tools > CIMcheck > Java executable.
     *  2. IntelliJ's own bundled JBR — `java.home` points to the JRE running the
     *     current IDE process, which is always Java 21+ for IntelliJ 2024.1+.
     *     This is the right default: `java` may not be on the system PATH (common
     *     on Windows), but IntelliJ's JBR is always present and always the right version.
     *  3. Bare `"java"` as a last resort (relies on the system PATH).
     */
    private fun resolveJavaExecutable(configured: String): String {
        if (configured.isNotBlank()) return configured.trim()

        val javaHome = System.getProperty("java.home") ?: ""
        if (javaHome.isNotBlank()) {
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val javaBin = File(javaHome, "bin/java${if (isWindows) ".exe" else ""}")
            if (javaBin.canExecute()) return javaBin.absolutePath
        }

        return "java"
    }

    private fun resolveServerJar(configured: String): String {
        // 1. Explicit user setting.
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.exists()) return f.absolutePath
            throw IllegalStateException(
                "CIMcheck: configured server JAR not found: $configured\n" +
                "Check Settings > Tools > CIMcheck > Server JAR."
            )
        }

        // 2. Extract the bundled resource to IntelliJ's system cache.
        val version  = pluginVersion()
        val cacheDir = File(PathManager.getSystemPath(), "cimcheck")
        val cached   = File(cacheDir, "cimcheck-lsp-$version.jar")
        if (!cached.exists()) {
            val stream = javaClass.classLoader.getResourceAsStream("server/cimcheck-lsp.jar")
                ?: throw IllegalStateException(
                    "CIMcheck: cimcheck-lsp.jar not found in plugin resources.\n" +
                    "Either set a path in Settings > Tools > CIMcheck > Server JAR, " +
                    "or reinstall the plugin."
                )
            Files.createDirectories(cacheDir.toPath())
            stream.use { input -> cached.outputStream().use { input.copyTo(it) } }
        }
        return cached.absolutePath
    }

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId("de.soptim.opencgmes.cimcheck"))
            ?.version ?: "unknown"
}
