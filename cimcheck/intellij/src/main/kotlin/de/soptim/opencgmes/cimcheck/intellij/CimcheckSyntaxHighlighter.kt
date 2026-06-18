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

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

class CimcheckSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = CimcheckLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(TOKEN_COLORS[tokenType])

    companion object {
        private val TOKEN_COLORS: Map<IElementType, TextAttributesKey> =
            mapOf(
                SparqlTokenTypes.KEYWORD to DefaultLanguageHighlighterColors.KEYWORD,
                SparqlTokenTypes.COMMENT to DefaultLanguageHighlighterColors.LINE_COMMENT,
                SparqlTokenTypes.STRING to DefaultLanguageHighlighterColors.STRING,
                SparqlTokenTypes.IRI to DefaultLanguageHighlighterColors.STRING,
                SparqlTokenTypes.VARIABLE to DefaultLanguageHighlighterColors.LOCAL_VARIABLE,
                SparqlTokenTypes.NUMBER to DefaultLanguageHighlighterColors.NUMBER,
                SparqlTokenTypes.PREFIXED to DefaultLanguageHighlighterColors.IDENTIFIER,
                SparqlTokenTypes.OPERATOR to DefaultLanguageHighlighterColors.OPERATION_SIGN,
                SparqlTokenTypes.PUNCTUATION to DefaultLanguageHighlighterColors.BRACES,
            )
    }
}
