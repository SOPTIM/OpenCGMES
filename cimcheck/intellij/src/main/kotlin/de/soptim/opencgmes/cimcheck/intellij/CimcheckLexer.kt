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

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Hand-written lexer shared by the SPARQL and SHACL/Turtle syntax highlighters.
 *
 * Recognises: keywords, comments, IRI references, prefixed names, variables,
 * string literals (single/triple-quoted), numbers, and basic operators/punctuation.
 * Unknown characters are emitted as OTHER (no colouring).
 */
class CimcheckLexer : LexerBase() {
    private var myBuffer: CharSequence = ""
    private var myTokenStart = 0
    private var myTokenEnd = 0
    private var myBufferEnd = 0
    private var myTokenType: IElementType? = null

    override fun start(
        buffer: CharSequence,
        startOffset: Int,
        endOffset: Int,
        initialState: Int,
    ) {
        myBuffer = buffer
        myTokenStart = startOffset
        myTokenEnd = startOffset
        myBufferEnd = endOffset
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = myTokenType

    override fun getTokenStart(): Int = myTokenStart

    override fun getTokenEnd(): Int = myTokenEnd

    override fun getBufferSequence(): CharSequence = myBuffer

    override fun getBufferEnd(): Int = myBufferEnd

    override fun advance() {
        myTokenStart = myTokenEnd
        myTokenType = if (myTokenStart >= myBufferEnd) null else nextToken()
    }

    // Returns the char at position i, or NUL if out of bounds.
    private fun at(i: Int): Char = if (i < myBufferEnd) myBuffer[i] else '\u0000'

    private fun nextToken(): IElementType {
        val c = at(myTokenEnd)

        // Whitespace
        if (c.isWhitespace()) {
            while (myTokenEnd < myBufferEnd && myBuffer[myTokenEnd].isWhitespace()) myTokenEnd++
            return SparqlTokenTypes.WHITESPACE
        }

        // Comment  # … end of line
        if (c == '#') {
            while (myTokenEnd < myBufferEnd && myBuffer[myTokenEnd] != '\n') myTokenEnd++
            return SparqlTokenTypes.COMMENT
        }

        // Triple-quoted strings  """…"""  or  '''…'''
        val q1 = at(myTokenEnd + 1)
        val q2 = at(myTokenEnd + 2)
        if ((c == '"' || c == '\'') && q1 == c && q2 == c) {
            myTokenEnd += 3
            while (myTokenEnd < myBufferEnd) {
                if (at(myTokenEnd) == c && at(myTokenEnd + 1) == c && at(myTokenEnd + 2) == c) {
                    myTokenEnd += 3
                    break
                }
                if (myBuffer[myTokenEnd] == '\\') myTokenEnd++ // skip escape
                myTokenEnd++
            }
            return SparqlTokenTypes.STRING
        }

        // Single-quoted strings  "…"  or  '…'
        if (c == '"' || c == '\'') {
            myTokenEnd++
            while (myTokenEnd < myBufferEnd &&
                myBuffer[myTokenEnd] != c &&
                myBuffer[myTokenEnd] != '\n'
            ) {
                if (myBuffer[myTokenEnd] == '\\') myTokenEnd++
                myTokenEnd++
            }
            if (myTokenEnd < myBufferEnd) myTokenEnd++ // closing quote
            return SparqlTokenTypes.STRING
        }

        // IRI reference  <…>
        if (c == '<') {
            myTokenEnd++
            while (myTokenEnd < myBufferEnd &&
                myBuffer[myTokenEnd] != '>' &&
                myBuffer[myTokenEnd] != '\n'
            ) {
                myTokenEnd++
            }
            if (myTokenEnd < myBufferEnd) myTokenEnd++
            return SparqlTokenTypes.IRI
        }

        // Variables  ?name  $name
        if ((c == '?' || c == '$') && (at(myTokenEnd + 1).isLetterOrDigit() || at(myTokenEnd + 1) == '_')) {
            myTokenEnd++
            while (myTokenEnd < myBufferEnd &&
                (myBuffer[myTokenEnd].isLetterOrDigit() || myBuffer[myTokenEnd] == '_')
            ) {
                myTokenEnd++
            }
            return SparqlTokenTypes.VARIABLE
        }

        // Turtle directives  @prefix  @base  @en (lang tags)
        if (c == '@') {
            myTokenEnd++
            while (myTokenEnd < myBufferEnd && (myBuffer[myTokenEnd].isLetter() || myBuffer[myTokenEnd] == '-')) myTokenEnd++
            return SparqlTokenTypes.KEYWORD
        }

        // Typed-literal caret  ^^
        if (c == '^' && at(myTokenEnd + 1) == '^') {
            myTokenEnd += 2
            return SparqlTokenTypes.OPERATOR
        }

        // Numbers — integer / decimal / double, optional leading sign
        val isSignedNumber = (c == '+' || c == '-') && at(myTokenEnd + 1).isDigit()
        val isDotNumber = c == '.' && at(myTokenEnd + 1).isDigit()
        if (c.isDigit() || isSignedNumber || isDotNumber) {
            if (c == '+' || c == '-') myTokenEnd++
            while (myTokenEnd < myBufferEnd && myBuffer[myTokenEnd].isDigit()) myTokenEnd++
            if (myTokenEnd < myBufferEnd && myBuffer[myTokenEnd] == '.') {
                myTokenEnd++
                while (myTokenEnd < myBufferEnd && myBuffer[myTokenEnd].isDigit()) myTokenEnd++
            }
            if (myTokenEnd < myBufferEnd && (myBuffer[myTokenEnd] == 'e' || myBuffer[myTokenEnd] == 'E')) {
                myTokenEnd++
                if (myTokenEnd < myBufferEnd && (myBuffer[myTokenEnd] == '+' || myBuffer[myTokenEnd] == '-')) myTokenEnd++
                while (myTokenEnd < myBufferEnd && myBuffer[myTokenEnd].isDigit()) myTokenEnd++
            }
            return SparqlTokenTypes.NUMBER
        }

        // Identifiers, keywords, and prefix:local names
        if (c.isLetter() || c == '_') {
            val wordStart = myTokenEnd
            while (myTokenEnd < myBufferEnd &&
                (
                    myBuffer[myTokenEnd].isLetterOrDigit() ||
                        myBuffer[myTokenEnd] == '_' ||
                        myBuffer[myTokenEnd] == '-'
                )
            ) {
                myTokenEnd++
            }
            val word = myBuffer.subSequence(wordStart, myTokenEnd).toString()

            // prefix:localPart  (or KEYWORD: treated below)
            if (myTokenEnd < myBufferEnd && myBuffer[myTokenEnd] == ':') {
                myTokenEnd++ // consume ':'
                while (myTokenEnd < myBufferEnd &&
                    (
                        myBuffer[myTokenEnd].isLetterOrDigit() ||
                            myBuffer[myTokenEnd] in "_-."
                    )
                ) {
                    myTokenEnd++
                }
                // e.g. PREFIX: in Turtle is a keyword, cim:ACLineSegment is a prefixed name
                return if (KEYWORDS.contains(word.uppercase())) {
                    SparqlTokenTypes.KEYWORD
                } else {
                    SparqlTokenTypes.PREFIXED
                }
            }

            return if (KEYWORDS.contains(word.uppercase())) {
                SparqlTokenTypes.KEYWORD
            } else {
                SparqlTokenTypes.OTHER
            }
        }

        // Punctuation  { } ( ) [ ] . ; ,
        if (c in "{}()[].;,") {
            myTokenEnd++
            return SparqlTokenTypes.PUNCTUATION
        }

        // Operators  = ! < > + - * / | &
        if (c in "=!<>+\\-*/|&") {
            myTokenEnd++
            return SparqlTokenTypes.OPERATOR
        }

        myTokenEnd++
        return SparqlTokenTypes.OTHER
    }

    companion object {
        private val KEYWORDS: Set<String> =
            setOf(
                // SPARQL query
                "SELECT",
                "CONSTRUCT",
                "DESCRIBE",
                "ASK",
                "WHERE",
                "FROM",
                "NAMED",
                "OPTIONAL",
                "UNION",
                "MINUS",
                "GRAPH",
                "SERVICE",
                "SILENT",
                "BIND",
                "AS",
                "VALUES",
                "FILTER",
                "NOT",
                "EXISTS",
                "IN",
                "DISTINCT",
                "REDUCED",
                "ORDER",
                "BY",
                "ASC",
                "DESC",
                "LIMIT",
                "OFFSET",
                "HAVING",
                "GROUP",
                // SPARQL update
                "INSERT",
                "DELETE",
                "LOAD",
                "CLEAR",
                "DROP",
                "CREATE",
                "ADD",
                "MOVE",
                "COPY",
                "WITH",
                "USING",
                "DATA",
                "INTO",
                // Common
                "BASE",
                "PREFIX",
                // Turtle / SHACL
                "A",
                // Literals
                "TRUE",
                "FALSE",
                // SPARQL built-in functions
                "STR",
                "LANG",
                "DATATYPE",
                "IRI",
                "URI",
                "BNODE",
                "ISIRI",
                "ISURI",
                "ISBLANK",
                "ISLITERAL",
                "ISNUMERIC",
                "BOUND",
                "COALESCE",
                "IF",
                "SAMEAS",
                "REGEX",
                "SUBSTR",
                "STRLEN",
                "REPLACE",
                "UCASE",
                "LCASE",
                "STRSTARTS",
                "STRENDS",
                "CONTAINS",
                "STRBEFORE",
                "STRAFTER",
                "CONCAT",
                "LANGMATCHES",
                "ENCODE_FOR_URI",
                "ABS",
                "ROUND",
                "CEIL",
                "FLOOR",
                "RAND",
                "NOW",
                "YEAR",
                "MONTH",
                "DAY",
                "HOURS",
                "MINUTES",
                "SECONDS",
                "TIMEZONE",
                "TZ",
                "MD5",
                "SHA1",
                "SHA256",
                "SHA384",
                "SHA512",
                "UUID",
                "STRUUID",
                "STRLANG",
                "STRDT",
                // Aggregates
                "COUNT",
                "SUM",
                "MIN",
                "MAX",
                "AVG",
                "SAMPLE",
                "GROUP_CONCAT",
            )
    }
}
