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
 * The endpoint or local files a cell is executed against, resolved client-side from the cell's
 * {@code # [endpoint=...]} directives (see {@code EndpointDirective} in the parent package).
 *
 * @param type {@code "http"} for a remote SPARQL endpoint, {@code "files"} for local RDF/CIMXML
 *     files queried in-process.
 * @param url the SPARQL query endpoint URL ({@code "http"} targets only).
 * @param updateUrl the SPARQL Update endpoint URL ({@code "http"} targets only). {@code null}/blank
 *     means this target has no configured update endpoint, so a cell that parses as a SPARQL Update
 *     is rejected with {@link ErrorCode#UPDATE_NOT_ALLOWED} rather than silently reusing {@link
 *     #url()}.
 * @param shaclUrl the SHACL validation service URL ({@code "http"} targets only), e.g. Fuseki's
 *     {@code …/shacl} operation. {@code null}/blank means SHACL cells cannot run against this
 *     target; like {@link #updateUrl()}, the server never derives it — the client does.
 * @param files the local files to query, exactly as written in the directives ({@code "files"}
 *     targets only). Relative paths are resolved server-side against the directory of {@link
 *     ExecuteRequest#notebookUri()}; multiple files are queried as one union store. Local files are
 *     read-only — updates are rejected with {@link ErrorCode#UPDATE_NOT_ALLOWED}.
 * @param auth optional credentials for {@code "http"} targets — see {@link ExecAuth} for the
 *     handling guarantees; {@code null} for anonymous access.
 */
record ExecuteTarget(
    String type, String url, String updateUrl, String shaclUrl, List<String> files, ExecAuth auth) {

  /** Target {@link #type()}: a remote SPARQL endpoint. */
  static final String TYPE_HTTP = "http";

  /** Target {@link #type()}: local RDF/CIMXML files queried in-process. */
  static final String TYPE_FILES = "files";
}
