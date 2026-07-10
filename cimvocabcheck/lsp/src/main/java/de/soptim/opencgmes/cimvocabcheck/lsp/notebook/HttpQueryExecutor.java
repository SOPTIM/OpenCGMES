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

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.apache.jena.update.UpdateExecution;
import org.apache.jena.update.UpdateRequest;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a parsed SPARQL query or update against an HTTP endpoint, applying the request timeout,
 * result truncation, and client-cancellation wiring.
 *
 * <p><b>Cancellation.</b> Jena's {@code QueryExecution.abort()} reliably interrupts an in-flight
 * HTTP query — verified empirically against Jena 6.1.0 — but the symmetric {@code
 * UpdateExecution.abort()} does not: an in-flight {@code UpdateExecutionHTTP.execute()} call keeps
 * blocking until the request's own timeout elapses regardless of {@code abort()} being called from
 * another thread. Rather than rely on that asymmetric, version-specific behavior, the blocking Jena
 * call always runs on a background task ({@link #runCancellable}) raced against a {@link
 * CancellationWatchdog}-driven signal: whichever finishes first — the real result, or the
 * cancellation signal — determines the response. {@code abort()} is still called as a best-effort
 * courtesy (it does help for queries, and costs nothing when it does not), but correctness of the
 * client-visible CANCELLED outcome never depends on it. One consequence: a cancelled UPDATE's HTTP
 * request may continue running against the endpoint in the background until it completes or times
 * out; the notebook simply stops waiting for it.
 */
final class HttpQueryExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(HttpQueryExecutor.class);

  /** Default request timeout, matching {@code SchemaManager}'s remote-fetch timeout. */
  static final int DEFAULT_TIMEOUT_MS = 30_000;

  /** Default cap on returned rows/triples before the result is marked truncated. */
  static final int DEFAULT_MAX_ROWS = 10_000;

  private HttpQueryExecutor() {}

  /**
   * Resources a single execution needs for cancellation wiring, bundled to keep call sites short.
   */
  record ExecContext(
      ExecutorService executionPool,
      ScheduledExecutorService watchdogScheduler,
      CancelChecker cancelChecker) {}

  /**
   * Executes a SELECT/ASK/CONSTRUCT/DESCRIBE query, returning a populated {@link ExecuteResponse}.
   */
  static ExecuteResponse executeQuery(
      Query query, QueryKind kind, ExecuteTarget target, ExecuteOptions options, ExecContext ctx) {
    ExecError targetError = checkTarget(target);
    if (targetError != null) {
      return ExecuteResponse.failed(targetError);
    }

    String url = target.url();
    int maxRows = maxRows(options);
    QueryExecution qe =
        QueryExecutionHTTP.service(url)
            .query(query)
            .timeout(timeoutMs(options), TimeUnit.MILLISECONDS)
            .build();
    return runCancellable(
        ctx,
        qe::abort,
        () -> {
          try (qe) {
            return runQuery(qe, kind, url, maxRows);
          }
        });
  }

  /** Executes a SPARQL Update request, returning a populated {@link ExecuteResponse}. */
  static ExecuteResponse executeUpdate(
      UpdateRequest update, ExecuteTarget target, ExecuteOptions options, ExecContext ctx) {
    ExecError targetError = checkTarget(target);
    if (targetError != null) {
      return ExecuteResponse.failed(targetError);
    }
    String updateUrl = target.updateUrl();
    if (isBlank(updateUrl)) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.UPDATE_NOT_ALLOWED,
              "No update endpoint configured for this target.",
              null,
              null,
              null));
    }

    UpdateExecution ue =
        UpdateExecutionHTTP.service(updateUrl)
            .update(update)
            .timeout(timeoutMs(options), TimeUnit.MILLISECONDS)
            .build();
    return runCancellable(ctx, ue::abort, () -> runUpdate(ue, updateUrl));
  }

  // ---- query/update execution (runs on ctx.executionPool()) -------------------------------

  private static ExecuteResponse runQuery(
      QueryExecution qe, QueryKind kind, String url, int maxRows) {
    long start = System.currentTimeMillis();
    try {
      return switch (kind) {
        case SELECT -> {
          ResultSerializer.Serialized s = ResultSerializer.selectToJson(qe.execSelect(), maxRows);
          yield ExecuteResponse.successJson(
              kind, s.payload(), stats(start, s.count(), s.truncated(), url));
        }
        case ASK -> {
          String json = ResultSerializer.askToJson(qe.execAsk());
          yield ExecuteResponse.successJson(kind, json, stats(start, null, false, url));
        }
        case CONSTRUCT -> {
          ResultSerializer.Serialized s =
              ResultSerializer.constructToTurtle(qe.execConstructTriples(), maxRows);
          yield ExecuteResponse.successTurtle(
              kind, s.payload(), stats(start, s.count(), s.truncated(), url));
        }
        case DESCRIBE -> {
          ResultSerializer.Serialized s =
              ResultSerializer.constructToTurtle(qe.execDescribeTriples(), maxRows);
          yield ExecuteResponse.successTurtle(
              kind, s.payload(), stats(start, s.count(), s.truncated(), url));
        }
        case UPDATE ->
            throw new IllegalStateException("UPDATE must be routed through executeUpdate(...)");
      };
    } catch (QueryExceptionHTTP e) {
      return ExecuteResponse.failed(classify(e.getStatusCode(), e.getMessage(), e));
    } catch (CancellationException e) {
      // Expected fallout of runCancellable's best-effort qe.abort(): the cancelFuture race has
      // already been won by the time this unwinds, so the returned value is discarded, but avoid
      // logging a normal user-initiated cancellation as an unexpected internal error.
      LOG.debug("{} query aborted after cancellation", kind);
      return ExecuteResponse.cancelled();
    } catch (RuntimeException e) {
      LOG.error("Unexpected error executing {} query: {}", kind, e.getMessage(), e);
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.INTERNAL, "Internal error: " + e.getMessage(), null, null, null));
    }
  }

  private static ExecuteResponse runUpdate(UpdateExecution ue, String updateUrl) {
    long start = System.currentTimeMillis();
    try {
      ue.execute();
      return ExecuteResponse.successUpdate(stats(start, null, false, updateUrl));
    } catch (HttpException e) {
      return ExecuteResponse.failed(classify(e.getStatusCode(), e.getMessage(), e));
    } catch (CancellationException e) {
      // See the matching catch in runQuery: expected fallout of a best-effort abort() racing
      // against (and losing to) cancelFuture, not a genuine internal error.
      LOG.debug("Update aborted after cancellation");
      return ExecuteResponse.cancelled();
    } catch (RuntimeException e) {
      LOG.error("Unexpected error executing update: {}", e.getMessage(), e);
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.INTERNAL, "Internal error: " + e.getMessage(), null, null, null));
    }
  }

  // ---- cancellation racing -----------------------------------------------------------------

  /**
   * Runs {@code work} on {@code ctx.executionPool()} and races it against cancellation of {@code
   * ctx.cancelChecker()}, returning whichever resolves first. {@code bestEffortAbort} is invoked
   * (on the watchdog thread) the moment cancellation is observed, as a courtesy to unblock {@code
   * work} sooner where the underlying Jena execution supports it — see the class-level note on why
   * this is not relied upon for correctness.
   */
  private static ExecuteResponse runCancellable(
      ExecContext ctx, Runnable bestEffortAbort, Supplier<ExecuteResponse> work) {
    CompletableFuture<ExecuteResponse> workFuture =
        CompletableFuture.supplyAsync(work, ctx.executionPool());
    CompletableFuture<ExecuteResponse> cancelFuture = new CompletableFuture<>();

    ScheduledFuture<?> watchdogTask =
        CancellationWatchdog.watch(
            ctx.watchdogScheduler(),
            ctx.cancelChecker(),
            () -> {
              // Complete cancelFuture before aborting: aborting can itself unblock the blocked
              // Jena call on ctx.executionPool(), which would otherwise race workFuture's own
              // completion against cancelFuture below. Signalling cancellation first guarantees
              // workFuture cannot win that race as a side effect of the abort it triggers.
              cancelFuture.complete(ExecuteResponse.cancelled());
              bestEffortAbort.run();
            });
    try {
      return workFuture.applyToEither(cancelFuture, Function.identity()).join();
    } finally {
      watchdogTask.cancel(true);
    }
  }

  // ---- error classification ----------------------------------------------------------------

  private static ExecError classify(int statusCode, String message, Throwable causeChain) {
    if (hasCause(causeChain, HttpTimeoutException.class)) {
      return new ExecError(ErrorCode.TIMEOUT, "Request timed out.", message, null, null);
    }
    if (statusCode == 401 || statusCode == 403) {
      return new ExecError(
          ErrorCode.AUTH_FAILED,
          "Authentication failed (HTTP " + statusCode + ").",
          message,
          null,
          null);
    }
    return new ExecError(ErrorCode.HTTP_ERROR, message, null, null, null);
  }

  private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (type.isInstance(cur)) {
        return true;
      }
    }
    return false;
  }

  // ---- small helpers ------------------------------------------------------------------------

  private static ExecError checkTarget(ExecuteTarget target) {
    if (target == null || isBlank(target.url())) {
      return new ExecError(
          ErrorCode.NO_TARGET,
          "No endpoint configured. Add a `# [endpoint=<url>]` directive to the cell.",
          null,
          null,
          null);
    }
    return null;
  }

  private static ExecStats stats(
      long startMs, Integer rowCount, boolean truncated, String resolvedTarget) {
    return new ExecStats(System.currentTimeMillis() - startMs, rowCount, truncated, resolvedTarget);
  }

  private static long timeoutMs(ExecuteOptions options) {
    return options != null && options.timeoutMs() != null
        ? options.timeoutMs()
        : DEFAULT_TIMEOUT_MS;
  }

  private static int maxRows(ExecuteOptions options) {
    return options != null && options.maxRows() != null ? options.maxRows() : DEFAULT_MAX_ROWS;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
