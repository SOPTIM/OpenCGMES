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
package de.soptim.opencgmes.cimcheck.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "CimcheckSettings",
    storages = [Storage("cimcheck.xml")]
)
class CimcheckSettings : PersistentStateComponent<CimcheckSettings.State> {

    data class State(
        @JvmField var serverJar: String      = "",
        // Empty by default so CimcheckServerConnectionProvider.resolveJavaExecutable falls through
        // to IntelliJ's bundled JBR (always Java 21+). Defaulting to "java" here would short-circuit
        // that logic and break the plugin on machines where "java" is not on PATH (common on Windows).
        @JvmField var javaExecutable: String = "",
        @JvmField var javaArgs: String       = ""
    )

    private var _state = State()

    override fun getState(): State = _state.copy()

    override fun loadState(state: State) {
        _state = state.copy()
    }

    /** Absolute path to cimcheck-lsp.jar, or empty to use the bundled JAR. */
    var serverJar: String
        get() = _state.serverJar
        set(v) { _state.serverJar = v }

    /**
     * Java executable used to launch the language server (must be Java 21+).
     * Empty means "auto": use IntelliJ's own bundled JBR.
     */
    var javaExecutable: String
        get() = _state.javaExecutable
        set(v) { _state.javaExecutable = v }

    /** Additional JVM arguments, space-separated, passed before -jar. */
    var javaArgs: String
        get() = _state.javaArgs
        set(v) { _state.javaArgs = v }

    companion object {
        @JvmStatic
        fun getInstance(): CimcheckSettings = service()
    }
}
