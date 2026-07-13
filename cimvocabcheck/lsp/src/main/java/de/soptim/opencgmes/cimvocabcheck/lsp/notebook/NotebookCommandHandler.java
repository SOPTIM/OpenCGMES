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
 * Handles the {@value #CMD_EXECUTE} workspace command: runs a notebook cell — a SPARQL query or
 * update, or a SHACL shapes cell ({@link ShaclExecutor}) — against the target resolved by the
 * client, an HTTP endpoint ({@link HttpQueryExecutor}) or local RDF/CIMXML files ({@link
 * LocalQueryExecutor}), and returns an {@link ExecuteResponse}.
 *
 * <p>Wired into {@code SparqlWorkspaceService#executeCommand} by {@code SparqlLanguageServer},
 * which also forwards {@link #shutdown()} from its own shutdown sequence.
 */
public final class NotebookCommandHandler {

  private static final Logger LOG = LoggerFactory.getLogger(NotebookCommandHandler.class);
  private static final Gson GSON = new Gson();

  /** Command id for cell execution (see {@link #executeCommand}). */
  public static final String CMD_EXECUTE = "cimvocabcheck.notebook.execute";

  /** Command id for listing a notebook's configured connections (see {@link #listConnections}). */
  public static final String CMD_LIST_CONNECTIONS = "cimvocabcheck.notebook.listConnections";

  /**
   * Pool the blocking Jena call runs on. Must stay unbounded (cached, not fixed): the executors'
   * {@link ExecSupport#runCancellable} racing submits its own nested task to this same pool and
   * joins on it from the calling thread, so a fixed-size pool could deadlock once all threads are
   * blocked waiting on a nested task that has no thread left to run on.
   */
  private final ExecutorService executionPool =
      Executors.newCachedThreadPool(threadFactory("cimvocabcheck-notebook-exec"));

  private final ScheduledExecutorService watchdogScheduler =
      Executors.newSingleThreadScheduledExecutor(threadFactory("cimvocabcheck-notebook-watchdog"));

  private final LocalStoreManager localStores = new LocalStoreManager();

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

  /**
   * Handles one {@value #CMD_LIST_CONNECTIONS} invocation: answers the {@code "cimnotebook"} config
   * that applies to the notebook named by the single argument's {@code notebookUri} field
   * (nearest-config discovery; empty result when none applies). Never returns secrets — the config
   * only ever declares an {@code authType}.
   */
  public CompletableFuture<Object> listConnections(List<Object> arguments) {
    JsonElement el = CommandArguments.firstAsJson(arguments);
    final String uri =
        el != null && el.isJsonObject() && el.getAsJsonObject().has("notebookUri")
            ? el.getAsJsonObject().get("notebookUri").getAsString()
            : null;
    return CompletableFuture.supplyAsync(
        () -> {
          NotebookConfigLoader.Located located = NotebookConfigLoader.forNotebook(uri);
          return new ListConnectionsResponse(
              located.configPath() != null ? located.configPath().toString() : null,
              located.config().connections());
        },
        executionPool);
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

  private Object doExecute(ExecuteRequest rawRequest, CancelChecker cancelChecker) {
    LOG.debug("Executing {} cell {}", rawRequest.languageId(), rawRequest.cellUri());
    try {
      ExecuteRequest request = withConfigDefaults(rawRequest);
      var ctx = new ExecContext(executionPool, watchdogScheduler, cancelChecker);
      if ("shacl".equalsIgnoreCase(request.languageId())) {
        // SHACL cells are Turtle shapes, not SPARQL — never route them through the query parser.
        return ShaclExecutor.execute(request, localStores, ctx);
      }
      boolean filesTarget =
          request.target() != null && ExecuteTarget.TYPE_FILES.equals(request.target().type());
      return switch (QueryKindDetector.detect(request.text())) {
        case QueryKindDetector.AsQuery(var kind, var query) ->
            filesTarget
                ? LocalQueryExecutor.executeQuery(query, kind, request, localStores, ctx)
                : HttpQueryExecutor.executeQuery(
                    query, kind, request.target(), request.options(), ctx);
        case QueryKindDetector.AsUpdate(var update) ->
            filesTarget
                ? ExecuteResponse.failed(
                    new ExecError(
                        ErrorCode.UPDATE_NOT_ALLOWED,
                        "Local files are read-only — running a SPARQL Update needs an HTTP"
                            + " endpoint.",
                        null,
                        null,
                        null))
                : HttpQueryExecutor.executeUpdate(update, request.target(), request.options(), ctx);
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
   * Fills unset execution options from the notebook's {@code "cimnotebook"} config
   * (queryTimeoutSeconds/maxRows), so workspace-wide defaults apply without the client having to
   * read the config itself. Explicit per-request options always win.
   */
  private static ExecuteRequest withConfigDefaults(ExecuteRequest request) {
    ExecuteOptions options = request.options();
    boolean needsTimeout = options == null || options.timeoutMs() == null;
    boolean needsMaxRows = options == null || options.maxRows() == null;
    if (!needsTimeout && !needsMaxRows) {
      return request;
    }
    NotebookConfig config = NotebookConfigLoader.forNotebook(request.notebookUri()).config();
    if (config.queryTimeoutSeconds() == null && config.maxRows() == null) {
      return request;
    }
    Integer timeoutMs =
        !needsTimeout
            ? options.timeoutMs()
            : config.queryTimeoutSeconds() != null ? config.queryTimeoutSeconds() * 1_000 : null;
    Integer maxRows = !needsMaxRows ? options.maxRows() : config.maxRows();
    return new ExecuteRequest(
        request.cellUri(),
        request.notebookUri(),
        request.languageId(),
        request.text(),
        request.target(),
        new ExecuteOptions(timeoutMs, maxRows));
  }

  /** Extracts and deserializes the command's single argument (see {@link CommandArguments}). */
  private static ExecuteRequest parseRequest(List<Object> arguments) {
    JsonElement el = CommandArguments.firstAsJson(arguments);
    if (el == null) {
      throw new IllegalArgumentException("missing required argument");
    }
    ExecuteRequest request = GSON.fromJson(el, ExecuteRequest.class);
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
