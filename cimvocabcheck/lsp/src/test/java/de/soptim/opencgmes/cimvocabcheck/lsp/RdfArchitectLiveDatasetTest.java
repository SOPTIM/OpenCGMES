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

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

  /** A minimal ENTSO-E RDFS profile — the schema the config switches over to below. */
  private static final String SCHEMA_RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.shortName">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">EQ</cims:isFixed>
        </rdf:Description>
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.entsoeURI">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/TestProfile/1</cims:isFixed>
        </rdf:Description>
        <rdfs:Class rdf:about="http://iec.ch/TC57/2013/CIM-schema-cim16#Breaker">
          <rdfs:label>Breaker</rdfs:label>
        </rdfs:Class>
      </rdf:RDF>
      """;

  private HttpServer server;
  private String changeId = "";

  /** Held requests: a test opens this to prove a caller was not waiting behind the instance. */
  private volatile java.util.concurrent.CountDownLatch gate;

  /** Whether the instance answers at all — an outage, as far as a caller can tell. */
  private volatile boolean down;

  private final java.util.concurrent.atomic.AtomicInteger requests =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.List<String> cookiesSeen = new java.util.ArrayList<>();
  private final java.util.concurrent.atomic.AtomicInteger changeLogPolls =
      new java.util.concurrent.atomic.AtomicInteger();

  @Before
  public void setUp() throws IOException {
    SchemaManager.liveCheckInterval = Duration.ofMillis(50);
    SchemaManager.failureTtl = Duration.ofMillis(100);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/", this::dispatch);
    server.start();
  }

  @After
  public void tearDown() {
    if (gate != null) {
      gate.countDown();
    }
    server.stop(0);
    SchemaManager.liveCheckInterval = Duration.ofSeconds(3);
    SchemaManager.failureTtl = Duration.ofSeconds(30);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    java.util.concurrent.CountDownLatch held = gate;
    if (held != null) {
      try {
        held.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (down) {
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
      return;
    }
    // getPath() is percent-decoded, which is all a server does to a path — decoding again would
    // turn a form-encoded '+' back into a space and hide the difference between the two.
    String path = exchange.getRequestURI().getPath();
    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
    if (cookie != null) {
      cookiesSeen.add(cookie);
    }
    boolean connected = SESSION.equals(cookieValue(cookie));
    String body;
    if (path.equals("/api/datasets")) {
      // Without the connected session there is nothing to see — datasets live in a session.
      body = connected ? "[\"" + DATASET + "\"]" : "[]";
    } else if (path.endsWith("/graphs")) {
      body = "[{\"prefix\":\"http://graph#\",\"suffix\":\"EQ\"}]";
    } else if (path.endsWith("/content")) {
      body = turtle;
    } else if (path.endsWith("/changes")) {
      changeLogPolls.incrementAndGet();
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
  public void neverSendsTheSessionToAnotherInstance() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      manager.connectRdfArchitect(baseUrl(), SESSION);

      // The config names an instance of its own — the same server here, but under a different
      // address, so it is a different instance as far as a credential is concerned.
      String elsewhere =
          "http://localhost:" + server.getAddress().getPort() + "/?dataset=" + DATASET;
      resolve(manager, elsewhere);

      assertTrue(
          "the connected session must not be handed to an instance the config named itself",
          cookiesSeen.stream().noneMatch(c -> c.contains(SESSION)));
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

  /**
   * A client that spells "no session" as an explicit JSON {@code null} disconnects, like one that
   * simply leaves the argument off.
   *
   * <p>The two forms travel differently: lsp4j hands a JSON {@code null} over as {@link JsonNull},
   * which is a {@code JsonElement} but not a primitive — read as a string it would become {@code
   * "null"} and connect a session by that name. The in-process form the test above uses carries a
   * Java {@code null} instead, so it cannot catch this.
   */
  @Test
  public void aJsonNullSessionDisconnectsLikeAMissingOne() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      var workspace =
          new SparqlWorkspaceService(
              manager,
              new SparqlTextDocumentService(manager, new NotebookDefaults()),
              new NotebookCommandHandler(),
              new NotebookDefaults());

      Object connected =
          runWire(workspace, new JsonPrimitive(baseUrl()), new JsonPrimitive(SESSION));
      assertEquals(Map.of("connected", true, "url", baseUrl()), connected);

      Object disconnected = runWire(workspace, new JsonPrimitive(baseUrl()), JsonNull.INSTANCE);
      assertEquals(Map.of("connected", false, "url", ""), disconnected);
      assertEquals(Optional.empty(), manager.connectedRdfArchitect());
    } finally {
      manager.shutdown();
    }
  }

  /**
   * A workspace that stops naming RDFArchitect must stop being polled for edits over there — the
   * poll is armed per config, and only a reload can disarm it.
   */
  @Test
  public void aConfigThatDropsRdfArchitectStopsBeingPolled() throws Exception {
    Path workspace = java.nio.file.Files.createTempDirectory("rdfa-live");
    Path config = workspace.resolve("opencgmes.jsonc");
    Path schema = workspace.resolve("profile.rdf");
    java.nio.file.Files.writeString(schema, SCHEMA_RDF);
    java.nio.file.Files.writeString(
        config, "{ \"cimvocabcheck\": { \"rdfArchitect\": \"" + DATASET + "\" } }");

    SchemaManager manager = new SchemaManager();
    try {
      manager.connectRdfArchitect(baseUrl(), SESSION);
      manager.loadAsync(workspace);
      for (int i = 0; i < 100 && manager.getApi().isEmpty(); i++) {
        Thread.sleep(50);
      }
      assertTrue(
          "the workspace schema must come from RDFArchitect first", manager.getApi().isPresent());

      // The user points the config at a schema file instead.
      java.nio.file.Files.writeString(
          config, "{ \"cimvocabcheck\": { \"schemas\": [\"profile.rdf\"] } }");
      manager.reloadAsync();
      for (int i = 0; i < 100; i++) {
        Thread.sleep(50);
        if (manager
            .workspaceSchemaFor(workspace)
            .map(ws -> ws.definitionIndex() != null)
            .orElse(false)) {
          break;
        }
      }

      int polls = changeLogPolls.get();
      for (int i = 0; i < 10; i++) {
        Thread.sleep(20);
        manager.workspaceSchemaFor(workspace);
      }
      assertEquals(
          "nothing in RDFArchitect backs this workspace any more", polls, changeLogPolls.get());
    } finally {
      manager.shutdown();
      java.nio.file.Files.deleteIfExists(schema);
      java.nio.file.Files.deleteIfExists(config);
      java.nio.file.Files.deleteIfExists(workspace);
    }
  }

  /**
   * A config below the workspace root is resolved on whichever thread asked for the schema — a
   * validate, hover, completion or definition request. Reading RDFArchitect there would hold that
   * thread for as long as the instance takes to answer, so the load has to happen elsewhere.
   */
  @Test(timeout = 60_000)
  public void aNestedConfigReadsRdfArchitectOffTheRequestThread() throws Exception {
    Path workspace = java.nio.file.Files.createTempDirectory("rdfa-nested");
    Path nested = java.nio.file.Files.createDirectories(workspace.resolve("queries"));
    java.nio.file.Files.writeString(
        nested.resolve("opencgmes.jsonc"),
        "{ \"cimvocabcheck\": { \"rdfArchitect\": \"" + DATASET + "\" } }");

    SchemaManager manager = new SchemaManager();
    try {
      manager.connectRdfArchitect(baseUrl(), SESSION);
      manager.loadAsync(workspace); // the root itself has no config

      gate = new java.util.concurrent.CountDownLatch(1);
      // Would block until the gate opens if the instance were read here.
      assertTrue(
          "a document must not wait for RDFArchitect to answer",
          manager.workspaceSchemaFor(nested).isEmpty());
      gate.countDown();
      gate = null;

      assertTrue("and the schema must still arrive", await(manager, nested));
    } finally {
      manager.shutdown();
      deleteTree(workspace);
    }
  }

  /**
   * A file that fails to load fails the same way every time; an instance that cannot be reached
   * does not. The workspace must come back on its own once it can be.
   */
  @Test(timeout = 60_000)
  public void anInstanceThatComesBackIsPickedUpAgain() throws Exception {
    Path workspace = java.nio.file.Files.createTempDirectory("rdfa-retry");
    java.nio.file.Files.writeString(
        workspace.resolve("opencgmes.jsonc"),
        "{ \"cimvocabcheck\": { \"rdfArchitect\": \"" + DATASET + "\" } }");

    SchemaManager manager = new SchemaManager();
    try {
      down = true;
      manager.connectRdfArchitect(baseUrl(), SESSION);
      manager.loadAsync(workspace);
      for (int i = 0; i < 200 && requests.get() == 0; i++) {
        Thread.sleep(20);
      }
      assertTrue("the failing load must have been attempted", requests.get() > 0);
      assertTrue("and must have left no schema", manager.workspaceSchemaFor(workspace).isEmpty());

      down = false;

      assertTrue("the workspace must recover without being reloaded", await(manager, workspace));
    } finally {
      manager.shutdown();
      deleteTree(workspace);
    }
  }

  /** Asks for {@code docDir}'s schema until it is there, the way an editor keeps asking. */
  private static boolean await(SchemaManager manager, Path docDir) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (manager.workspaceSchemaFor(docDir).isPresent()) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private static void deleteTree(Path root) throws IOException {
    try (var paths = java.nio.file.Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
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

  /** The same command as {@link #run}, with the Gson elements lsp4j delivers over the wire. */
  private static Object runWire(SparqlWorkspaceService workspace, JsonElement... args)
      throws ExecutionException, InterruptedException {
    return workspace
        .executeCommand(
            new ExecuteCommandParams(
                SparqlWorkspaceService.CMD_CONNECT_RDFARCHITECT,
                java.util.Arrays.stream(args).map(a -> (Object) a).toList()))
        .get();
  }
}
