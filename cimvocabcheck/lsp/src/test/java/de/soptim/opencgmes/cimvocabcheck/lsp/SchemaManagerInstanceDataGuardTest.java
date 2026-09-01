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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Covers the dual-semantics guard in {@link SchemaManager#resolveSchema}: a {@code #
 * [endpoint=...]} directive is both validation schema source and notebook execution data target,
 * and when it names <em>instance data</em> — a CIMXML {@code .xml} model, an {@code .nt} dump —
 * validation must fall back to the workspace schema instead of loading the data file as a schema.
 * Without the guard, a CIMXML model (valid RDF/XML!) silently loads as an empty-ish schema and
 * every {@code cim:} term in the cell turns into a false diagnostic.
 */
public class SchemaManagerInstanceDataGuardTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  /** A perfectly valid CIMXML instance model — which is exactly why it must not become a schema. */
  private static final String CIMXML_MODEL =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
       <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
         <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
       </md:FullModel>
       <cim:MyEquipment rdf:ID="_f67fc354-9e39-4191-a456-67537399bc48">
         <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
       </cim:MyEquipment>
      </rdf:RDF>
      """;

  @Test
  public void instanceDataDirectivesFallBackToTheWorkspaceSchema() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    Files.writeString(docDir.resolve("model.xml"), CIMXML_MODEL);
    Files.writeString(
        docDir.resolve("data.nt"),
        "<http://example.org/s> <http://example.org/p> <http://example.org/o> .\n");

    List<MessageParams> messages = new CopyOnWriteArrayList<>();
    SchemaManager manager = new SchemaManager();
    manager.setClient(recordingClient(messages));
    try {
      // No opencgmes.jsonc anywhere near docDir, so the workspace-schema fallback is empty —
      // syntax-only validation. Before the guard, the .xml resolved to a garbage schema (or, when
      // schema construction rejected it, a user-facing load-failure notification) instead.
      assertTrue(
          "a CIMXML model directive must not load as an endpoint schema",
          manager.resolveSchema("./model.xml", docDir).isEmpty());
      assertTrue(
          "an N-Triples data directive must not load as an endpoint schema",
          manager.resolveSchema("./data.nt", docDir).isEmpty());
      assertEquals(
          "the quiet fallback must not raise schema-load notifications: " + messages,
          List.of(),
          messages);
    } finally {
      manager.shutdown();
    }
  }

  private static LanguageClient recordingClient(List<MessageParams> messages) {
    return new LanguageClient() {
      @Override
      public void telemetryEvent(Object object) {}

      @Override
      public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}

      @Override
      public void showMessage(MessageParams messageParams) {
        messages.add(messageParams);
      }

      @Override
      public CompletableFuture<MessageActionItem> showMessageRequest(
          ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void logMessage(MessageParams message) {}
    };
  }
}
