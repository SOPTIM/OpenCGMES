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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A term declared in several profiles must be reported with all of them, so the editor can ask
 * which one to open rather than letting RDFArchitect pick whichever graph it finds it in first.
 *
 * <p>The stub serves a dataset of two profile graphs that share a class.
 */
public class RdfArchitectTermProfilesTest {

  private static final String SESSION = "9E77B4B8B9B34B0E90C77C0C0E4E8F11";
  private static final String DATASET = "cgmes-3.0";
  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String DOC_URI = "file:///queries/live.rq";

  private static final String QUERY =
      "# [rdfarchitect="
          + DATASET
          + "]\n"
          + "PREFIX cim: <"
          + CIM
          + ">\n"
          + "SELECT * WHERE { ?s a cim:Breaker ; a cim:TopologicalNode }\n";

  /** Two graphs: the shared class is in both, the other class in one. */
  private static final Map<String, String> GRAPHS =
      Map.of(
          "http://graph#EQ",
          profile("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0", "EQ", "Breaker"),
          "http://graph#TP",
          profile("http://iec.ch/TC57/ns/CIM/Topology-EU/3.0", "TP", "Breaker", "TopologicalNode"));

  private static String profile(String versionIri, String keyword, String... classes) {
    StringBuilder ttl =
        new StringBuilder(
            """
            @prefix cim:  <http://iec.ch/TC57/CIM100#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .

            """);
    ttl.append('<')
        .append(versionIri)
        .append("> a owl:Ontology ; owl:versionIRI <")
        .append(versionIri)
        .append("> ; dcat:keyword \"")
        .append(keyword)
        .append("\" .\n");
    for (String name : classes) {
      ttl.append("cim:").append(name).append(" a rdfs:Class ; rdfs:label \"").append(name);
      ttl.append("\" .\n");
    }
    return ttl.toString();
  }

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private HttpServer server;
  private SchemaManager manager;
  private SparqlTextDocumentService documents;
  private SparqlWorkspaceService workspace;

  @Before
  public void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/", this::dispatch);
    server.start();

