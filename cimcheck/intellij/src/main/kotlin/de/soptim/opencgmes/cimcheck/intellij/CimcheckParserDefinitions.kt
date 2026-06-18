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

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

// Shared helpers — one flat parser that consumes all tokens without building an AST.
// This is intentionally minimal: the real semantic work is done by the LSP server.
// The only reason these definitions exist is to give IntelliJ token boundaries so
// that Ctrl+hover underlines the single token under the cursor instead of the whole file.

private val WHITESPACE_TOKENS = TokenSet.create(SparqlTokenTypes.WHITESPACE)
private val COMMENT_TOKENS = TokenSet.create(SparqlTokenTypes.COMMENT)
private val STRING_TOKENS = TokenSet.create(SparqlTokenTypes.STRING, SparqlTokenTypes.IRI)

private fun flatParser() =
    PsiParser { root, builder ->
        val m = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        m.done(root)
        builder.treeBuilt
    }

// ── SPARQL ────────────────────────────────────────────────────────────────────

class SparqlParserDefinition : ParserDefinition {
    companion object {
        @JvmField val FILE = IFileElementType(SparqlLanguage)
    }

    override fun createLexer(project: Project) = CimcheckLexer()

    override fun createParser(project: Project) = flatParser()

    override fun getFileNodeType() = FILE

    override fun getWhitespaceTokens() = WHITESPACE_TOKENS

    override fun getCommentTokens() = COMMENT_TOKENS

    override fun getStringLiteralElements() = STRING_TOKENS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider) =
        object : PsiFileBase(viewProvider, SparqlLanguage) {
            override fun getFileType() = SparqlFileType.INSTANCE
        }
}

// ── SHACL / Turtle ────────────────────────────────────────────────────────────

class ShaclParserDefinition : ParserDefinition {
    companion object {
        @JvmField val FILE = IFileElementType(ShaclLanguage)
    }

    override fun createLexer(project: Project) = CimcheckLexer()

    override fun createParser(project: Project) = flatParser()

    override fun getFileNodeType() = FILE

    override fun getWhitespaceTokens() = WHITESPACE_TOKENS

    override fun getCommentTokens() = COMMENT_TOKENS

    override fun getStringLiteralElements() = STRING_TOKENS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider) =
        object : PsiFileBase(viewProvider, ShaclLanguage) {
            override fun getFileType() = ShaclFileType.INSTANCE
        }
}
