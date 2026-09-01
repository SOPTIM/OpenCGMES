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
 * Aggregated outcome of a SHACL validation run, so the client can render a verdict banner without
 * parsing the report graph in {@link ExecuteResponse#turtle()}.
 *
 * @param conforms the report's {@code sh:conforms} value.
 * @param violations number of report entries with severity {@code sh:Violation}.
 * @param warnings number of report entries with severity {@code sh:Warning}.
 * @param infos number of report entries with severity {@code sh:Info}.
 */
record ShaclSummary(boolean conforms, int violations, int warnings, int infos) {}