    manager = new SchemaManager();
    documents = new SparqlTextDocumentService(manager, new NotebookDefaults());
    workspace =
        new SparqlWorkspaceService(
            manager, documents, new NotebookCommandHandler(), new NotebookDefaults());
    manager.connectRdfArchitect(baseUrl(), SESSION);
    documents.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(DOC_URI, "sparql", 1, QUERY)));
  }

  @After
  public void tearDown() {
    manager.shutdown();
    server.stop(0);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
    String body;
    if (path.equals("/api/datasets")) {
      body = "[\"" + DATASET + "\"]";
    } else if (path.endsWith("/graphs")) {
      body =
          "[{\"prefix\":\"http://graph#\",\"suffix\":\"EQ\"},"
              + "{\"prefix\":\"http://graph#\",\"suffix\":\"TP\"}]";
    } else if (path.endsWith("/content")) {
      body = GRAPHS.get(graphOf(path));
    } else if (path.endsWith("/changes")) {
      body = "[]";
    } else {
      body = null;
    }
    byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** The graph name out of {@code /api/datasets/<ds>/graphs/<graph>/content}. */
  private static String graphOf(String path) {
    String rest = path.substring(path.indexOf("/graphs/") + "/graphs/".length());
    return rest.substring(0, rest.lastIndexOf("/content"));
  }

  /** Runs the command once for a document. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> command(String uri) throws Exception {
    return (Map<String, Object>)
        workspace
            .executeCommand(
                new ExecuteCommandParams(
                    SparqlWorkspaceService.CMD_RDFARCHITECT_TERMS, List.of(uri)))
            .get();
  }

  /** Runs the command once the asynchronous schema load has landed. */
  private Map<String, Object> terms() throws Exception {
    for (int i = 0; i < 100; i++) {
      Map<String, Object> result = command(DOC_URI);
      if (result != null && !profilesOf(result, CIM + "Breaker").isEmpty()) {
        return result;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("the schema never loaded from the stub");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> profilesOf(Map<String, Object> result, String iri) {
    for (Map<String, Object> term : (List<Map<String, Object>>) result.get("terms")) {
      if (iri.equals(term.get("iri"))) {
        return (List<Map<String, Object>>) term.get("profiles");
      }
    }
    return List.of();
  }

  @Test
  public void reportsEveryProfileDeclaringATerm() throws Exception {
    Map<String, Object> result = terms();

    List<Map<String, Object>> profiles = profilesOf(result, CIM + "Breaker");

    assertEquals(2, profiles.size());
    // Sorted by version IRI, so the same profile is offered first on every run.
    assertEquals("CoreEquipment-EU/3.0", profiles.get(0).get("label"));
    assertEquals("http://graph#EQ", profiles.get(0).get("graph"));
    assertEquals("Topology-EU/3.0", profiles.get(1).get("label"));
    assertEquals("http://graph#TP", profiles.get(1).get("graph"));
  }

  @Test
  public void reportsTheOneProfileOfATermDeclaredOnlyThere() throws Exception {
    List<Map<String, Object>> profiles = profilesOf(terms(), CIM + "TopologicalNode");

    assertEquals(1, profiles.size());
    assertEquals("http://graph#TP", profiles.get(0).get("graph"));
  }

  /**
   * The everyday setup: no directive in the document, the dataset named once in {@code
   * opencgmes.jsonc}. It reaches the same answer by a different route — the config, not the text.
   */
  @Test
  public void reportsTermsForAWorkspaceConfiguredRdfArchitect() throws Exception {
    File root = tmp.newFolder("workspace");
    Files.writeString(
        root.toPath().resolve("opencgmes.jsonc"),
        "{ \"cimvocabcheck\": { \"rdfArchitect\": \"" + DATASET + "\" } }");
    String uri = root.toPath().resolve("query.rq").toUri().toString();
    String query = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s a cim:Breaker }\n";
    documents.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "sparql", 1, query)));
    manager.loadAsync(root.toPath());

    Map<String, Object> result = null;
    for (int i = 0; i < 100 && result == null; i++) {
      result = command(uri);
      if (result == null || profilesOf(result, CIM + "Breaker").isEmpty()) {
        result = null;
        Thread.sleep(50);
      }
    }

    assertNotNull("a config-configured RDFArchitect must produce term links too", result);
    assertEquals(DATASET, result.get("dataset"));
    assertEquals(2, profilesOf(result, CIM + "Breaker").size());
  }

  /**
   * The gesture that has to work: Ctrl+Click. It is the editors' own go-to-definition, so the
   * server has to answer with real locations — anything else leaves the term without so much as an
   * underline.
   */
  @Test
  public void answersGoToDefinitionWithOneDocumentPerProfile() throws Exception {
    terms(); // wait for the schema

    var params =
        new org.eclipse.lsp4j.DefinitionParams(
            new org.eclipse.lsp4j.TextDocumentIdentifier(DOC_URI),
            new org.eclipse.lsp4j.Position(2, QUERY.split("\n")[2].indexOf("cim:Breaker") + 5));
    List<? extends org.eclipse.lsp4j.Location> locations =
        documents.definition(params).get().getLeft();

    assertEquals("declared in both profiles", 2, locations.size());
    for (var location : locations) {
      Path file = Path.of(java.net.URI.create(location.getUri()));
      assertTrue("the definition document must exist", Files.exists(file));
      String header = Files.readAllLines(file).get(0);
      assertTrue(
          "the editor reads the first line to open the term in RDFArchitect: " + header,
          header.startsWith("#! rdfarchitect ") && header.contains("class=http"));
      assertTrue("and it names the graph to open it in", header.contains("graph=http"));
      assertTrue("the term is in the document", Files.readString(file).contains("Breaker"));
    }
    // One document per profile, kept apart by the profile's own directory.
    assertTrue(
        locations.get(0).getUri().contains("CoreEquipment-EU")
            || locations.get(1).getUri().contains("CoreEquipment-EU"));
  }

  @Test
  public void namesTheDatasetToOpenTermsIn() throws Exception {
    // The dataset is read live, so it is called the same in the window the editor embeds.
    assertEquals(DATASET, terms().get("dataset"));
    assertTrue(((String) terms().get("baseUrl")).startsWith("http://127.0.0.1:"));
  }
}
