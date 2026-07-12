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
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTPBuilder;
import org.apache.jena.update.UpdateExecution;
import org.apache.jena.update.UpdateRequest;
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
 * call is raced against the cancellation signal via {@link ExecSupport#runCancellable}. One
 * consequence: a cancelled UPDATE's HTTP request may continue running against the endpoint in the
 * background until it completes or times out; the notebook simply stops waiting for it.
 */
final class HttpQueryExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(HttpQueryExecutor.class);

  private HttpQueryExecutor() {}

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
    int maxRows = ExecSupport.maxRows(options);
    QueryExecutionHTTPBuilder builder =
        QueryExecutionHTTP.service(url)
            .query(query)
            .timeout(ExecSupport.timeoutMs(options), TimeUnit.MILLISECONDS);
    String authHeader = ExecSupport.basicAuthHeader(target.auth());
    if (authHeader != null) {
      builder.httpHeader("Authorization", authHeader);
    }
    QueryExecution qe = builder.build();
    return ExecSupport.runCancellable(
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
    if (ExecSupport.isBlank(updateUrl)) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.UPDATE_NOT_ALLOWED,
              "No update endpoint configured for this target.",
              null,
              null,
              null));
    }

    UpdateExecutionHTTPBuilder builder =
        UpdateExecutionHTTP.service(updateUrl)
            .update(update)
            .timeout(ExecSupport.timeoutMs(options), TimeUnit.MILLISECONDS);
    String authHeader = ExecSupport.basicAuthHeader(target.auth());
    if (authHeader != null) {
      builder.httpHeader("Authorization", authHeader);
    }
    UpdateExecution ue = builder.build();
    return ExecSupport.runCancellable(ctx, ue::abort, () -> runUpdate(ue, updateUrl));
  }

  // ---- query/update execution (runs on ctx.executionPool()) -------------------------------

  private static ExecuteResponse runQuery(
      QueryExecution qe, QueryKind kind, String url, int maxRows) {
    try {
      return ExecSupport.successFor(qe, kind, url, maxRows);
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
      return ExecuteResponse.successUpdate(ExecSupport.stats(start, null, false, updateUrl));
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
    if (target == null || ExecSupport.isBlank(target.url())) {
      return new ExecError(
          ErrorCode.NO_TARGET,
          "No endpoint configured. Add a `# [endpoint=<url>]` directive to the cell.",
          null,
          null,
          null);
    }
    return null;
  }
}
