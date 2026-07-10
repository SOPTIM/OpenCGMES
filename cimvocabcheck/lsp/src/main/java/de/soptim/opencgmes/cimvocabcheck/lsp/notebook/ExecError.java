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

/**
 * Failure details reported when {@link ExecuteResponse#status()} is not {@code SUCCESS}.
 *
 * @param code machine-readable failure reason: the {@link ErrorCode} name. Carried as a string
 *     because lsp4j's message Gson serializes enum fields as ordinals, which no client should have
 *     to decode (pinned by {@code NotebookCommandHandlerTest}).
 * @param message human-readable message, suitable for display in the cell's error output.
 * @param detail additional detail (e.g. a parser error's underlying cause); {@code null} if none.
 * @param line 1-based source line of a parse error; {@code null} if not applicable/unknown.
 * @param column 1-based source column of a parse error; {@code null} if not applicable/unknown.
 */
record ExecError(String code, String message, String detail, Integer line, Integer column) {

  ExecError(ErrorCode code, String message, String detail, Integer line, Integer column) {
    this(code.name(), message, detail, line, column);
  }
}
