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

package de.soptim.opencgmes.cimvocabcheck.lsp.notebook;

import java.util.List;

/**
 * The {@code "cimnotebook"} section of {@code opencgmes.jsonc} — a sibling of the {@code
 * "cimvocabcheck"} section the validator reads, parsed by {@link NotebookConfigLoader}.
 *
 * @param connections named endpoint connections; never {@code null} after loading.
 * @param queryTimeoutSeconds workspace default for the execution timeout; {@code null} keeps the
 *     built-in default ({@link ExecSupport#DEFAULT_TIMEOUT_MS}).
 * @param maxRows workspace default for the result row/triple cap; {@code null} keeps the built-in
 *     default ({@link ExecSupport#DEFAULT_MAX_ROWS}).
 */
public record NotebookConfig(
    List<NotebookConnection> connections, Integer queryTimeoutSeconds, Integer maxRows) {

  static final NotebookConfig EMPTY = new NotebookConfig(List.of(), null, null);

  /** Normalizes an absent {@code connections} array to an immutable empty list. */
  public NotebookConfig {
    connections = connections == null ? List.of() : List.copyOf(connections);
  }

  /** The connection marked {@code "default": true}, or {@code null} if none is. */
  public NotebookConnection defaultConnection() {
    return connections.stream().filter(NotebookConnection::isDefault).findFirst().orElse(null);
  }

  /** The connection with the given name (exact match), or {@code null}. */
  public NotebookConnection byName(String name) {
    return connections.stream()
        .filter(c -> name != null && name.equals(c.name()))
        .findFirst()
        .orElse(null);
  }
}
