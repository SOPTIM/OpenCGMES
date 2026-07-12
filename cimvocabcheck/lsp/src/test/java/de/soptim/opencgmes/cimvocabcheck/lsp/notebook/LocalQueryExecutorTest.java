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
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exercises {@link LocalQueryExecutor}: in-process query kinds over local files, relative-path
 * resolution against the notebook URI, the {@link LocalStoreManager} failure modes surfacing as
 * responses, and timeout/cancellation of the in-process engine. File parsing and union/cache
 * mechanics themselves are covered by {@link LocalStoreManagerTest}.
 */
public class LocalQueryExecutorTest {

  private static final String EX = "http://example.org/";

  private static final CancelChecker NEVER_CANCELLED =
      new CancelChecker() {
        @Override
        public void checkCanceled() {}

        @Override
        public boolean isCanceled() {
          return false;
        }
      };

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private ExecutorService executionPool;
  private ScheduledExecutorService watchdogScheduler;
  private LocalStoreManager stores;

  @Before
  public void setUp() {
    executionPool = Executors.newCachedThreadPool();
    watchdogScheduler = Executors.newSingleThreadScheduledExecutor();
    stores = new LocalStoreManager();
  }

  @After
  public void tearDown() {
    executionPool.shutdownNow();
    watchdogScheduler.shutdownNow();
  }

  // ---- happy paths -----------------------------------------------------------------------------

