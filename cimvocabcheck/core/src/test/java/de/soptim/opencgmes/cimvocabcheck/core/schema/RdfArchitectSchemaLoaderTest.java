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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
  private static final String SESSION = "4CD09ADDD8817D3369A57E60A9FDC506";
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
  private final List<String> cookies = new ArrayList<>();
  private boolean snapshotLoaded;
  private String changeId = "";

  /** The dataset a borrowed session can see, as opposed to the snapshot one. */
  private static final String LIVE_DATASET = "live";

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
    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
    if (cookie != null) {
      cookies.add(cookie);
    }
    // A borrowed session sees the live dataset; a session of the loader's own sees only what it
    // loaded itself — which is how the real backend behaves.
    boolean borrowed = cookie != null && cookie.contains(SESSION);
    if (path.equals("/api/snapshots/" + TOKEN)) {
      snapshotLoaded = true;
      respond(exchange, 200, "loaded");
    } else if (path.equals("/api/datasets")) {
      String visible =
          borrowed
              ? "[\"" + LIVE_DATASET + "\"]"
              : snapshotLoaded ? "[\"" + SNAPSHOT_DATASET + "\"]" : "[]";
      respond(exchange, 200, visible);
    } else if (path.endsWith("/graphs")) {
      respond(exchange, 200, "[{\"prefix\":\"http://graph#\",\"suffix\":\"Equipment\"}]");
    } else if (path.endsWith("/content")) {
      respond(exchange, 200, TURTLE);
    } else if (path.endsWith("/changes")) {
      respond(exchange, 200, changeId.isEmpty() ? "[]" : "[{\"changeId\":\"" + changeId + "\"}]");
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

    assertTrue(e.getMessage(), e.getMessage().contains("datasets belong to a session"));
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
  public void readsALiveDatasetThroughABorrowedSession() {
    EndpointSchema schema =
        RdfArchitectSchemaLoader.load(
            RdfArchitectSource.parse(baseUrl() + "/?dataset=" + LIVE_DATASET),
            Duration.ofSeconds(10),
            SESSION);

    assertTrue(schema.hasSchema());
    assertTrue(
        "every request must carry the borrowed session",
        cookies.stream().allMatch(c -> c.contains(SESSION)));
    assertFalse("a live dataset needs no snapshot", requested.contains("/api/snapshots/" + TOKEN));
  }

  @Test
  public void neverLoadsASnapshotIntoABorrowedSession() {
    // Loading one would add a dataset to somebody's open editor, so the snapshot gets its own
    // session even when a session was offered.
    RdfArchitectSchemaLoader.load(
        RdfArchitectSource.parse(baseUrl() + "/?snapshot=" + TOKEN),
        Duration.ofSeconds(10),
        SESSION);

    assertTrue(requested.contains("/api/snapshots/" + TOKEN));
    assertTrue(
        "the snapshot must not be loaded into the borrowed session",
        cookies.stream().noneMatch(c -> c.contains(SESSION)));
  }

  @Test
  public void changeStampFollowsTheChangeLog() {
    var source = RdfArchitectSource.parse(baseUrl() + "/?dataset=" + LIVE_DATASET);

    String before = RdfArchitectSchemaLoader.changeStamp(source, Duration.ofSeconds(10), SESSION);
    changeId = "9f1c1c1e-0000-4000-8000-000000000001";
    String after = RdfArchitectSchemaLoader.changeStamp(source, Duration.ofSeconds(10), SESSION);

    assertNotNull(before);
    assertNotEquals("an edit in RDFArchitect must move the stamp", before, after);
  }

  @Test
  public void changeStampIsNullWhenTheInstanceCannotBeRead() {
    assertNull(
        RdfArchitectSchemaLoader.changeStamp(
            RdfArchitectSource.parse("http://127.0.0.1:1/?dataset=nope"),
            Duration.ofSeconds(2),
            null));
  }

  @Test
  public void resolvesABareDatasetNameAgainstTheConnectedInstance() {
    RdfArchitectSource source = RdfArchitectSource.parse("cgmes-3.0", "http://host:3000/");

    assertEquals("http://host:3000", source.baseUrl());
    assertEquals("cgmes-3.0", source.dataset());
    assertNull(source.snapshot());
  }

  @Test
  public void rejectsABareDatasetNameWithoutAConnectedInstance() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> RdfArchitectSource.parse("cgmes-3.0"));

    assertTrue(e.getMessage(), e.getMessage().contains("no RDFArchitect session is connected"));
  }

  @Test
  public void loadsTheSnapshotBehindASnapshotDatasetName() {
    // The name a loaded snapshot has in the address bar — what a user copies into a config. It
    // exists only in sessions that loaded that snapshot, so the token has to be recovered from it.
    EndpointSchema schema = load(baseUrl() + "/?dataset=" + SNAPSHOT_DATASET);

    assertTrue(schema.hasSchema());
    assertTrue(requested.contains("/api/snapshots/" + TOKEN));
  }

  @Test
  public void aBareSnapshotDatasetNameAlsoLoadsIt() {
    RdfArchitectSource source = RdfArchitectSource.parse(SNAPSHOT_DATASET, baseUrl());

    assertEquals(TOKEN, source.snapshot());
    assertEquals(SNAPSHOT_DATASET, source.dataset());
    assertTrue(RdfArchitectSchemaLoader.load(source, Duration.ofSeconds(10), SESSION).hasSchema());
    assertTrue(
        "a snapshot must not be loaded into the borrowed session",
        cookies.stream().noneMatch(c -> c.contains(SESSION)));
  }

  @Test
  public void takesTheWholeTokenWhenItContainsAnUnderscore() {
    // Real tokens are base64url, so "_" occurs inside them — splitting on the last one truncates.
    RdfArchitectSource source =
        RdfArchitectSource.parse("SNAPSHOT_cimnotebook-test_iLdGrIScuO2wWUtWv_NDvw", "http://host");

    assertEquals("iLdGrIScuO2wWUtWv_NDvw", source.snapshot());
  }

  @Test
  public void takesTheTokenWhenTheDatasetNameContainsUnderscores() {
    RdfArchitectSource source =
        RdfArchitectSource.parse("SNAPSHOT_my_profile_set_ffPKWuq2hw8WKBRn5VwEOA", "http://host");

    assertEquals("ffPKWuq2hw8WKBRn5VwEOA", source.snapshot());
  }

  @Test
  public void acceptsASnapshotGivenAsItsDatasetName() {
    // ?snapshot= with the name from the address bar rather than the token behind it.
    RdfArchitectSource source =
        RdfArchitectSource.parse("http://host/?snapshot=SNAPSHOT_profiles_" + TOKEN);

    assertEquals(TOKEN, source.snapshot());
  }

  @Test
  public void treatsAnOrdinaryNameStartingWithSnapshotAsADataset() {
    RdfArchitectSource source = RdfArchitectSource.parse("SNAPSHOT_", "http://host:3000");

    assertNull(source.snapshot());
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
