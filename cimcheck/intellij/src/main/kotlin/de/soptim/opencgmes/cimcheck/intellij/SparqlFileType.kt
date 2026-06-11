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

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * SPARQL file type (.rq, .sparql).
 *
 * Extends [LanguageFileType] with the dedicated [SparqlLanguage] singleton so that
 * IntelliJ's editor highlighter pipeline (via [CimcheckEditorHighlighterProvider]) fires
 * correctly. Using a dedicated Language avoids the conflict that occurred when this type
 * previously shared PlainTextLanguage with the built-in PLAIN_TEXT file type.
 */
class SparqlFileType private constructor() : LanguageFileType(SparqlLanguage) {

    override fun getName()             = "SPARQL"
    override fun getDescription()      = "SPARQL query language"
    override fun getDefaultExtension() = "rq"
    override fun getIcon(): Icon?      = null

    companion object {
        @JvmField
        val INSTANCE = SparqlFileType()
    }
}
