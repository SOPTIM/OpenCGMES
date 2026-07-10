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
 * Argument of the {@code cimvocabcheck.notebook.execute} workspace command, sent by the VS Code
 * notebook controller for each cell execution.
 *
 * @param cellUri URI of the executing cell; used only for logging/diagnostics.
 * @param languageId the cell's language id (e.g. {@code sparql}).
 * @param text the cell's source text.
 * @param target the endpoint to execute against, resolved client-side; {@code null} when the cell
 *     (and its notebook) has no {@code # [endpoint=...]} directive.
 * @param options per-execution overrides; {@code null} to use all defaults.
 */
record ExecuteRequest(
    String cellUri, String languageId, String text, ExecuteTarget target, ExecuteOptions options) {}
