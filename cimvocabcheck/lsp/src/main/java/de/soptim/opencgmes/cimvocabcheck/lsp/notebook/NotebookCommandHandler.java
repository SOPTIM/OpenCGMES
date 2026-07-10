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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the {@value #CMD_EXECUTE} workspace command: runs a notebook cell's SPARQL query or
 * update against the endpoint resolved by the client, and returns an {@link ExecuteResponse}.
 *
 * <p>Wired into {@code SparqlWorkspaceService#executeCommand} by {@code SparqlLanguageServer},
 * which also forwards {@link #shutdown()} from its own shutdown sequence.
 */
public final class NotebookCommandHandler {

  private static final Logger LOG = LoggerFactory.getLogger(NotebookCommandHandler.class);
  private static final Gson GSON = new Gson();

  /** Command id for cell execution (see {@link #executeCommand}). */
  public static final String CMD_EXECUTE = "cimvocabcheck.notebook.execute";

  /**
   * Pool the blocking Jena HTTP call runs on. Must stay unbounded (cached, not fixed): {@link
   * HttpQueryExecutor#executeQuery} submits its own nested task to this same pool and joins on it
   * from the calling thread, so a fixed-size pool could deadlock once all threads are blocked
   * waiting on a nested task that has no thread left to run on.
   */
  private final ExecutorService executionPool =
      Executors.newCachedThreadPool(threadFactory("cimvocabcheck-notebook-exec"));

  private final ScheduledExecutorService watchdogScheduler =
      Executors.newSingleThreadScheduledExecutor(threadFactory("cimvocabcheck-notebook-watchdog"));

  /**
   * Handles one {@value #CMD_EXECUTE} invocation. {@code arguments} must have a single element that
   * Gson-deserializes to an {@link ExecuteRequest}; over JSON-RPC this arrives as a {@link
   * JsonElement}, while a direct in-process call may pass a JSON {@link String}.
   */
  public CompletableFuture<Object> executeCommand(List<Object> arguments) {
    ExecuteRequest request;
    try {
      request = parseRequest(arguments);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(
          new ResponseErrorException(
              new ResponseError(
                  ResponseErrorCode.InvalidParams,
                  "Malformed " + CMD_EXECUTE + " argument: " + e.getMessage(),
                  null)));
    }
    return CompletableFutures.computeAsync(
        executionPool, cancelChecker -> doExecute(request, cancelChecker));
  }

  /** Releases the thread pools. Safe to call once during server shutdown. */
  public void shutdown() {
    executionPool.shutdown();
    watchdogScheduler.shutdown();
    try {
      if (!executionPool.awaitTermination(2, TimeUnit.SECONDS)) {
        executionPool.shutdownNow();
      }
      if (!watchdogScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
        watchdogScheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      executionPool.shutdownNow();
      watchdogScheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private Object doExecute(ExecuteRequest request, CancelChecker cancelChecker) {
    LOG.debug("Executing {} cell {}", request.languageId(), request.cellUri());
    try {
      var ctx = new HttpQueryExecutor.ExecContext(executionPool, watchdogScheduler, cancelChecker);
      return switch (QueryKindDetector.detect(request.text())) {
        case QueryKindDetector.AsQuery(var kind, var query) ->
            HttpQueryExecutor.executeQuery(query, kind, request.target(), request.options(), ctx);
        case QueryKindDetector.AsUpdate(var update) ->
            HttpQueryExecutor.executeUpdate(update, request.target(), request.options(), ctx);
        case QueryKindDetector.ParseFailure(var message, var line, var column) ->
            ExecuteResponse.failed(
                new ExecError(ErrorCode.PARSE_ERROR, message, null, line, column));
      };
    } catch (RuntimeException e) {
      LOG.error("Unexpected error handling {}: {}", CMD_EXECUTE, e.getMessage(), e);
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.INTERNAL, "Internal error: " + e.getMessage(), null, null, null));
    }
  }

  /**
   * Extracts and deserializes the command's single argument. Over JSON-RPC, lsp4j delivers
   * arguments as Gson {@link JsonElement}s; a direct in-process call may pass a JSON {@link String}
   * instead — mirrors the dual-case handling in {@code SparqlWorkspaceService}.
   */
  private static ExecuteRequest parseRequest(List<Object> arguments) {
    if (arguments == null || arguments.isEmpty() || arguments.get(0) == null) {
      throw new IllegalArgumentException("missing required argument");
    }
    Object first = arguments.get(0);
    ExecuteRequest request =
        first instanceof JsonElement el
            ? GSON.fromJson(el, ExecuteRequest.class)
            : GSON.fromJson(first.toString(), ExecuteRequest.class);
    if (request == null || request.text() == null) {
      throw new IllegalArgumentException("missing required field 'text'");
    }
    return request;
  }

  private static ThreadFactory threadFactory(String namePrefix) {
    AtomicInteger threadNum = new AtomicInteger(1);
    return r -> {
      Thread t = new Thread(r, namePrefix + "-" + threadNum.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
  }
}
