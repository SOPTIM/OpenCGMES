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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.apache.jena.graph.NodeFactory;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests reading a <em>live</em> RDFArchitect dataset: the editor connects the window it embeds, the
 * language server borrows that session, and an edit made over there is picked up.
 *
 * <p>The stub serves a dataset only to a request carrying the connected session, mirroring how the
 * real backend keeps one working copy per session.
 */
public class RdfArchitectLiveDatasetTest {

  private static final String SESSION = "4CD09ADDD8817D3369A57E60A9FDC506";
  private static final String DATASET = "cgmes-3.0";
  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  /** The dataset's only graph, with one class that the edit below adds a sibling to. */
  private String turtle = profile("Breaker");

  private static String profile(String... classes) {
    StringBuilder ttl =
        new StringBuilder(
            """
            @prefix cim:  <http://iec.ch/TC57/CIM100#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .

            <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0> a owl:Ontology ;
                owl:versionIRI <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0> ;
                dcat:keyword "EQ" .
            """);
    for (String name : classes) {
      ttl.append("cim:")
          .append(name)
          .append(" a rdfs:Class ; rdfs:label \"")
          .append(name)
          .append("\" .\n");
    }
    return ttl.toString();
  }

  private HttpServer server;
  private String changeId = "";

  @Before
  public void setUp() throws IOException {
    SchemaManager.liveCheckInterval = Duration.ofMillis(50);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/", this::dispatch);
    server.start();
  }

  @After
  public void tearDown() {
    server.stop(0);
    SchemaManager.liveCheckInterval = Duration.ofSeconds(3);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
    boolean connected =
        SESSION.equals(cookieValue(exchange.getRequestHeaders().getFirst("Cookie")));
    String body;
    if (path.equals("/api/datasets")) {
      // Without the connected session there is nothing to see — datasets live in a session.
      body = connected ? "[\"" + DATASET + "\"]" : "[]";
    } else if (path.endsWith("/graphs")) {
      body = "[{\"prefix\":\"http://graph#\",\"suffix\":\"EQ\"}]";
    } else if (path.endsWith("/content")) {
      body = turtle;
    } else if (path.endsWith("/changes")) {
      body = changeId.isEmpty() ? "[]" : "[{\"changeId\":\"" + changeId + "\"}]";
    } else {
      body = null;
    }
    byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String cookieValue(String cookie) {
    if (cookie == null) {
      return null;
    }
    for (String pair : cookie.split(";")) {
      String[] kv = pair.trim().split("=", 2);
      if (kv.length == 2 && kv[0].equals("RDFA_SESSION_ID")) {
        return kv[1];
      }
    }
    return null;
  }

  /** Resolves the schema, waiting for the asynchronous load to land. */
  private Optional<ResolvedSchema> resolve(SchemaManager manager, String ref)
      throws InterruptedException {
    String source = RdfArchitectDirective.SCHEME + ref;
    for (int i = 0; i < 100; i++) {
      Optional<ResolvedSchema> resolved = manager.resolveSchema(source, null);
      if (resolved.isPresent()) {
        return resolved;
      }
      Thread.sleep(50);
    }
    return Optional.empty();
  }

  private static boolean knows(ResolvedSchema schema, String className) {
    return !schema.api().schemaIndex().findClass(NodeFactory.createURI(CIM + className)).isEmpty();
  }

  @Test
  public void readsADatasetOfTheConnectedWindow() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      manager.connectRdfArchitect(baseUrl(), SESSION);

      // The document names the dataset only; the instance comes from the connection.
      Optional<ResolvedSchema> resolved = resolve(manager, DATASET);

      assertTrue(
          "a bare dataset name must resolve once a window is connected", resolved.isPresent());
      assertTrue(knows(resolved.get(), "Breaker"));
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void picksUpAnEditMadeInRdfArchitect() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      manager.connectRdfArchitect(baseUrl(), SESSION);
      ResolvedSchema before = resolve(manager, DATASET).orElseThrow();
      assertFalse("the class is added below", knows(before, "Disconnector"));

      // Somebody adds a class in the RDFArchitect window: content and change log move together.
      turtle = profile("Breaker", "Disconnector");
      changeId = "9f1c1c1e-0000-4000-8000-000000000001";

      boolean seen = false;
      for (int i = 0; i < 100 && !seen; i++) {
        Thread.sleep(50);
        seen =
            manager
                .resolveSchema(RdfArchitectDirective.SCHEME + DATASET, null)
                .map(rs -> knows(rs, "Disconnector"))
                .orElse(false);
      }

      assertTrue("the edit must reach validation without a reload by hand", seen);
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void withoutAConnectionABareDatasetNameIsReported() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      // Nothing connected: the name has no instance to be looked up in.
      assertTrue(manager.resolveSchema(RdfArchitectDirective.SCHEME + DATASET, null).isEmpty());
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void connectingDropsWhatAnotherSessionHadServed() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      manager.connectRdfArchitect(baseUrl(), SESSION);
      assertTrue(resolve(manager, DATASET).isPresent());

      // A different window: its session cannot see this dataset, so the cached schema must go.
      manager.connectRdfArchitect(baseUrl(), "SOME-OTHER-SESSION");

      assertTrue(
          "a schema from the previous session must not survive",
          manager.resolveSchema(RdfArchitectDirective.SCHEME + DATASET, null).isEmpty());
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void theCommandConnectsAndDisconnects() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      var workspace =
          new SparqlWorkspaceService(
              manager,
              new SparqlTextDocumentService(manager, new NotebookDefaults()),
              new NotebookCommandHandler(),
              new NotebookDefaults());

      Object connected = run(workspace, baseUrl(), SESSION);
      assertEquals(Map.of("connected", true, "url", baseUrl()), connected);
      assertEquals(Optional.of(baseUrl()), manager.connectedRdfArchitect());

      Object disconnected = run(workspace, baseUrl(), null);
      assertEquals(Map.of("connected", false, "url", ""), disconnected);
      assertEquals(Optional.empty(), manager.connectedRdfArchitect());
    } finally {
      manager.shutdown();
    }
  }

  private static Object run(SparqlWorkspaceService workspace, String... args)
      throws ExecutionException, InterruptedException {
    return workspace
        .executeCommand(
            new ExecuteCommandParams(
                SparqlWorkspaceService.CMD_CONNECT_RDFARCHITECT,
                java.util.Arrays.stream(args).map(a -> (Object) a).toList()))
        .get();
  }
}
