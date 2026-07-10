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
 * Execution metadata reported alongside a successful {@link ExecuteResponse}.
 *
 * @param durationMs wall-clock execution time in milliseconds.
 * @param rowCount number of result rows (SELECT) or triples (CONSTRUCT/DESCRIBE) returned; {@code
 *     null} for ASK and UPDATE.
 * @param truncated whether the result was cut off at the {@code maxRows} limit.
 * @param resolvedTarget the endpoint URL actually queried/updated.
 */
record ExecStats(long durationMs, Integer rowCount, boolean truncated, String resolvedTarget) {}
