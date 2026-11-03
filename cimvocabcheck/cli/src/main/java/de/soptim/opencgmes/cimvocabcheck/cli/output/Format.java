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

package de.soptim.opencgmes.cimvocabcheck.cli.output;

import java.util.Locale;

/** Output format for the {@code cimvocabcheck} command. */
public enum Format {
  /** Human-readable, compiler-style diagnostics (default). */
  TEXT,
  /** Machine-readable JSON — one object per file, wrapped in a top-level array. */
  JSON,
  /**
   * A <a href="https://docs.gitlab.com/ci/testing/code_quality/">GitLab / CodeClimate Code
   * Quality</a> report — a bare JSON array of one issue per finding, suitable for an {@code
   * artifacts:reports:codequality} CI artifact.
   */
  CODEQUALITY,
  /**
   * A <a href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html">SARIF 2.1.0</a>
   * log — one run holding one result per finding, consumed by GitHub code scanning and most IDE
   * result viewers.
   */
  SARIF;

  /**
   * Parses {@code value} ("text", "json", "codequality"/"gitlab" or "sarif", case-insensitive) into
   * a {@link Format}.
   */
  public static Format parse(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "text" -> TEXT;
      case "json" -> JSON;
      case "codequality", "gitlab" -> CODEQUALITY;
      case "sarif" -> SARIF;
      default ->
          throw new IllegalArgumentException(
              "Unknown format '" + value + "'. Use 'text', 'json', 'codequality' or 'sarif'.");
    };
  }
}
