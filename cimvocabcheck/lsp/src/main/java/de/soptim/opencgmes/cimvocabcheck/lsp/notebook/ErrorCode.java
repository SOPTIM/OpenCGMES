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
 * Machine-readable failure reason reported in {@link ExecError#code()}.
 *
 * <p>Local-file targets (and their file-specific failure modes) are added once local-file execution
 * is implemented; every code needed for HTTP-endpoint execution is declared here.
 */
enum ErrorCode {
  /** The command argument could not be parsed as an {@link ExecuteRequest} at all. */
  INVALID_REQUEST,

  /** No endpoint is configured for the cell (missing/blank {@code target}). */
  NO_TARGET,

  /** The cell's text is neither a valid SPARQL query nor a valid SPARQL Update request. */
  PARSE_ERROR,

  /**
   * The cell parsed as a SPARQL Update, but its target has no update endpoint configured (see
   * {@link ExecuteTarget#updateUrl()}).
   */
  UPDATE_NOT_ALLOWED,

  /** The endpoint answered with an HTTP error, or the connection otherwise failed. */
  HTTP_ERROR,

  /** The endpoint rejected the request as unauthorized/forbidden (HTTP 401/403). */
  AUTH_FAILED,

  /** The request did not complete within the configured timeout. */
  TIMEOUT,

  /** The client cancelled the execution before it completed. */
  CANCELLED,

  /** An unexpected internal error occurred while handling the request. */
  INTERNAL
}
