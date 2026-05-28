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

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class CimcheckSettingsConfigurable : Configurable {

    private var panel: JPanel? = null

    private val serverJarField    = TextFieldWithBrowseButton()
    private val javaExeField      = JBTextField()
    private val javaArgsField     = JBTextField()

    override fun getDisplayName() = "CIMcheck"

    override fun createComponent(): JComponent {
        serverJarField.addBrowseFolderListener(
            "Select Server JAR",
            "Select the cimcheck-lsp.jar file. Leave empty to use the JAR bundled with the plugin.",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
        )

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server JAR:"),      serverJarField, 1, false)
            .addLabeledComponent(JBLabel("Java executable:"), javaExeField,   1, false)
            .addLabeledComponent(JBLabel("JVM arguments:"),   javaArgsField,  1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = CimcheckSettings.getInstance()
        return serverJarField.text.trim() != s.serverJar  ||
               javaExeField.text.trim()   != s.javaExecutable ||
               javaArgsField.text.trim()  != s.javaArgs
    }

    override fun apply() {
        val s = CimcheckSettings.getInstance()
        s.serverJar      = serverJarField.text.trim()
        s.javaExecutable = javaExeField.text.trim()
        s.javaArgs       = javaArgsField.text.trim()
    }

    override fun reset() {
        val s = CimcheckSettings.getInstance()
        serverJarField.text = s.serverJar
        javaExeField.text   = s.javaExecutable
        javaArgsField.text  = s.javaArgs
    }

    override fun disposeUIResources() {
        panel = null
    }
}
