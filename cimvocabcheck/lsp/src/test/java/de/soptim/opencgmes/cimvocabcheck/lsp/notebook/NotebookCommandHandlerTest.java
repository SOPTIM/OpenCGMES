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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Exercises {@link NotebookCommandHandler#executeCommand} end to end, covering argument parsing
 * (both the {@code JsonElement} and in-process {@code String} argument shapes handled by {@link
 * NotebookCommandHandler}) and dispatch into {@link HttpQueryExecutor}. Query-execution behavior
 * itself (timeouts, cancellation, HTTP error classification, etc.) is covered by {@link
 * HttpQueryExecutorTest}; this class only needs enough of a real endpoint to prove that a
 * successfully parsed request actually reaches it.
 */
public class NotebookCommandHandlerTest {

  private static FusekiServer fuseki;
  private static String queryUrl;
  private static String updateUrl;

  private NotebookCommandHandler handler;

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
    handler = new NotebookCommandHandler();
  }

  @After
  public void tearDown() {
    handler.shutdown();
  }

  // ---- malformed arguments (never reach HttpQueryExecutor) -----------------------------------

  @Test
  public void executeCommandFailsWithInvalidParamsWhenArgumentsIsNull() {
    assertInvalidParams(handler.executeCommand(null));
  }

  @Test
  public void executeCommandFailsWithInvalidParamsWhenArgumentsIsEmpty() {
    assertInvalidParams(handler.executeCommand(List.of()));
  }

  @Test
  public void executeCommandFailsWithInvalidParamsWhenFirstArgumentIsNull() {
    List<Object> args = new ArrayList<>();
    args.add(null);
    assertInvalidParams(handler.executeCommand(args));
  }

  @Test
  public void executeCommandFailsWithInvalidParamsWhenArgumentIsNotValidJson() {
    assertInvalidParams(handler.executeCommand(List.of("not json at all")));
  }

  @Test
  public void executeCommandFailsWithInvalidParamsWhenTextFieldIsMissing() {
    JsonObject arg = new JsonObject();
    arg.addProperty("cellUri", "file:///cell1.sparql");
    assertInvalidParams(handler.executeCommand(List.of(arg)));
  }

  // ---- successful dispatch ---------------------------------------------------------------------

  @Test
  public void executeCommandDispatchesJsonElementArgumentAndExecutesAskQuery() throws Exception {
    JsonObject arg = requestJson("ASK {}", queryUrl, updateUrl);

    ExecuteResponse response = getResponse(handler.executeCommand(List.of(arg)));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.ASK.name(), response.queryKind());
  }

  @Test
  public void executeCommandDispatchesStringArgumentAndExecutesAskQuery() throws Exception {
    // Mirrors a direct in-process call (as opposed to a JsonElement arriving over JSON-RPC): the
    // same request serialized to a plain JSON string.
    JsonObject arg = requestJson("ASK {}", queryUrl, updateUrl);

    ExecuteResponse response = getResponse(handler.executeCommand(List.of(arg.toString())));

    assertEquals(ExecutionStatus.SUCCESS.name(), response.status());
    assertEquals(QueryKind.ASK.name(), response.queryKind());
  }

  // ---- requests that parse as arguments but fail during execution --------------------------

  @Test
  public void executeCommandReturnsParseErrorResponseWhenTextIsNotValidSparql() throws Exception {
    JsonObject arg = requestJson("this is not sparql at all", null, null);

    ExecuteResponse response = getResponse(handler.executeCommand(List.of(arg)));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.PARSE_ERROR.name(), response.error().code());
  }

  @Test
  public void executeCommandReturnsNoTargetResponseWhenTargetIsMissing() throws Exception {
    JsonObject arg = requestJson("ASK {}", null, null);

    ExecuteResponse response = getResponse(handler.executeCommand(List.of(arg)));

    assertEquals(ExecutionStatus.ERROR.name(), response.status());
    assertEquals(ErrorCode.NO_TARGET.name(), response.error().code());
  }

  // ---- wire shape ----------------------------------------------------------------------------

  /**
   * Pins the JSON the VS Code client actually parses: lsp4j encodes the {@link ExecuteResponse}
   * returned from {@code workspace/executeCommand} with its message Gson, so record component names
   * become the JSON keys and enums serialize as their names. The TypeScript mirror of this contract
   * lives in {@code cimnotebook/vscode/src/notebook/endpoint.ts}.
   */
  @Test
  public void executeResponseSerializesToTheWireShapeTheClientParses() throws Exception {
    Gson wireGson = new MessageJsonHandler(Map.of()).getGson();

    JsonObject success =
        wireGson
            .toJsonTree(
                getResponse(
                    handler.executeCommand(
                        List.of(
                            requestJson(
                                "SELECT ?s WHERE { VALUES ?s { <http://example.org/x> } }",
                                queryUrl,
                                updateUrl)))))
            .getAsJsonObject();
    assertEquals("SUCCESS", success.get("status").getAsString());
    assertEquals("SELECT", success.get("queryKind").getAsString());
    assertFalse("gson must omit the null error field", success.has("error"));
    JsonObject stats = success.getAsJsonObject("stats");
    assertTrue(stats.get("durationMs").getAsLong() >= 0);
    assertEquals(queryUrl, stats.get("resolvedTarget").getAsString());
    JsonObject results =
        JsonParser.parseString(success.get("resultsJson").getAsString()).getAsJsonObject();
    JsonObject binding =
        results
            .getAsJsonObject("results")
            .getAsJsonArray("bindings")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("s");
    assertEquals("uri", binding.get("type").getAsString());
    assertEquals("http://example.org/x", binding.get("value").getAsString());

    JsonObject failure =
        wireGson
            .toJsonTree(
                getResponse(handler.executeCommand(List.of(requestJson("not sparql", null, null)))))
            .getAsJsonObject();
    assertEquals("ERROR", failure.get("status").getAsString());
    JsonObject error = failure.getAsJsonObject("error");
    assertEquals("PARSE_ERROR", error.get("code").getAsString());
    assertFalse(error.get("message").getAsString().isEmpty());
    if (error.has("line")) {
      assertTrue(error.get("line").getAsInt() >= 1);
    }
  }

  // ---- shutdown ----------------------------------------------------------------------------

  @Test
  public void shutdownIsIdempotent() {
    handler.shutdown();
    handler.shutdown();
    // tearDown() below calls shutdown() a third time; ExecutorService#shutdown() is documented to
    // have no additional effect once already shut down, so repeated calls must not throw.
  }

  // ---- helpers ------------------------------------------------------------------------------

  private static JsonObject requestJson(String text, String url, String updateUrl) {
    JsonObject arg = new JsonObject();
    arg.addProperty("cellUri", "file:///cell1.sparql");
    arg.addProperty("languageId", "sparql");
    arg.addProperty("text", text);
    if (url != null) {
      JsonObject target = new JsonObject();
      target.addProperty("type", ExecuteTarget.TYPE_HTTP);
      target.addProperty("url", url);
      if (updateUrl != null) {
        target.addProperty("updateUrl", updateUrl);
      }
      arg.add("target", target);
    }
    return arg;
  }

  private static ExecuteResponse getResponse(CompletableFuture<Object> future) throws Exception {
    return (ExecuteResponse) future.get();
  }

  private static void assertInvalidParams(CompletableFuture<Object> future) {
    try {
      future.get();
      fail("expected the future to fail with InvalidParams");
    } catch (ExecutionException e) {
      assertTrue(
          "expected a ResponseErrorException, got " + e.getCause(),
          e.getCause() instanceof ResponseErrorException);
      ResponseErrorException rex = (ResponseErrorException) e.getCause();
      assertEquals(ResponseErrorCode.InvalidParams.getValue(), rex.getResponseError().getCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted while waiting for the future");
    }
  }
}
