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
 * Covers the connection-name coherence in {@link SchemaManager#resolveSchema}: a {@code #
 * [endpoint=<name>]} directive naming a {@code "cimnotebook"} connection must resolve the schema
 * from that connection's URL (the remote-endpoint path), so validation and notebook execution agree
 * on what the directive means. An unknown name falls through to the old behavior (and, with no
 * extension, ends in the quiet workspace-schema fallback).
 */
public class SchemaManagerConnectionNameTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void connectionNameDirectiveResolvesTheSchemaFromTheConnectionUrl() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    // Deliberately unreachable URL: what matters is that the remote-schema path is entered for
    // the connection's URL, observable through the synchronous "loading schema from endpoint"
    // notification. The async load then fails quietly (negative-cached), which is fine here.
    String url = "http://localhost:9/none/query";
    Files.writeString(
        docDir.resolve("opencgmes.jsonc"),
        """
        { "cimnotebook": { "connections": [ { "name": "local-fuseki", "url": "%s" } ] } }
        """
            .formatted(url));

    List<MessageParams> messages = new CopyOnWriteArrayList<>();
    SchemaManager manager = new SchemaManager();
    manager.setClient(recordingClient(messages));
    try {
      var resolved = manager.resolveSchema("local-fuseki", docDir);

      assertTrue("remote loads are async — nothing is resolved yet", resolved.isEmpty());
      assertTrue(
          "the remote-schema path must be entered for the connection's URL; messages: " + messages,
          messages.stream().anyMatch(m -> m.getMessage().contains(url)));
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void unknownNameFallsBackQuietlyToTheWorkspaceSchema() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    Files.writeString(docDir.resolve("opencgmes.jsonc"), "{ \"cimnotebook\": {} }");

    List<MessageParams> messages = new CopyOnWriteArrayList<>();
    SchemaManager manager = new SchemaManager();
    manager.setClient(recordingClient(messages));
    try {
      assertTrue(manager.resolveSchema("no-such-connection", docDir).isEmpty());
      assertTrue("no user-facing noise for an unknown name: " + messages, messages.isEmpty());
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
