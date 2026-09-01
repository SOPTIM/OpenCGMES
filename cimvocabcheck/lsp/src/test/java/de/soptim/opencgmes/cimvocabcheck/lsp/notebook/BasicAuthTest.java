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
import static org.junit.Assert.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.update.UpdateFactory;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the preemptive basic-auth behavior across all three HTTP paths (query, update, SHACL): the
 * exact {@code Authorization} header the endpoint receives, and its absence for anonymous targets.
 * Uses a header-capturing JDK HttpServer rather than a secured Fuseki — what matters is the request
 * we send, not any particular server's challenge dance.
 */
public class BasicAuthTest {

  /** {@code Basic base64("alice:secret")}. */
  private static final String EXPECTED_HEADER = "Basic YWxpY2U6c2VjcmV0";

  private static final ExecAuth AUTH = new ExecAuth("basic", "alice", "secret");

  private static final CancelChecker NEVER_CANCELLED =
      new CancelChecker() {
        @Override
        public void checkCanceled() {}

        @Override
        public boolean isCanceled() {
          return false;
        }
      };

  private ExecutorService executionPool;
  private ScheduledExecutorService watchdogScheduler;
  private HttpServer server;
  private final AtomicReference<String> seenAuthorization = new AtomicReference<>();

  @Before
  public void setUp() throws IOException {
    executionPool = Executors.newCachedThreadPool();
    watchdogScheduler = Executors.newSingleThreadScheduledExecutor();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body;
          String contentType;
          if (exchange.getRequestURI().getPath().contains("shacl")) {
            body =
                """
                PREFIX sh: <http://www.w3.org/ns/shacl#>
                [] a sh:ValidationReport ; sh:conforms true .
                """
                    .getBytes(StandardCharsets.UTF_8);
            contentType = "text/turtle";
          } else {
            body = "{\"head\":{},\"boolean\":true}".getBytes(StandardCharsets.UTF_8);
            contentType = "application/sparql-results+json";
          }
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
  }

  @After
  public void tearDown() {
    server.stop(0);
    executionPool.shutdownNow();
    watchdogScheduler.shutdownNow();
  }

  @Test
  public void queryCarriesThePreemptiveBasicAuthHeader() {
    ExecuteResponse response =
        HttpQueryExecutor.executeQuery(
            QueryFactory.create("ASK {}"), QueryKind.ASK, target(url("/query"), AUTH), null, ctx());

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(EXPECTED_HEADER, seenAuthorization.get());
  }

  @Test
  public void queryWithoutCredentialsSendsNoAuthorizationHeader() {
    HttpQueryExecutor.executeQuery(
        QueryFactory.create("ASK {}"), QueryKind.ASK, target(url("/query"), null), null, ctx());

    assertNull(seenAuthorization.get());
  }

  @Test
  public void updateCarriesThePreemptiveBasicAuthHeader() {
    HttpQueryExecutor.executeUpdate(
        UpdateFactory.create("INSERT DATA { <urn:s> <urn:p> 1 }"),
        new ExecuteTarget(ExecuteTarget.TYPE_HTTP, url("/query"), url("/update"), null, null, AUTH),
        null,
        ctx());

    assertEquals(EXPECTED_HEADER, seenAuthorization.get());
  }

  @Test
  public void shaclValidationCarriesThePreemptiveBasicAuthHeader() {
    ExecuteResponse response =
        HttpShaclClient.validate(
            "PREFIX sh: <http://www.w3.org/ns/shacl#>",
            new ExecuteTarget(
                ExecuteTarget.TYPE_HTTP,
                url("/query"),
                null,
                url("/shacl?graph=default"),
                null,
                AUTH),
            null,
            ctx());

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(EXPECTED_HEADER, seenAuthorization.get());
  }

  @Test
  public void basicAuthHeaderIsNullForUnusableCredentials() {
    assertNull(ExecSupport.basicAuthHeader(null));
    assertNull(ExecSupport.basicAuthHeader(new ExecAuth("bearer", "alice", "secret")));
    assertNull(ExecSupport.basicAuthHeader(new ExecAuth("basic", null, "secret")));
    assertEquals(
        "an empty password must still authenticate as user:",
        "Basic YWxpY2U6",
        ExecSupport.basicAuthHeader(new ExecAuth("basic", "alice", null)));
  }

  @Test
  public void toStringNeverLeaksCredentials() {
    String s = AUTH.toString();
    assertEquals(-1, s.indexOf("alice"));
    assertEquals(-1, s.indexOf("secret"));
  }

  // ---- helpers -------------------------------------------------------------------------------

  private String url(String pathAndQuery) {
    return "http://localhost:" + server.getAddress().getPort() + pathAndQuery;
  }

  private static ExecuteTarget target(String url, ExecAuth auth) {
    return new ExecuteTarget(ExecuteTarget.TYPE_HTTP, url, null, null, null, auth);
  }

  private ExecContext ctx() {
    return new ExecContext(executionPool, watchdogScheduler, NEVER_CANCELLED);
  }
}
