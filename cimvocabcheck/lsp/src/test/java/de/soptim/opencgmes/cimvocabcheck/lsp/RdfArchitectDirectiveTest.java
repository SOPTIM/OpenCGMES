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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.jena.graph.NodeFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@code # [rdfarchitect=...]} directive: how it is parsed, and that {@link
 * SchemaManager} loads the named model from the instance. Its precedence over the {@code #
 * [endpoint=...]} directives a document may also carry is covered by {@link
 * RdfArchitectTermsCommandTest}.
 */
public class RdfArchitectDirectiveTest {

  private static final String TOKEN = "tok123";
  private static final String DATASET = "SNAPSHOT_profiles_" + TOKEN;
  private static final String GRAPH = "http://graph#EQ";
  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  private static final String TURTLE =
      """
      @prefix cim:  <http://iec.ch/TC57/CIM100#> .
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix dcat: <http://www.w3.org/ns/dcat#> .

      <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0> a owl:Ontology ;
          owl:versionIRI <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0> ;
          dcat:keyword "EQ" .

      cim:Breaker a rdfs:Class ;
          rdfs:label "Breaker" .
      """;

  private HttpServer server;

  @Before
  public void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/", this::dispatch);
    server.start();
  }

  @After
  public void tearDown() {
    server.stop(0);
  }

  private String link() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/?snapshot=" + TOKEN;
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
    String body =
        switch (path) {
          case "/api/snapshots/" + TOKEN -> "loaded";
          case "/api/datasets" -> "[\"" + DATASET + "\"]";
          case "/api/datasets/" + DATASET + "/graphs" ->
              "[{\"prefix\":\"http://graph#\",\"suffix\":\"EQ\"}]";
          case "/api/datasets/" + DATASET + "/graphs/" + GRAPH + "/content" -> TURTLE;
          default -> null;
        };
    byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  public void parsesTheDirective() {
    Optional<String> parsed =
        RdfArchitectDirective.parse(
            "# [rdfarchitect=http://localhost:3000/?snapshot=abc]\nSELECT *");

    assertEquals(Optional.of("http://localhost:3000/?snapshot=abc"), parsed);
  }

  @Test
  public void ignoresADocumentWithoutTheDirective() {
    assertEquals(Optional.empty(), RdfArchitectDirective.parse("SELECT * WHERE { ?s ?p ?o }"));
  }

  @Test
  public void loadsTheSchemaFromTheInstance() throws Exception {
    SchemaManager manager = new SchemaManager();
    try {
      String source = RdfArchitectDirective.SCHEME + link();

      // The load is asynchronous: empty until the fetch lands, then cached.
      Optional<ResolvedSchema> resolved = Optional.empty();
      for (int i = 0; i < 100 && resolved.isEmpty(); i++) {
        resolved = manager.resolveSchema(source, null);
        if (resolved.isEmpty()) {
          Thread.sleep(50);
        }
      }

      assertTrue("the schema must load from the RDFArchitect stub", resolved.isPresent());
      assertFalse(
          "Breaker must be known to the loaded schema",
          resolved
              .get()
              .api()
              .schemaIndex()
              .findClass(NodeFactory.createURI(CIM + "Breaker"))
              .isEmpty());
      assertNull(
          "an RDFArchitect schema has no local source file to jump to",
          resolved.get().definitionIndex());
    } finally {
      manager.shutdown();
    }
  }
}
