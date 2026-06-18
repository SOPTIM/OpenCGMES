/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimvocabcheck.cli.output;

import java.util.Locale;

/** Output format for the {@code cimvocabcheck} command. */
public enum Format {
  /** Human-readable, compiler-style diagnostics (default). */
  TEXT,
  /** Machine-readable JSON — one object per file, wrapped in a top-level array. */
  JSON;

  /** Parses {@code value} ("text" or "json", case-insensitive) into a {@link Format}. */
  public static Format parse(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "text" -> TEXT;
      case "json" -> JSON;
      default ->
          throw new IllegalArgumentException(
              "Unknown format '" + value + "'. Use 'text' or 'json'.");
    };
  }
}
