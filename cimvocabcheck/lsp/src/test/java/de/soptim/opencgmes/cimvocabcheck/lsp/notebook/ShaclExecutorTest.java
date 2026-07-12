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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.server.Operation;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.system.Txn;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exercises {@link ShaclExecutor} (and, through it, {@link HttpShaclClient}): local jena-shacl
 * validation over file targets, remote validation against a real in-process Fuseki SHACL operation,
 * shapes parsing failures, and the timeout/cancellation behavior of the racing variant that
 * in-process validation relies on.
 */
public class ShaclExecutorTest {

  private static final String EX = "http://example.org/";

  private static final String SHAPES_NAME_REQUIRED =
      """
      PREFIX sh: <http://www.w3.org/ns/shacl#>
      PREFIX ex: <http://example.org/>
      ex:PersonShape a sh:NodeShape ;
        sh:targetClass ex:Person ;
        sh:property [ sh:path ex:name ; sh:minCount 1 ] .
      """;

  private static final String DATA_CONFORMING =
      """
      PREFIX ex: <http://example.org/>
      ex:alice a ex:Person ; ex:name "Alice" .
      """;

  private static final String DATA_VIOLATING =
      """
      PREFIX ex: <http://example.org/>
      ex:bob a ex:Person .
      """;

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
  private static String shaclUrl;

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private ExecutorService executionPool;
  private ScheduledExecutorService watchdogScheduler;
  private LocalStoreManager stores;

  @BeforeClass
  public static void startFuseki() {
    DatasetGraph dsg = DatasetGraphFactory.createTxnMem();
    Txn.executeWrite(
        dsg,
        () ->
            dsg.getDefaultGraph()
                .add(
                    Triple.create(
                        NodeFactory.createURI(EX + "bob"),
                        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                        NodeFactory.createURI(EX + "Person"))));
    fuseki =
        FusekiServer.create()
            .port(0)
            .add("/ds", dsg)
            .addEndpoint("/ds", "shacl", Operation.Shacl)
            .build()
            .start();
    // Fuseki's SHACL operation requires the graph selector; "default" = the default graph. The
    // TS client's deriveShaclUrl appends exactly this when the directive has no query string.
    shaclUrl = fuseki.serverURL() + "ds/shacl?graph=default";
  }

  @AfterClass
  public static void stopFuseki() {
    fuseki.stop();
  }

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

  // ---- local validation --------------------------------------------------------------------

