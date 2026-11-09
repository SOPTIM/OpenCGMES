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

package de.soptim.opencgmes.cimxmlcli.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects parser warnings per source instead of logging each of them, so that hundreds of
 * identical warnings can be summarized in one line. Call {@link #reset()} before each source; the
 * models are parsed sequentially.
 */
public final class CountingErrorHandler implements ErrorHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CountingErrorHandler.class);
  private static final Pattern QUOTED = Pattern.compile("'[^']*'");
  private static final int MAX_KIND_LENGTH = 80;

  private final List<String> warnings = new ArrayList<>();

  /** Forgets the warnings of the previous source. */
  public void reset() {
    warnings.clear();
  }

  /** Returns the warnings collected since the last {@link #reset()}. */
  public WarningSummary summary() {
    final var counts = new LinkedHashMap<String, Integer>();
    warnings.forEach(w -> counts.merge(kindOf(w), 1, Integer::sum));
    // Most frequent kind first
    final var sorted = new LinkedHashMap<String, Integer>();
    counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .forEach(e -> sorted.put(e.getKey(), e.getValue()));
    return new WarningSummary(sorted, warnings);
  }

  @Override
  public void warning(String message, long line, long col) {
    final var formatted = format(message, line, col);
    LOG.debug("{}", formatted);
    warnings.add(formatted);
  }

  /** The cimxml parser throws right after reporting an error, so only the message is kept. */
  @Override
  public void error(String message, long line, long col) {
    LOG.debug("{}", format(message, line, col));
  }

  @Override
  public void fatal(String message, long line, long col) {
    throw new RiotException(format(message, line, col));
  }

  /**
   * Reduces a warning message to its kind: quoted values are replaced and everything after the
   * first sentence is dropped, e.g. {@code CIM UUID without dashes: '...' - converted to dashed
   * form.} becomes {@code CIM UUID without dashes}.
   */
  static String kindOf(String message) {
    // Strip the leading position
    var kind = message.replaceFirst("^\\[line: \\d+, col: \\d+\\] ", "");
    kind = QUOTED.matcher(kind).replaceAll("'...'");
    final var colon = kind.indexOf(": ");
    if (colon > 0) {
      kind = kind.substring(0, colon);
    }
    final var sentence = kind.indexOf(". ");
    if (sentence > 0) {
      kind = kind.substring(0, sentence);
    }
    if (kind.endsWith(".")) {
      kind = kind.substring(0, kind.length() - 1);
    }
    return kind.length() > MAX_KIND_LENGTH ? kind.substring(0, MAX_KIND_LENGTH) + "..." : kind;
  }

  private static String format(String message, long line, long col) {
    return line > 0 ? "[line: " + line + ", col: " + col + "] " + message : message;
  }
}
