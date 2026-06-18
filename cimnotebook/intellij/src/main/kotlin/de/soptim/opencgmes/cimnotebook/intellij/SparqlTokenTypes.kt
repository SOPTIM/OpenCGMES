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

import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType

/** Shared token types for the SPARQL / Turtle (SHACL) syntax highlighter. */
object SparqlTokenTypes {
    @JvmField val KEYWORD = IElementType("CIM_KEYWORD", Language.ANY)

    @JvmField val COMMENT = IElementType("CIM_COMMENT", Language.ANY)

    @JvmField val STRING = IElementType("CIM_STRING", Language.ANY)

    @JvmField val IRI = IElementType("CIM_IRI", Language.ANY)

    @JvmField val VARIABLE = IElementType("CIM_VARIABLE", Language.ANY)

    @JvmField val NUMBER = IElementType("CIM_NUMBER", Language.ANY)

    @JvmField val PREFIXED = IElementType("CIM_PREFIXED", Language.ANY)

    @JvmField val OPERATOR = IElementType("CIM_OPERATOR", Language.ANY)

    @JvmField val PUNCTUATION = IElementType("CIM_PUNCTUATION", Language.ANY)

    @JvmField val WHITESPACE = IElementType("CIM_WHITESPACE", Language.ANY)

    @JvmField val OTHER = IElementType("CIM_OTHER", Language.ANY)
}