  @Test
  public void conformingDataReportsConforms() throws IOException {
    Path data = dataFile("data.ttl", DATA_CONFORMING);

    ExecuteResponse response = executeLocal(SHAPES_NAME_REQUIRED, data.toString());

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.SHACL.name(), response.queryKind());
    ShaclSummary summary = response.shaclSummary();
    assertNotNull(summary);
    assertTrue(summary.conforms());
    assertEquals(0, summary.violations());
    assertTrue("the report itself travels as turtle", response.turtle().contains("conforms"));
    assertEquals(data.toString(), response.stats().resolvedTarget());
  }

  @Test
  public void violationsAreCountedBySeverity() throws IOException {
    Path data = dataFile("data.ttl", DATA_VIOLATING);
    String shapes =
        """
        PREFIX sh: <http://www.w3.org/ns/shacl#>
        PREFIX ex: <http://example.org/>
        ex:NameShape a sh:NodeShape ;
          sh:targetClass ex:Person ;
          sh:property [ sh:path ex:name ; sh:minCount 1 ] .
        ex:AgeShape a sh:NodeShape ;
          sh:targetClass ex:Person ;
          sh:property [ sh:path ex:age ; sh:minCount 1 ; sh:severity sh:Warning ] .
        """;

    ExecuteResponse response = executeLocal(shapes, data.toString());

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    ShaclSummary summary = response.shaclSummary();
    assertFalse(summary.conforms());
    assertEquals(1, summary.violations());
    assertEquals(1, summary.warnings());
    assertEquals(0, summary.infos());
    assertEquals(Integer.valueOf(2), response.stats().rowCount());
    assertTrue(response.turtle().contains("ValidationResult"));
  }

  @Test
  public void validationSpansTheUnionOfAllTargetFiles() throws IOException {
    Path good = dataFile("good.ttl", DATA_CONFORMING);
    Path bad = dataFile("bad.ttl", DATA_VIOLATING);

    ExecuteResponse response = executeLocal(SHAPES_NAME_REQUIRED, good.toString(), bad.toString());

    ShaclSummary summary = response.shaclSummary();
    assertFalse("the violating file must be seen through the union", summary.conforms());
    assertEquals(1, summary.violations());
  }

  @Test
  public void invalidTurtleShapesReportParseErrorWithPosition() throws IOException {
    Path data = dataFile("data.ttl", DATA_CONFORMING);

    ExecuteResponse response = executeLocal("this is not turtle @@@", data.toString());

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.PARSE_ERROR.name(), response.error().code());
    assertNotNull("riot reports a line for syntax errors", response.error().line());
  }

  @Test
  public void wellFormedTurtleButBrokenShapesReportParseError() throws IOException {
    Path data = dataFile("data.ttl", DATA_CONFORMING);
    // Valid Turtle, invalid SHACL: a property shape without sh:path.
    String shapes =
        """
        PREFIX sh: <http://www.w3.org/ns/shacl#>
        PREFIX ex: <http://example.org/>
        ex:Broken a sh:NodeShape ;
          sh:targetClass ex:Person ;
          sh:property [ sh:minCount 1 ] .
        """;

    ExecuteResponse response = executeLocal(shapes, data.toString());

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.PARSE_ERROR.name(), response.error().code());
    assertTrue(response.error().message().contains("SHACL"));
  }

  @Test
  public void missingDataFileReportsFileNotFound() {
    ExecuteResponse response =
        executeLocal(SHAPES_NAME_REQUIRED, tmp.getRoot().toPath().resolve("nope.ttl").toString());

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.FILE_NOT_FOUND.name(), response.error().code());
  }

  @Test
  public void missingTargetReportsNoTarget() {
    ExecuteResponse response =
        ShaclExecutor.execute(request(SHAPES_NAME_REQUIRED, null), stores, ctx(NEVER_CANCELLED));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
  }

  // ---- timeout / cancellation of the racing variant used for in-process validation ----------

  @Test
  public void timeoutOverloadResolvesToTimeoutWhileWorkIsStillRunning() {
    ExecuteResponse response =
        ExecSupport.runCancellable(
            ctx(NEVER_CANCELLED), () -> {}, ShaclExecutorTest::sleepForever, 100);

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.TIMEOUT.name(), response.error().code());
  }

  @Test
  public void cancellationWinsOverBlockedWork() {
    long start = System.nanoTime();
    ExecuteResponse response =
        ExecSupport.runCancellable(
            ctx(cancelAfter(200)), () -> {}, ShaclExecutorTest::sleepForever, 30_000);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertEquals(ExecutionStatus.CANCELLED.name(), response.status());
    assertTrue(
        "expected cancellation to resolve quickly, took " + elapsedMs + "ms", elapsedMs < 10_000);
  }

  // ---- remote validation (real Fuseki SHACL operation) --------------------------------------

  @Test
  public void remoteValidationReportsViolationsFromTheService() {
    ExecuteResponse response = executeRemote(SHAPES_NAME_REQUIRED, shaclUrl);

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.SHACL.name(), response.queryKind());
    ShaclSummary summary = response.shaclSummary();
    assertNotNull(summary);
    assertFalse("bob has no name, the service must report it", summary.conforms());
    assertTrue(summary.violations() >= 1);
    assertTrue(response.turtle().contains("ValidationResult"));
    assertEquals(shaclUrl, response.stats().resolvedTarget());
  }

  @Test
  public void remoteValidationCanConform() {
    // Shapes that the server's data satisfies: bob is typed, nothing more required.
    String shapes =
        """
        PREFIX sh: <http://www.w3.org/ns/shacl#>
        PREFIX ex: <http://example.org/>
        ex:TypedShape a sh:NodeShape ;
          sh:targetClass ex:Person ;
          sh:property [ sh:path <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ;
                        sh:minCount 1 ] .
        """;

    ExecuteResponse response = executeRemote(shapes, shaclUrl);

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertTrue(response.shaclSummary().conforms());
  }

  @Test
  public void remoteErrorsSurfaceAsHttpError() {
    ExecuteResponse response = executeRemote(SHAPES_NAME_REQUIRED, fuseki.serverURL() + "ds/query");

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.HTTP_ERROR.name(), response.error().code());
  }

  @Test
  public void httpTargetWithoutShaclServiceReportsNoTarget() {
    ExecuteResponse response = executeRemote(SHAPES_NAME_REQUIRED, null);

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
    assertTrue(response.error().message().contains("SHACL"));
  }

  // ---- helpers -------------------------------------------------------------------------------

  private ExecuteResponse executeLocal(String shapes, String... files) {
    ExecuteTarget target =
        new ExecuteTarget(ExecuteTarget.TYPE_FILES, null, null, null, List.of(files));
    return ShaclExecutor.execute(request(shapes, target), stores, ctx(NEVER_CANCELLED));
  }

  private ExecuteResponse executeRemote(String shapes, String shaclServiceUrl) {
    ExecuteTarget target =
        new ExecuteTarget(
            ExecuteTarget.TYPE_HTTP, fuseki.serverURL() + "ds", null, shaclServiceUrl, null);
    return ShaclExecutor.execute(request(shapes, target), stores, ctx(NEVER_CANCELLED));
  }

  private static ExecuteRequest request(String shapes, ExecuteTarget target) {
    return new ExecuteRequest("file:///cell1.shacl", null, "shacl", shapes, target, null);
  }

  private ExecContext ctx(CancelChecker checker) {
    return new ExecContext(executionPool, watchdogScheduler, checker);
  }

  private Path dataFile(String name, String content) throws IOException {
    Path file = tmp.getRoot().toPath().resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private static ExecuteResponse sleepForever() {
    try {
      Thread.sleep(60_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return ExecuteResponse.failed(
        new ExecError(ErrorCode.INTERNAL, "work was not interrupted", null, null, null));
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
