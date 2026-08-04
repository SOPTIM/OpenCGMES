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

package de.soptim.opencgmes.cimvocabcheck.core.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfArchitectSchemaLoader.RdfArchitectException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the RDFArchitect schema source against a stub of its REST API, wired to the shapes the real
 * one serves: graph lists as {@code {prefix, suffix}} objects, graph content as Turtle, and
 * datasets that only appear once their snapshot has been loaded into the session.
 */
public class RdfArchitectSchemaLoaderTest {

  private static final String TOKEN = "ffPKWuq2hw8WKBRn5VwEOA";
  private static final String GRAPH = "http://graph#Equipment";
  private static final String SNAPSHOT_DATASET = "SNAPSHOT_profiles_" + TOKEN;

  /** A minimal CGMES-3.0-shaped profile: one ontology, one class, one attribute. */
  private static final String TURTLE =
      """
      @prefix cim:  <http://iec.ch/TC57/CIM100#> .
      @prefix cims: <http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#> .
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix dcat: <http://www.w3.org/ns/dcat#> .

      <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0> a owl:Ontology ;
          owl:versionIRI <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0> ;
          dcat:keyword "EQ" .

      cim:ACLineSegment a rdfs:Class ;
          rdfs:label "ACLineSegment" .

      cim:ACLineSegment.r a rdf:Property ;
          rdfs:label "r" ;
          rdfs:domain cim:ACLineSegment ;
          cims:dataType cim:Resistance .
      """;

  private HttpServer server;
  private final List<String> requested = new ArrayList<>();
  private boolean snapshotLoaded;

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

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
    requested.add(path);
    if (path.equals("/api/snapshots/" + TOKEN)) {
      snapshotLoaded = true;
      respond(exchange, 200, "loaded");
    } else if (path.equals("/api/datasets")) {
      // Datasets are session-scoped: nothing is visible until the snapshot is loaded.
      respond(exchange, 200, snapshotLoaded ? "[\"" + SNAPSHOT_DATASET + "\"]" : "[]");
    } else if (path.equals("/api/datasets/" + SNAPSHOT_DATASET + "/graphs")) {
      respond(exchange, 200, "[{\"prefix\":\"http://graph#\",\"suffix\":\"Equipment\"}]");
    } else if (path.equals("/api/datasets/" + SNAPSHOT_DATASET + "/graphs/" + GRAPH + "/content")) {
      respond(exchange, 200, TURTLE);
    } else {
      respond(exchange, 404, "");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private EndpointSchema load(String url) {
    return RdfArchitectSchemaLoader.load(RdfArchitectSource.parse(url), Duration.ofSeconds(10));
  }

  @Test
  public void buildsASchemaFromASnapshotLink() {
    EndpointSchema schema = load(baseUrl() + "/?snapshot=" + TOKEN);

    assertTrue(schema.hasSchema());
    assertNotNull(schema.index());
    assertEquals(List.of(GRAPH), schema.schemaGraphNames());
  }

  @Test
  public void loadsTheSnapshotBeforeReadingTheDataset() {
    load(baseUrl() + "/?snapshot=" + TOKEN);

    assertEquals("/api/snapshots/" + TOKEN, requested.get(0));
    assertTrue(requested.contains("/api/datasets/" + SNAPSHOT_DATASET + "/graphs"));
  }

  @Test
  public void acceptsAMainpageDeepLink() {
    EndpointSchema schema = load(baseUrl() + "/mainpage?snapshot=" + TOKEN + "&class=cim:Foo");

    assertTrue(schema.hasSchema());
  }

  @Test
  public void reportsADatasetTheSessionCannotSee() {
    RdfArchitectException e =
        assertThrows(
            RdfArchitectException.class, () -> load(baseUrl() + "/?dataset=not-in-this-session"));

    assertTrue(e.getMessage(), e.getMessage().contains("session-scoped"));
  }

  @Test
  public void reportsAnUnreachableInstance() {
    RdfArchitectException e =
        assertThrows(
            RdfArchitectException.class,
            () ->
                RdfArchitectSchemaLoader.load(
                    RdfArchitectSource.parse("http://127.0.0.1:1/?snapshot=" + TOKEN),
                    Duration.ofSeconds(2)));

    assertTrue(e.getMessage(), e.getMessage().contains("Could not reach RDFArchitect"));
  }

  @Test
  public void parsesSnapshotAndDatasetLinks() {
    RdfArchitectSource snapshot = RdfArchitectSource.parse("http://host:3000/?snapshot=abc");
    assertEquals("http://host:3000", snapshot.baseUrl());
    assertEquals("abc", snapshot.snapshot());

    RdfArchitectSource dataset = RdfArchitectSource.parse("https://host/rdfa/mainpage?dataset=eq");
    assertEquals("https://host/rdfa", dataset.baseUrl());
    assertEquals("eq", dataset.dataset());
  }

  @Test
  public void rejectsALinkWithoutADatasetOrSnapshot() {
    assertThrows(
        IllegalArgumentException.class, () -> RdfArchitectSource.parse("http://localhost:3000/"));
  }
}
