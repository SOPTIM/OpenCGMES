/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */
package de.soptim.opencgmes.cimnotebook.intellij

import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CimnotebookLexerTest {
    private fun tokens(text: String): List<Pair<IElementType, String>> {
        val lexer = CimnotebookLexer()
        lexer.start(text, 0, text.length, 0)
        val result = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            result.add(type to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return result
    }

    private fun significantTokens(text: String) = tokens(text).filter { it.first != SparqlTokenTypes.WHITESPACE }

    @Test
    fun `less-than comparison is an operator, not an IRI`() {
        val actual = significantTokens("FILTER(?a < ?b)")
        val expected =
            listOf(
                SparqlTokenTypes.KEYWORD to "FILTER",
                SparqlTokenTypes.PUNCTUATION to "(",
                SparqlTokenTypes.VARIABLE to "?a",
                SparqlTokenTypes.OPERATOR to "<",
                SparqlTokenTypes.VARIABLE to "?b",
                SparqlTokenTypes.PUNCTUATION to ")",
            )
        assertEquals(expected, actual)
    }

    @Test
    fun `comparison operators inside a function call`() {
        val actual = significantTokens("BIND(IF(?a < ?b, ?a, ?b) AS ?min)")
        val expected =
            listOf(
                SparqlTokenTypes.KEYWORD to "BIND",
                SparqlTokenTypes.PUNCTUATION to "(",
                SparqlTokenTypes.KEYWORD to "IF",
                SparqlTokenTypes.PUNCTUATION to "(",
                SparqlTokenTypes.VARIABLE to "?a",
                SparqlTokenTypes.OPERATOR to "<",
                SparqlTokenTypes.VARIABLE to "?b",
                SparqlTokenTypes.PUNCTUATION to ",",
                SparqlTokenTypes.VARIABLE to "?a",
                SparqlTokenTypes.PUNCTUATION to ",",
                SparqlTokenTypes.VARIABLE to "?b",
                SparqlTokenTypes.PUNCTUATION to ")",
                SparqlTokenTypes.KEYWORD to "AS",
                SparqlTokenTypes.VARIABLE to "?min",
                SparqlTokenTypes.PUNCTUATION to ")",
            )
        assertEquals(expected, actual)
    }

    @Test
    fun `less-than does not swallow a later greater-than on the same line`() {
        val actual = significantTokens("FILTER(?a < ?b && ?c > ?d)")
        val expected =
            listOf(
                SparqlTokenTypes.KEYWORD to "FILTER",
                SparqlTokenTypes.PUNCTUATION to "(",
                SparqlTokenTypes.VARIABLE to "?a",
                SparqlTokenTypes.OPERATOR to "<",
                SparqlTokenTypes.VARIABLE to "?b",
                SparqlTokenTypes.OPERATOR to "&",
                SparqlTokenTypes.OPERATOR to "&",
                SparqlTokenTypes.VARIABLE to "?c",
                SparqlTokenTypes.OPERATOR to ">",
                SparqlTokenTypes.VARIABLE to "?d",
                SparqlTokenTypes.PUNCTUATION to ")",
            )
        assertEquals(expected, actual)
    }

    @Test
    fun `iri reference is lexed as a single IRI token`() {
        val actual = significantTokens("FROM <http://example.org/graph>")
        val expected =
            listOf(
                SparqlTokenTypes.KEYWORD to "FROM",
                SparqlTokenTypes.IRI to "<http://example.org/graph>",
            )
        assertEquals(expected, actual)
    }

    @Test
    fun `less-than-or-equal is lexed as operators`() {
        val actual = significantTokens("FILTER(?a <= ?b)")
        val expected =
            listOf(
                SparqlTokenTypes.KEYWORD to "FILTER",
                SparqlTokenTypes.PUNCTUATION to "(",
                SparqlTokenTypes.VARIABLE to "?a",
                SparqlTokenTypes.OPERATOR to "<",
                SparqlTokenTypes.OPERATOR to "=",
                SparqlTokenTypes.VARIABLE to "?b",
                SparqlTokenTypes.PUNCTUATION to ")",
            )
        assertEquals(expected, actual)
    }

    @Test
    fun `unclosed angle bracket at end of line is an operator`() {
        val actual = significantTokens("?a <\n?b")
        val expected =
            listOf(
                SparqlTokenTypes.VARIABLE to "?a",
                SparqlTokenTypes.OPERATOR to "<",
                SparqlTokenTypes.VARIABLE to "?b",
            )
        assertEquals(expected, actual)
    }

    @Test
    fun `iri candidate containing a quote is not an IRI`() {
        val actual = significantTokens("?a <\"x\"> ?b")
        assertEquals(SparqlTokenTypes.OPERATOR to "<", actual[1])
    }
}
