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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;

/**
 * One named connection from the {@code "cimnotebook"} section of {@code opencgmes.jsonc}, read by
 * {@link NotebookConfigLoader} (Jackson) and answered to the client by the {@code
 * cimvocabcheck.notebook.listConnections} command (lsp4j's Gson) — hence the double annotations on
 * {@code isDefault}, whose JSON name {@code default} is a Java keyword.
 *
 * <p>Deliberately carries <b>no credentials</b>: the config file only declares the {@code
 * authType}, and the client keeps secrets in VS Code SecretStorage — passwords never live in {@code
 * opencgmes.jsonc} and never leave the client except inside an execute request.
 *
 * @param name the connection's name, referenced by {@code # [endpoint=<name>]} directives.
 * @param url the SPARQL query endpoint URL.
 * @param updateUrl optional SPARQL Update endpoint; {@code null} lets the client derive it.
 * @param shaclUrl optional SHACL service endpoint; {@code null} lets the client derive it.
 * @param authType {@code "basic"} when the endpoint needs credentials, else {@code null}/"none".
 * @param isDefault whether cells without a directive use this connection ({@code "default"} in
 *     JSON).
 */
public record NotebookConnection(
    String name,
    String url,
    String updateUrl,
    String shaclUrl,
    String authType,
    @JsonProperty("default") @SerializedName("default") boolean isDefault) {}
