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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.jena.query.QueryExecution;

/**
 * Execution plumbing shared by {@link HttpQueryExecutor} and {@link LocalQueryExecutor}:
 * cancellation racing, the query-kind result dispatch, and option defaulting. The executors keep
 * only what genuinely differs between them — how the {@link QueryExecution} is built and how its
 * failures are classified.
 *
 * <p><b>Cancellation.</b> Jena's {@code abort()} support varies by execution type ({@code
 * QueryExecution.abort()} interrupts an in-flight HTTP query, {@code UpdateExecution.abort()} does
 * not — see {@link HttpQueryExecutor}), so the blocking Jena call always runs on a background task
 * ({@link #runCancellable}) raced against a {@link CancellationWatchdog}-driven signal: whichever
 * finishes first — the real result, or the cancellation signal — determines the response. {@code
 * abort()} is still called as a best-effort courtesy to unblock the background task sooner, but the
 * client-visible CANCELLED outcome never depends on it.
 */
final class ExecSupport {

  /** Default request timeout, matching {@code SchemaManager}'s remote-fetch timeout. */
  static final int DEFAULT_TIMEOUT_MS = 30_000;

  /** Default cap on returned rows/triples before the result is marked truncated. */
  static final int DEFAULT_MAX_ROWS = 10_000;

  private ExecSupport() {}

  /**
   * Runs {@code work} on {@code ctx.executionPool()} and races it against cancellation of {@code
   * ctx.cancelChecker()}, returning whichever resolves first. {@code bestEffortAbort} is invoked
   * (on the watchdog thread) the moment cancellation is observed, as a courtesy to unblock {@code
   * work} sooner where the underlying Jena execution supports it — see the class-level note on why
   * this is not relied upon for correctness.
   */
  static ExecuteResponse runCancellable(
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

  /**
   * Executes a SELECT/ASK/CONSTRUCT/DESCRIBE on an already-built {@link QueryExecution} and
   * serializes the result into a success response. Exceptions propagate — each executor classifies
   * its own failure modes ({@link HttpQueryExecutor}: HTTP status codes; {@link
   * LocalQueryExecutor}: timeouts of the in-process engine).
   */
  static ExecuteResponse successFor(
      QueryExecution qe, QueryKind kind, String resolvedTarget, int maxRows) {
    long start = System.currentTimeMillis();
    return switch (kind) {
      case SELECT -> {
        ResultSerializer.Serialized s = ResultSerializer.selectToJson(qe.execSelect(), maxRows);
        yield ExecuteResponse.successJson(
            kind, s.payload(), stats(start, s.count(), s.truncated(), resolvedTarget));
      }
      case ASK -> {
        String json = ResultSerializer.askToJson(qe.execAsk());
        yield ExecuteResponse.successJson(kind, json, stats(start, null, false, resolvedTarget));
      }
      case CONSTRUCT -> {
        ResultSerializer.Serialized s =
            ResultSerializer.constructToTurtle(qe.execConstructTriples(), maxRows);
        yield ExecuteResponse.successTurtle(
            kind, s.payload(), stats(start, s.count(), s.truncated(), resolvedTarget));
      }
      case DESCRIBE -> {
        ResultSerializer.Serialized s =
            ResultSerializer.constructToTurtle(qe.execDescribeTriples(), maxRows);
        yield ExecuteResponse.successTurtle(
            kind, s.payload(), stats(start, s.count(), s.truncated(), resolvedTarget));
      }
      case UPDATE ->
          throw new IllegalStateException("UPDATE must be routed through executeUpdate(...)");
    };
  }

  static ExecStats stats(long startMs, Integer rowCount, boolean truncated, String resolvedTarget) {
    return new ExecStats(System.currentTimeMillis() - startMs, rowCount, truncated, resolvedTarget);
  }

  static long timeoutMs(ExecuteOptions options) {
    return options != null && options.timeoutMs() != null
        ? options.timeoutMs()
        : DEFAULT_TIMEOUT_MS;
  }

  static int maxRows(ExecuteOptions options) {
    return options != null && options.maxRows() != null ? options.maxRows() : DEFAULT_MAX_ROWS;
  }

  static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
