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
package de.soptim.opencgmes.cimnotebook.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * SHACL / Turtle file type (.ttl, .shacl).
 *
 * Extends [LanguageFileType] with the dedicated [ShaclLanguage] singleton — see
 * [SparqlFileType] for the rationale.
 */
class ShaclFileType private constructor() : LanguageFileType(ShaclLanguage) {
    override fun getName() = "SHACL"

    override fun getDescription() = "SHACL / Turtle"

    override fun getDefaultExtension() = "shacl"

    override fun getIcon(): Icon? = null

    companion object {
        @JvmField
        val INSTANCE = ShaclFileType()
    }
}
