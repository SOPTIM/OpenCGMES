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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.update.UpdateFactory;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class HttpQueryExecutorTest {

  private static final String EX = "http://example.org/";
  private static final AtomicInteger SUBJECT_COUNTER = new AtomicInteger();

  private static final CancelChecker NEVER_CANCELLED =
      new CancelChecker() {
        @Override
        public void checkCanceled() {}

        @Override
        public boolean isCanceled() {
          return false;
        }
      };

  private static FusekiServer fuseki;
  private static String queryUrl;
  private static String updateUrl;

  private ExecutorService executionPool;
  private ScheduledExecutorService watchdogScheduler;

  @BeforeClass
  public static void startFuseki() {
    fuseki =
        FusekiServer.create()
            .port(0)
            .add("/ds", DatasetGraphFactory.createTxnMem())
            .build()
            .start();
    queryUrl = fuseki.serverURL() + "ds";
    updateUrl = fuseki.serverURL() + "ds/update";
  }

  @AfterClass
  public static void stopFuseki() {
    fuseki.stop();
  }

  @Before
  public void setUp() {
    executionPool = Executors.newCachedThreadPool();
    watchdogScheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @After
  public void tearDown() {
    executionPool.shutdownNow();
    watchdogScheduler.shutdownNow();
  }

  // ---- happy paths (against the shared in-process Fuseki dataset) --------------------------

  @Test
  public void executeUpdateInsertsDataVisibleToASubsequentQuery() {
    String subject = uniqueSubject();
    var update = UpdateFactory.create("INSERT DATA { <" + subject + "> <" + EX + "p> \"v\" }");

    ExecuteResponse response =
        HttpQueryExecutor.executeUpdate(
            update, target(queryUrl, updateUrl), null, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.UPDATE.name(), response.queryKind());
    assertNull(response.error());
    assertNotNull(response.stats());
    assertEquals(updateUrl, response.stats().resolvedTarget());

    Query ask = QueryFactory.create("ASK { <" + subject + "> <" + EX + "p> \"v\" }");
    ExecuteResponse askResponse =
        HttpQueryExecutor.executeQuery(
            ask, QueryKind.ASK, target(queryUrl, updateUrl), null, ctx(NEVER_CANCELLED));
    assertEquals(ExecutionStatus.SUCCESS.name(), askResponse.status());
    assertTrue(asBoolean(askResponse));
  }

  @Test
  public void executeQueryAskReturnsFalseWhenNoMatch() {
    Query ask = QueryFactory.create("ASK { <" + EX + "does-not-exist> <" + EX + "p> ?o }");
    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            ask, QueryKind.ASK, target(queryUrl, updateUrl), null, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertFalse(asBoolean(response));
  }

  @Test
  public void executeQuerySelectReturnsAllRowsUnderTheLimit() {
    String subject = uniqueSubject();
    insertTriples(subject, 3);
    Query select = QueryFactory.create("SELECT ?o WHERE { <" + subject + "> <" + EX + "p> ?o }");

    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            select, QueryKind.SELECT, target(queryUrl, updateUrl), null, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.SELECT.name(), response.queryKind());
    assertNotNull(response.resultsJson());
    assertNull(response.turtle());
    assertFalse(response.stats().truncated());
    assertEquals(3, response.stats().rowCount().intValue());
    assertEquals(
        3,
        JsonParser.parseString(response.resultsJson())
            .getAsJsonObject()
            .getAsJsonObject("results")
            .getAsJsonArray("bindings")
            .size());
  }

  @Test
  public void executeQuerySelectTruncatesAtConfiguredMaxRows() {
    String subject = uniqueSubject();
    insertTriples(subject, 5);
    Query select = QueryFactory.create("SELECT ?o WHERE { <" + subject + "> <" + EX + "p> ?o }");
    var options = new ExecuteOptions(null, 2);

    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            select, QueryKind.SELECT, target(queryUrl, updateUrl), options, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertTrue(response.stats().truncated());
    assertEquals(2, response.stats().rowCount().intValue());
  }

  @Test
  public void executeQueryConstructReturnsParseableTurtle() {
    String subject = uniqueSubject();
    insertTriples(subject, 2);
    Query construct =
        QueryFactory.create(
            "CONSTRUCT { <"
                + subject
                + "> <"
                + EX
                + "p> ?o } WHERE { <"
                + subject
                + "> <"
                + EX
                + "p> ?o }");

    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            construct,
            QueryKind.CONSTRUCT,
            target(queryUrl, updateUrl),
            null,
            ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.CONSTRUCT.name(), response.queryKind());
    assertNull(response.resultsJson());
    assertNotNull(response.turtle());
    assertEquals(2, parseTurtle(response.turtle()).size());
  }

  @Test
  public void executeQueryDescribeReturnsParseableTurtle() {
    String subject = uniqueSubject();
    insertTriples(subject, 1);
    Query describe = QueryFactory.create("DESCRIBE <" + subject + ">");

    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            describe, QueryKind.DESCRIBE, target(queryUrl, updateUrl), null, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertNotNull(response.turtle());
    assertEquals(1, parseTurtle(response.turtle()).size());
  }

  // ---- target/validation errors --------------------------------------------------------------

  @Test
  public void executeQueryFailsWithNoTargetWhenTargetIsNull() {
    Query ask = QueryFactory.create("ASK {}");
    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(ask, QueryKind.ASK, null, null, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
  }

  @Test
  public void executeQueryFailsWithNoTargetWhenUrlIsBlank() {
    Query ask = QueryFactory.create("ASK {}");
    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            ask, QueryKind.ASK, target("   ", null), null, ctx(NEVER_CANCELLED));

    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
  }

  @Test
  public void executeUpdateFailsWithUpdateNotAllowedWhenUpdateUrlIsBlank() {
    var update = UpdateFactory.create("INSERT DATA { <" + EX + "x> <" + EX + "p> 1 }");
    ExecuteResponse response =
        HttpQueryExecutor.executeUpdate(update, target(queryUrl, null), null, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.UPDATE_NOT_ALLOWED.name(), response.error().code());
  }

  @Test
  public void executeUpdateFailsWithNoTargetWhenTargetIsNull() {
    var update = UpdateFactory.create("INSERT DATA { <" + EX + "x> <" + EX + "p> 1 }");
    ExecuteResponse response =
        HttpQueryExecutor.executeUpdate(update, null, null, ctx(NEVER_CANCELLED));

    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
  }

  @Test
  public void executeUpdateFailsWithNoTargetWhenQueryUrlIsBlankEvenIfUpdateUrlIsSet() {
    // The query url is the base "is anything configured at all" check and applies uniformly to
    // both executeQuery and executeUpdate; a blank query url is NO_TARGET even when updateUrl is
    // present. In practice the client always derives both urls from the same directive, so this
    // combination should not arise, but the precedence is worth pinning down explicitly.
    var update = UpdateFactory.create("INSERT DATA { <" + EX + "x> <" + EX + "p> 1 }");
    ExecuteResponse response =
        HttpQueryExecutor.executeUpdate(
            update, target(null, updateUrl), null, ctx(NEVER_CANCELLED));

    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
  }

  // ---- transport-level failures (against a throwaway JDK HttpServer) ------------------------

  @Test
  public void executeQueryReportsAuthFailedOn401() throws IOException {
    HttpServer server = respondingWith(401, "unauthorized");
    try {
      Query ask = QueryFactory.create("ASK {}");
      ExecuteResponse response =
          HttpQueryExecutor.executeQuery(
              ask, QueryKind.ASK, target(baseUrl(server), null), null, ctx(NEVER_CANCELLED));

      assertEquals(ExecutionStatus.ERROR.name(), response.status());
      assertEquals(ErrorCode.AUTH_FAILED.name(), response.error().code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void executeQueryReportsHttpErrorOnServerError() throws IOException {
    HttpServer server = respondingWith(500, "boom");
    try {
      Query ask = QueryFactory.create("ASK {}");
      ExecuteResponse response =
          HttpQueryExecutor.executeQuery(
              ask, QueryKind.ASK, target(baseUrl(server), null), null, ctx(NEVER_CANCELLED));

      assertEquals(ExecutionStatus.ERROR.name(), response.status());
      assertEquals(ErrorCode.HTTP_ERROR.name(), response.error().code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void executeQueryReportsHttpErrorOnConnectionRefused() throws IOException {
    int freePort;
    try (ServerSocket probe = new ServerSocket(0)) {
      freePort = probe.getLocalPort();
    }
    Query ask = QueryFactory.create("ASK {}");

    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            ask,
            QueryKind.ASK,
            target("http://localhost:" + freePort + "/nope", null),
            null,
            ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.HTTP_ERROR.name(), response.error().code());
  }

  @Test
  public void executeQueryReportsTimeoutWhenEndpointIsSlow() throws IOException {
    HttpServer server = sleepingFor(2_000);
    try {
      Query ask = QueryFactory.create("ASK {}");
      var options = new ExecuteOptions(200, null);

      ExecuteResponse response =
          HttpQueryExecutor.executeQuery(
              ask, QueryKind.ASK, target(baseUrl(server), null), options, ctx(NEVER_CANCELLED));

      assertEquals(ExecutionStatus.ERROR.name(), response.status());
      assertEquals(ErrorCode.TIMEOUT.name(), response.error().code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void executeUpdateReportsTimeoutWhenEndpointIsSlow() throws IOException {
    HttpServer server = sleepingFor(2_000);
    try {
      var update = UpdateFactory.create("INSERT DATA { <" + EX + "x> <" + EX + "p> 1 }");
      var options = new ExecuteOptions(200, null);

      ExecuteResponse response =
          HttpQueryExecutor.executeUpdate(
              update, target(queryUrl, baseUrl(server)), options, ctx(NEVER_CANCELLED));

      assertEquals(ExecutionStatus.ERROR.name(), response.status());
      assertEquals(ErrorCode.TIMEOUT.name(), response.error().code());
    } finally {
      server.stop(0);
    }
  }

  // ---- cancellation ---------------------------------------------------------------------------

  @Test
  public void executeQueryReturnsCancelledBeforeTheSlowEndpointResponds() throws IOException {
    HttpServer server = sleepingFor(5_000);
    try {
      Query ask = QueryFactory.create("ASK {}");
      var options = new ExecuteOptions(20_000, null);

      long start = System.nanoTime();
      ExecuteResponse response =
          HttpQueryExecutor.executeQuery(
              ask, QueryKind.ASK, target(baseUrl(server), null), options, ctx(cancelAfter(300)));
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertEquals(ExecutionStatus.CANCELLED.name(), response.status());
      assertEquals(ErrorCode.CANCELLED.name(), response.error().code());
      assertTrue(
          "expected to return well before the 5s server delay, took " + elapsedMs + "ms",
          elapsedMs < 4_000);
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void executeUpdateReturnsCancelledEvenThoughAbortCannotUnblockIt() throws IOException {
    // Regression test for the abort()-asymmetry documented on HttpQueryExecutor: unlike queries,
    // UpdateExecution.abort() does not unblock a blocked execute() call, so this only passes
    // because the race against the watchdog signal — not abort() — decides the outcome.
    HttpServer server = sleepingFor(5_000);
    try {
      var update = UpdateFactory.create("INSERT DATA { <" + EX + "x> <" + EX + "p> 1 }");
      var options = new ExecuteOptions(20_000, null);

      long start = System.nanoTime();
      ExecuteResponse response =
          HttpQueryExecutor.executeUpdate(
              update, target(queryUrl, baseUrl(server)), options, ctx(cancelAfter(300)));
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertEquals(ExecutionStatus.CANCELLED.name(), response.status());
      assertTrue(
          "expected to return well before the 5s server delay, took " + elapsedMs + "ms",
          elapsedMs < 4_000);
    } finally {
      server.stop(0);
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private HttpQueryExecutor.ExecContext ctx(CancelChecker checker) {
    return new HttpQueryExecutor.ExecContext(executionPool, watchdogScheduler, checker);
  }

  private static ExecuteTarget target(String url, String updateUrl) {
    return new ExecuteTarget(ExecuteTarget.TYPE_HTTP, url, updateUrl);
  }

  private static String uniqueSubject() {
    return EX + "s" + SUBJECT_COUNTER.incrementAndGet();
  }

  private void insertTriples(String subject, int count) {
    StringBuilder sb = new StringBuilder("INSERT DATA { ");
    for (int i = 0; i < count; i++) {
      sb.append('<').append(subject).append("> <").append(EX).append("p> ").append(i).append(" . ");
    }
    sb.append('}');
    ExecuteResponse response =
        HttpQueryExecutor.executeUpdate(
            UpdateFactory.create(sb.toString()),
            target(queryUrl, updateUrl),
            null,
            ctx(NEVER_CANCELLED));
    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
  }

  private static boolean asBoolean(ExecuteResponse response) {
    return JsonParser.parseString(response.resultsJson())
        .getAsJsonObject()
        .get("boolean")
        .getAsBoolean();
  }

  private static Model parseTurtle(String turtle) {
    Model model = ModelFactory.createDefaultModel();
    model.read(new java.io.StringReader(turtle), null, "TURTLE");
    return model;
  }

  /** A {@link CancelChecker} that reports cancelled once {@code delayMs} has elapsed. */
  private static CancelChecker cancelAfter(long delayMs) {
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
    return new CancelChecker() {
      @Override
      public void checkCanceled() {}

      @Override
      public boolean isCanceled() {
        return System.nanoTime() >= deadlineNanos;
      }
    };
  }

  private static String baseUrl(HttpServer server) {
    return "http://localhost:" + server.getAddress().getPort() + "/endpoint";
  }

  private static HttpServer respondingWith(int status, String body) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/endpoint",
        exchange -> {
          try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    return server;
  }

  private static HttpServer sleepingFor(long millis) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/endpoint",
        exchange -> {
          try {
            Thread.sleep(millis);
            byte[] bytes = "{\"head\":{},\"boolean\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    return server;
  }
}