  @Test
  public void selectAgainstAnAbsolutePathReturnsBindings() throws IOException {
    Path file = dataFile("data.ttl", "<" + EX + "s> <" + EX + "p> \"v\" .");

    ExecuteResponse response =
        execute(
            "SELECT ?o WHERE { <" + EX + "s> <" + EX + "p> ?o }",
            QueryKind.SELECT,
            request(null, file.toString()));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.SELECT.name(), response.queryKind());
    assertNull(response.error());
    JsonArray bindings = bindings(response);
    assertEquals(1, bindings.size());
    assertEquals(
        "v", bindings.get(0).getAsJsonObject().getAsJsonObject("o").get("value").getAsString());
    assertEquals(Integer.valueOf(1), response.stats().rowCount());
    assertEquals(file.toString(), response.stats().resolvedTarget());
  }

  @Test
  public void relativePathsResolveAgainstTheNotebookDirectory() throws IOException {
    dataFile("data.ttl", "<" + EX + "s> <" + EX + "p> \"v\" .");
    String notebookUri = tmp.getRoot().toPath().resolve("demo.cimnb.md").toUri().toString();

    ExecuteResponse response =
        execute("ASK { ?s ?p ?o }", QueryKind.ASK, request(notebookUri, "./data.ttl"));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertTrue(
        JsonParser.parseString(response.resultsJson())
            .getAsJsonObject()
            .get("boolean")
            .getAsBoolean());
    assertEquals("./data.ttl", response.stats().resolvedTarget());
  }

  @Test
  public void multipleFilesAreQueriedAsOneUnion() throws IOException {
    Path a = dataFile("a.ttl", "<" + EX + "a> <" + EX + "p> 1 .");
    Path b = dataFile("b.ttl", "<" + EX + "b> <" + EX + "p> 2 .");

    ExecuteResponse response =
        execute(
            "SELECT ?s WHERE { ?s <" + EX + "p> ?o }",
            QueryKind.SELECT,
            request(null, a.toString(), b.toString()));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(2, bindings(response).size());
    assertEquals(a + ", " + b, response.stats().resolvedTarget());
  }

  @Test
  public void constructReturnsTheResultGraphAsTurtle() throws IOException {
    Path file = dataFile("data.ttl", "<" + EX + "s> <" + EX + "p> \"v\" .");

    ExecuteResponse response =
        execute(
            "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }",
            QueryKind.CONSTRUCT,
            request(null, file.toString()));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.CONSTRUCT.name(), response.queryKind());
    assertTrue(response.turtle().contains(EX + "s"));
  }

  @Test
  public void selectOverACimxmlModelSeesBodyAndHeader() throws IOException {
    Path model = dataFile("model.xml", CIMXML_FULL_MODEL);

    ExecuteResponse names =
        execute(
            "SELECT ?name WHERE { ?s <http://iec.ch/TC57/CIM100#IdentifiedObject.name> ?name }",
            QueryKind.SELECT,
            request(null, model.toString()));
    assertEquals(ExecutionStatus.SUCCESS.name(), names.status());
    assertEquals(
        "My Custom Equipment",
        bindings(names)
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("name")
            .get("value")
            .getAsString());

    ExecuteResponse header =
        execute(
            "ASK { ?m <http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile> ?p }",
            QueryKind.ASK,
            request(null, model.toString()));
    assertTrue(
        "the model header must be visible to bare patterns via the union default graph",
        JsonParser.parseString(header.resultsJson())
            .getAsJsonObject()
            .get("boolean")
            .getAsBoolean());
  }

  // ---- failure modes ---------------------------------------------------------------------------

  @Test
  public void missingFileReportsFileNotFound() {
    ExecuteResponse response =
        execute(
            "ASK {}",
            QueryKind.ASK,
            request(null, tmp.getRoot().toPath().resolve("nope.ttl").toString()));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.FILE_NOT_FOUND.name(), response.error().code());
    assertTrue(response.error().message().contains("nope.ttl"));
  }

  @Test
  public void unparseableFileReportsFileParseError() throws IOException {
    Path file = dataFile("broken.ttl", "not turtle @@@");

    ExecuteResponse response = execute("ASK {}", QueryKind.ASK, request(null, file.toString()));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.FILE_PARSE_ERROR.name(), response.error().code());
  }

  @Test
  public void relativePathWithoutANotebookLocationReportsFileNotFound() {
    ExecuteResponse response = execute("ASK {}", QueryKind.ASK, request(null, "./data.ttl"));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.FILE_NOT_FOUND.name(), response.error().code());
    assertTrue(response.error().message().contains("save the notebook"));
  }

  @Test
  public void emptyFileListReportsNoTarget() {
    ExecuteResponse response = execute("ASK {}", QueryKind.ASK, request(null));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
  }

  // ---- timeout and cancellation ----------------------------------------------------------------

  @Test
  public void slowQueryReportsTimeout() throws IOException {
    ExecuteResponse response =
        execute(PATHOLOGICAL_QUERY, QueryKind.SELECT, slowRequest(50), NEVER_CANCELLED);

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.TIMEOUT.name(), response.error().code());
  }

  @Test
  public void cancellationWinsOverASlowQuery() throws IOException {
    long start = System.nanoTime();
    ExecuteResponse response =
        execute(PATHOLOGICAL_QUERY, QueryKind.SELECT, slowRequest(60_000), cancelAfter(300));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertEquals(ExecutionStatus.CANCELLED.name(), response.status());
    assertEquals(ErrorCode.CANCELLED.name(), response.error().code());
    assertTrue(
        "expected cancellation well before the timeout, took " + elapsedMs + "ms",
        elapsedMs < 30_000);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * A COUNT over an unrestricted five-way cross join (120⁵ ≈ 2.5 × 10¹⁰ combinations on the fixture
   * data): the aggregation forces the whole join to be exhausted before the first row can be
   * emitted, so — unlike a plain SELECT, which streams rows immediately and would finish within the
   * row cap — this reliably still runs when the timeout or cancellation fires.
   */
  private static final String PATHOLOGICAL_QUERY =
      "SELECT (COUNT(*) AS ?n) WHERE { ?a ?p1 ?o1 . ?b ?p2 ?o2 . ?c ?p3 ?o3 . ?d ?p4 ?o4 ."
          + " ?e ?p5 ?o5 }";

  /** From the CIMXML FullModel structure exercised in the cimxml module's own tests. */
  private static final String CIMXML_FULL_MODEL =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
       <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
         <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
       </md:FullModel>
       <cim:MyEquipment rdf:ID="_f67fc354-9e39-4191-a456-67537399bc48">
         <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
       </cim:MyEquipment>
      </rdf:RDF>
      """;

  private ExecuteRequest slowRequest(int timeoutMs) throws IOException {
    StringBuilder turtle = new StringBuilder();
    for (int i = 0; i < 120; i++) {
      turtle
          .append('<')
          .append(EX)
          .append('s')
          .append(i)
          .append("> <")
          .append(EX)
          .append("p> ")
          .append(i)
          .append(" .\n");
    }
    Path file = dataFile("big.ttl", turtle.toString());
    return new ExecuteRequest(
        "file:///cell1.sparql",
        null,
        "sparql",
        PATHOLOGICAL_QUERY,
        filesTarget(file.toString()),
        new ExecuteOptions(timeoutMs, null));
  }

  private ExecuteResponse execute(String queryText, QueryKind kind, ExecuteRequest request) {
    return execute(queryText, kind, request, NEVER_CANCELLED);
  }

  private ExecuteResponse execute(
      String queryText, QueryKind kind, ExecuteRequest request, CancelChecker checker) {
    Query query = QueryFactory.create(queryText);
    return LocalQueryExecutor.executeQuery(
        query, kind, request, stores, new ExecContext(executionPool, watchdogScheduler, checker));
  }

  private static ExecuteRequest request(String notebookUri, String... files) {
    return new ExecuteRequest(
        "file:///cell1.sparql", notebookUri, "sparql", "unused", filesTarget(files), null);
  }

  private static ExecuteTarget filesTarget(String... files) {
    return new ExecuteTarget(ExecuteTarget.TYPE_FILES, null, null, null, List.of(files));
  }

  private Path dataFile(String name, String content) throws IOException {
    Path file = tmp.getRoot().toPath().resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private static JsonArray bindings(ExecuteResponse response) {
    return JsonParser.parseString(response.resultsJson())
        .getAsJsonObject()
        .getAsJsonObject("results")
        .getAsJsonArray("bindings");
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
}
