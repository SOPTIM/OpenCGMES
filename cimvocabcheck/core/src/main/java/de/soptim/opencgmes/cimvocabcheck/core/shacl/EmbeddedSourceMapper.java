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

package de.soptim.opencgmes.cimvocabcheck.core.shacl;

import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a validation-annotation position from an embedded SPARQL fragment's <em>rendered</em> query
 * (SHACL-declared prefixes prepended to the raw query) back to a position in the enclosing Turtle
 * source.
 *
 * <p>Embedded SPARQL queries are validated as standalone query text, so their annotations carry
 * line/column coordinates relative to the rendered query, not the Turtle file. Both the CLI and the
 * LSP need to translate those coordinates into Turtle-source positions to point the user at the
 * right place; this is the single shared implementation.
 *
 * <p>Two strategies (in order):
 *
 * <ol>
 *   <li><b>Exact substring match</b> — {@link EmbeddedSparql#rawQuery()} is the literal content
 *       between the {@code """..."""} delimiters, hence an exact substring of the Turtle source. It
 *       is located with {@link String#indexOf}, then navigated forward the required number of
 *       lines. Precise and anchor-free.
 *   <li><b>Line-anchor fallback</b> — when {@code indexOf} fails (e.g. escapes altered the
 *       literal), the first non-blank, non-comment raw-query line is used as a search anchor.
 * </ol>
 */
public final class EmbeddedSourceMapper {

  private static final Pattern SPARQL_POSITION = Pattern.compile("line (\\d+), column (\\d+)");

  private EmbeddedSourceMapper() {}

  /**
   * Resolves the Turtle-source position (0-based line and column) for {@code annotation}, which was
   * produced by validating {@code embedded}'s {@link EmbeddedSparql#renderedQuery() rendered
   * query}.
   *
   * <p>For a syntax error (no {@link SparqlValidationAnnotation#term() term}), Jena's reported
   * line/column can be unreliable, so the position is taken from the {@code "line N, column C"}
   * text in the message when present.
   *
   * @return a two-element {@code [line, column]} array, both 0-based
   */
  public static int[] toTurtlePosition(
      SparqlValidationAnnotation annotation, EmbeddedSparql embedded, String turtleText) {
    int renderedLine = annotation.line() != null ? annotation.line() : 0;
    int renderedCol = annotation.column() != null ? annotation.column() : 0;
    if (annotation.term() == null && annotation.message() != null) {
      Matcher m = SPARQL_POSITION.matcher(annotation.message());
      if (m.find()) {
        renderedLine = Integer.parseInt(m.group(1));
        renderedCol = Integer.parseInt(m.group(2));
      }
    }
    return toTurtlePosition(renderedLine, renderedCol, embedded, turtleText);
  }

  /**
   * Maps a rendered-query position (1-based line + column) back to a {@code [line, col]} pair in
   * the Turtle source (both 0-based).
   *
   * <p>The rendered query = {@code embedded.prefixes().size()} PREFIX lines + the raw query; only
   * lines are shifted, columns carry through unchanged.
   */
  public static int[] toTurtlePosition(
      int renderedLine1based, int renderedCol1based, EmbeddedSparql embedded, String turtleText) {
    if (renderedLine1based <= 0) {
      return new int[] {0, 0};
    }

    int lineInRendered = renderedLine1based - 1; // 0-based in rendered query
    int lineInRaw = lineInRendered - embedded.prefixes().size(); // 0-based in rawQuery
    int col = Math.max(0, renderedCol1based - 1);
    if (lineInRaw < 0) {
      lineInRaw = 0;
      col = 0;
    }

    String rawQuery = embedded.rawQuery();

    // Strategy 1 — exact match: rawQuery IS a literal substring of the Turtle source.
    int rawStart = (rawQuery != null && !rawQuery.isEmpty()) ? turtleText.indexOf(rawQuery) : -1;
    if (rawStart >= 0) {
      int offset = rawStart;
      for (int i = 0; i < lineInRaw; i++) {
        int nl = turtleText.indexOf('\n', offset);
        if (nl < 0) {
          offset = turtleText.length();
          break;
        }
        offset = nl + 1;
      }
      int turtleLine = 0;
      for (int i = 0; i < offset && i < turtleText.length(); i++) {
        if (turtleText.charAt(i) == '\n') {
          turtleLine++;
        }
      }
      return new int[] {turtleLine, col};
    }

    // Strategy 2 — find first non-blank, non-comment rawQuery line as search anchor.
    int rawStartLine = findRawQueryStartLine(rawQuery, turtleText);
    return new int[] {rawStartLine + lineInRaw, col};
  }

  /**
   * Fallback for {@link #toTurtlePosition}: uses the first non-blank, non-comment line of {@code
   * rawQuery} as an anchor and searches for it in the Turtle source.
   */
  private static int findRawQueryStartLine(String rawQuery, String turtleText) {
    if (rawQuery == null || turtleText == null) {
      return 0;
    }
    String[] rawLines = rawQuery.split("\n", -1);
    int anchorIdx = -1;
    String anchor = null;
    for (int i = 0; i < rawLines.length; i++) {
      String t = rawLines[i].trim();
      if (!t.isEmpty() && !t.startsWith("#")) {
        anchorIdx = i;
        anchor = t;
        break;
      }
    }
    if (anchor == null) {
      return 0;
    }
    String[] turtleLines = turtleText.split("\n", -1);
    for (int i = 0; i < turtleLines.length; i++) {
      if (turtleLines[i].contains(anchor)) {
        return Math.max(0, i - anchorIdx);
      }
    }
    return 0;
  }
}
