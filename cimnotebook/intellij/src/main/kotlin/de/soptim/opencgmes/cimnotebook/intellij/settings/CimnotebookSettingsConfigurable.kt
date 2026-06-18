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
package de.soptim.opencgmes.cimnotebook.intellij.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class CimnotebookSettingsConfigurable : Configurable {
    private var panel: JPanel? = null

    private val serverJarField = TextFieldWithBrowseButton()
    private val javaExeField = JBTextField()
    private val javaArgsField = JBTextField()

    override fun getDisplayName() = "CIMNotebook"

    override fun createComponent(): JComponent {
        val descriptor =
            FileChooserDescriptorFactory
                .createSingleFileDescriptor("jar")
                .withTitle("Select Server JAR")
                .withDescription(
                    "Select the cimvocabcheck-lsp.jar file. " +
                        "Leave empty to use the JAR bundled with the plugin.",
                )
        // Wire the browse button directly via addActionListener + FileChooser.chooseFile.
        // The addBrowseFolderListener(...) convenience overloads are all either deprecated,
        // scheduled-for-removal, or already removed across 2024.2–2026.1; these two APIs are
        // long-standing and stable across that range.
        serverJarField.addActionListener {
            FileChooser.chooseFile(descriptor, null, null)?.let { file ->
                serverJarField.text = file.path
            }
        }

        panel =
            FormBuilder
                .createFormBuilder()
                .addLabeledComponent(JBLabel("Server JAR:"), serverJarField, 1, false)
                .addLabeledComponent(JBLabel("Java executable:"), javaExeField, 1, false)
                .addLabeledComponent(JBLabel("JVM arguments:"), javaArgsField, 1, false)
                .addComponentFillVertically(JPanel(), 0)
                .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = CimnotebookSettings.getInstance()
        return serverJarField.text.trim() != s.serverJar ||
            javaExeField.text.trim() != s.javaExecutable ||
            javaArgsField.text.trim() != s.javaArgs
    }

    override fun apply() {
        val s = CimnotebookSettings.getInstance()
        s.serverJar = serverJarField.text.trim()
        s.javaExecutable = javaExeField.text.trim()
        s.javaArgs = javaArgsField.text.trim()
    }

    override fun reset() {
        val s = CimnotebookSettings.getInstance()
        serverJarField.text = s.serverJar
        javaExeField.text = s.javaExecutable
        javaArgsField.text = s.javaArgs
    }

    override fun disposeUIResources() {
        panel = null
    }
}
