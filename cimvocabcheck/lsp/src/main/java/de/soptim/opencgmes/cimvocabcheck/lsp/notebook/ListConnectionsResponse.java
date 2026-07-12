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
 * Result of the {@code cimvocabcheck.notebook.listConnections} workspace command: the notebook's
 * applicable {@code "cimnotebook"} config, resolved with nearest-config discovery. Contains no
 * secrets by construction (see {@link NotebookConnection}).
 *
 * @param configPath the config file the connections came from, or {@code null} when no {@code
 *     opencgmes.jsonc} applies to the notebook.
 * @param connections the declared connections; empty when there are none.
 * @param queryTimeoutSeconds workspace default execution timeout, or {@code null}.
 * @param maxRows workspace default result cap, or {@code null}.
 */
record ListConnectionsResponse(
    String configPath,
    List<NotebookConnection> connections,
    Integer queryTimeoutSeconds,
    Integer maxRows) {}
