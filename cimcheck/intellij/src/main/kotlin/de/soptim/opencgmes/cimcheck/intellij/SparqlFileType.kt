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

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

/**
 * SPARQL file type (.rq, .sparql).
 *
 * Implements [FileType] directly instead of extending LanguageFileType. Highlighting is
 * provided by a lexer registered against this file type's NAME (see
 * CimcheckEditorHighlighterProvider) and semantics come from the CIMcheck language server,
 * so no IntelliJ [com.intellij.lang.Language] is needed.
 *
 * Binding a Language here is actively harmful: the previous implementation bound
 * PlainTextLanguage, which the built-in PLAIN_TEXT file type already owns. IntelliJ logs a
 * file-type/language association conflict for that and can refuse to register the
 * extensions — which shows up as "no syntax highlighting / file type not recognised".
 */
class SparqlFileType private constructor() : FileType {

    override fun getName()             = "SPARQL"
    override fun getDescription()      = "SPARQL query language"
    override fun getDefaultExtension() = "rq"
    override fun getIcon(): Icon?      = null
    override fun isBinary()            = false

    companion object {
        @JvmField
        val INSTANCE = SparqlFileType()
    }
}
