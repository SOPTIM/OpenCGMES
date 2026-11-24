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

package de.soptim.opencgmes.cimxmlcli.validation;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * A problem found while reading or validating the input. Problems are collected and reported
 * together, so that a single run shows everything that needs to be fixed.
 *
 * @param location where the problem was found, e.g. a source path ({@code igm.zip!/EQ.xml}), a
 *     folder or a graph name
 * @param message what is wrong
 * @param hint how to fix it, or {@code null}
 */
public record Problem(String location, String message, String hint) implements Serializable {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a problem. */
  public Problem {
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(message, "message");
  }

  /** Creates a problem without a hint. */
  public static Problem of(String location, String message) {
    return new Problem(location, message, null);
  }

  /** Formats the problem for the console, with the hint on an indented second line. */
  public String format() {
    return hint == null
        ? location + ": " + message
        : location + ": " + message + System.lineSeparator() + "    hint: " + hint;
  }
}
