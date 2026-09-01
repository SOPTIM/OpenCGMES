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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Covers the schema source of a notebook cell that carries <em>no</em> {@code # [endpoint=...]}
 * directive of its own.
 *
 * <p>Such a cell still has a target: the notebook default the client remembers (its <em>Set Cell
 * Endpoint → Notebook default</em> scope), or the {@code "cimnotebook"} connection marked {@code
 * "default": true}. Both are how the cell is <em>executed</em>, so both must also be how it is
 * <em>validated</em> — otherwise the cell runs fine but the editor shows syntax-only diagnostics,
 * no completion and no go-to-definition, because the server only ever saw the cell text.
 */
public class NotebookDefaultEndpointTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
  private static final long TIMEOUT_MS = 10_000;

  /** Minimal CIM 16 RDFS that passes profile detection, plus one real class declaration. */
  private static final String SCHEMA_RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.shortName">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">TST</cims:isFixed>
        </rdf:Description>
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.entsoeURI">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/TestProfile/1</cims:isFixed>
        </rdf:Description>
        <rdfs:Class rdf:about="http://iec.ch/TC57/2013/CIM-schema-cim16#TestClass">
          <rdfs:label>TestClass</rdfs:label>
        </rdfs:Class>
      </rdf:RDF>
      """;

  /** A cell whose only schema-dependent finding is the unknown class — no syntax errors. */
  private static final String CELL_WITH_UNKNOWN_CLASS =
      "PREFIX cim: <" + CIM16 + ">\nSELECT * WHERE { ?s a cim:NoSuchClass }";

  /** The same notebook, mid-completion: the cursor sits right after the "cim:" prefix. */
  private static final String CELL_MID_COMPLETION =
      "PREFIX cim: <" + CIM16 + ">\nSELECT * WHERE { ?s a cim: }";

  private final List<PublishDiagnosticsParams> published = new CopyOnWriteArrayList<>();
  private final List<MessageParams> messages = new CopyOnWriteArrayList<>();

  private SchemaManager schemaManager;
  private SparqlTextDocumentService service;
  private NotebookDefaults notebookDefaults;

  private void startServer() {
    schemaManager = new SchemaManager();
    notebookDefaults = new NotebookDefaults();
    service = new SparqlTextDocumentService(schemaManager, notebookDefaults);
    // The same wiring SparqlLanguageServer does: a changed default revalidates the open cells.
    notebookDefaults.addOnChangeCallback(service::revalidateAll);
    LanguageClient client = recordingClient();
    schemaManager.setClient(client);
    service.setClient(client);
  }

  @After
  public void tearDown() {
    if (service != null) {
      service.shutdown();
    }
    if (schemaManager != null) {
      schemaManager.shutdown();
    }
  }

  @Test
  public void notebookDefaultGivesDirectivelessCellsTheirSchema() throws Exception {
    Path dir = tmp.getRoot().toPath();
    Files.writeString(dir.resolve("schema.rdf"), SCHEMA_RDF);
    Path notebook = dir.resolve("analysis.cimnb.md");
    startServer();

    String queryCell = cellUri(notebook, "c1");
    String completionCell = cellUri(notebook, "c2");
    open(queryCell, CELL_WITH_UNKNOWN_CLASS);
    open(completionCell, CELL_MID_COMPLETION);

    // Before: the cell has no directive and there is no config, so nothing but syntax is checked.
    awaitTrue("a first (syntax-only) publish", () -> lastDiagnostics(queryCell) != null);
    assertEquals(
        "without a schema the cell is syntax-only — no semantic findings",
        List.of(),
        lastDiagnostics(queryCell));
    assertEquals("and no completion is offered", List.of(), completeAfterPrefix(completionCell));

    // The user picks "Notebook default" in *Set Cell Endpoint*; the client pushes it to us.
    notebookDefaults.set(notebook.toUri().toString(), "./schema.rdf");

    awaitTrue(
        "the unknown class must be reported once the notebook default supplies a schema",
        () -> diagnosticsMention(queryCell, "NoSuchClass"));
    List<CompletionItem> items = completeAfterPrefix(completionCell);
    assertTrue(
        "completion must now offer the schema's terms, got: " + labels(items),
        labels(items).contains("cim:TestClass"));
  }

  @Test
  public void defaultConnectionGivesDirectivelessCellsTheirSchema() throws Exception {
    Path dir = tmp.getRoot().toPath();
    // Deliberately unreachable: what matters is that the cell's schema is fetched from the default
    // connection's endpoint at all — observable through the "loading schema from endpoint" notice.
    String url = "http://localhost:9/none/query";
    Files.writeString(
        dir.resolve("opencgmes.jsonc"),
        """
        { "cimnotebook": { "connections": [
            { "name": "local-fuseki", "url": "%s", "default": true }
        ] } }
        """
            .formatted(url));
    startServer();

    open(cellUri(dir.resolve("analysis.cimnb.md"), "c1"), CELL_WITH_UNKNOWN_CLASS);

    awaitTrue(
        "a directive-less cell must validate against the default connection's endpoint",
        () -> messages.stream().anyMatch(m -> m.getMessage().contains(url)));
  }

  @Test
  public void plainFilesIgnoreTheNotebookFallbacks() throws Exception {
    Path dir = tmp.getRoot().toPath();
    String url = "http://localhost:9/none/query";
    Files.writeString(
        dir.resolve("opencgmes.jsonc"),
        """
        { "cimnotebook": { "connections": [
            { "name": "local-fuseki", "url": "%s", "default": true }
        ] } }
        """
            .formatted(url));
    startServer();

    // A stand-alone query file is not part of any notebook: it keeps its workspace schema (here:
    // none) rather than borrowing the notebook connection's endpoint.
    String fileUri = dir.resolve("query.rq").toUri().toString();
    open(fileUri, CELL_WITH_UNKNOWN_CLASS);

    awaitTrue("a publish for the file", () -> lastDiagnostics(fileUri) != null);
    assertFalse(
        "a plain .rq file must not load the notebook default connection's schema: " + messages,
        messages.stream().anyMatch(m -> m.getMessage().contains(url)));
  }

  // ---- helpers ---------------------------------------------------------------------------

  /** The URI VS Code gives a cell: the notebook's path plus a cell-id fragment. */
  private static String cellUri(Path notebook, String cellId) {
    return "vscode-notebook-cell:" + notebook + "#" + cellId;
  }

  private void open(String uri, String text) {
    service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "sparql", 1, text)));
  }

  /** Completions offered with the cursor right behind the {@code cim:} prefix of line 1. */
  private List<CompletionItem> completeAfterPrefix(String uri) throws Exception {
    String line = CELL_MID_COMPLETION.split("\n")[1];
    Position cursor = new Position(1, line.indexOf("cim:") + "cim:".length());
    return service
        .completion(new CompletionParams(new TextDocumentIdentifier(uri), cursor))
        .get()
        .getLeft();
  }

  private static List<String> labels(List<CompletionItem> items) {
    return items.stream().map(CompletionItem::getLabel).toList();
  }

  /** The diagnostics of the most recent publish for {@code uri}, or {@code null} if none yet. */
  private List<Diagnostic> lastDiagnostics(String uri) {
    return published.stream()
        .filter(p -> p.getUri().equals(uri))
        .reduce((first, second) -> second)
        .map(PublishDiagnosticsParams::getDiagnostics)
        .orElse(null);
  }

  private boolean diagnosticsMention(String uri, String term) {
    List<Diagnostic> diagnostics = lastDiagnostics(uri);
    return diagnostics != null
        && diagnostics.stream().anyMatch(d -> d.getMessage().getLeft().contains(term));
  }

  /** Waits for a debounced (and, for endpoints, asynchronous) result to appear. */
  private static void awaitTrue(String what, BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("Timed out waiting for " + what);
  }

  private LanguageClient recordingClient() {
    return new LanguageClient() {
      @Override
      public void telemetryEvent(Object object) {}

      @Override
      public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        published.add(diagnostics);
      }

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
