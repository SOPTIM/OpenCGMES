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
 * Per-execution overrides for the server-side defaults (see {@link
 * HttpQueryExecutor#DEFAULT_TIMEOUT_MS} / {@link HttpQueryExecutor#DEFAULT_MAX_ROWS}).
 *
 * @param timeoutMs request timeout in milliseconds, or {@code null} to use the default.
 * @param maxRows maximum number of solutions/triples to include in the result before truncating, or
 *     {@code null} to use the default.
 */
record ExecuteOptions(Integer timeoutMs, Integer maxRows) {}
