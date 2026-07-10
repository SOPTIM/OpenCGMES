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
 * The endpoint a cell is executed against, resolved client-side from the cell's {@code #
 * [endpoint=...]} directive (see {@code EndpointDirective} in the parent package).
 *
 * @param type {@code "http"} for a remote SPARQL endpoint; other target types (local file, SHACL)
 *     are added in later milestones.
 * @param url the SPARQL query endpoint URL.
 * @param updateUrl the SPARQL Update endpoint URL. {@code null}/blank means this target has no
 *     configured update endpoint, so a cell that parses as a SPARQL Update is rejected with {@link
 *     ErrorCode#UPDATE_NOT_ALLOWED} rather than silently reusing {@link #url()}.
 */
record ExecuteTarget(String type, String url, String updateUrl) {

  /** The only target {@link #type()} understood in this milestone. */
  static final String TYPE_HTTP = "http";
}
