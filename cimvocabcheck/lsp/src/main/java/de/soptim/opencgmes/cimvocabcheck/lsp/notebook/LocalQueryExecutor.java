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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryCancelledException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.sparql.core.DatasetGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a parsed SPARQL query in-process against local data files ({@link
 * ExecuteTarget#TYPE_FILES}): RDF files and CIMXML models, parsed and cached by {@link
 * LocalStoreManager}. Local files are read-only — SPARQL updates are rejected by {@code
 * NotebookCommandHandler} before reaching this class.
 *
 * <p>Relative paths are resolved against the directory of {@link ExecuteRequest#notebookUri()},
 * matching how validation resolves relative {@code # [endpoint=...]} directives, so the same
 * directive means the same file for both.
 *
 * <p>Cancellation uses the same racing scheme as {@link HttpQueryExecutor} (via {@link
 * ExecSupport#runCancellable}); for the in-process engine {@code QueryExecution.abort()} and the
 * timeout both surface as {@link QueryCancelledException}, told apart by whether the client
 * actually cancelled.
 */
final class LocalQueryExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(LocalQueryExecutor.class);

  private LocalQueryExecutor() {}

  /**
   * Executes a SELECT/ASK/CONSTRUCT/DESCRIBE query against the request's local files, returning a
   * populated {@link ExecuteResponse}.
   */
  static ExecuteResponse executeQuery(
      Query query,
      QueryKind kind,
      ExecuteRequest request,
      LocalStoreManager stores,
      ExecContext ctx) {
    ExecuteTarget target = request.target();
    if (target.files() == null || target.files().isEmpty()) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.NO_TARGET,
              "No endpoint configured. Add a `# [endpoint=<url or file>]` directive to the cell.",
              null,
              null,
              null));
    }

    DatasetGraph data;
    try {
      List<Path> paths = NotebookPaths.resolvePaths(target.files(), request.notebookUri());
      data = stores.unionFor(paths);
    } catch (LocalStoreManager.StoreException e) {
      return ExecuteResponse.failed(new ExecError(e.code(), e.getMessage(), null, null, null));
    }

    // The stats line shows the files as the user wrote them in the directive, not the resolved
    // absolute paths — the notebook-relative form is the meaningful one to a reader.
    String resolvedTarget = String.join(", ", target.files());
    int maxRows = ExecSupport.maxRows(request.options());
    QueryExecution qe =
        QueryExecution.dataset(DatasetFactory.wrap(data))
            .query(query)
            .timeout(ExecSupport.timeoutMs(request.options()), TimeUnit.MILLISECONDS)
            .build();
    return ExecSupport.runCancellable(
        ctx,
        qe::abort,
        () -> {
          try (qe) {
            return runQuery(qe, kind, resolvedTarget, maxRows, ctx);
          }
        });
  }

  // ---- query execution (runs on ctx.executionPool()) ----------------------------------------

  private static ExecuteResponse runQuery(
      QueryExecution qe, QueryKind kind, String resolvedTarget, int maxRows, ExecContext ctx) {
    try {
      return ExecSupport.successFor(qe, kind, resolvedTarget, maxRows);
    } catch (QueryCancelledException e) {
      // The in-process engine raises the same exception for both ways an execution is cut short:
      // the timeout, and the best-effort abort() runCancellable fires on client cancellation.
      if (ctx.cancelChecker().isCanceled()) {
        LOG.debug("{} query aborted after cancellation", kind);
        return ExecuteResponse.cancelled();
      }
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.TIMEOUT, "Query timed out.", e.getMessage(), null, null));
    } catch (CancellationException e) {
      LOG.debug("{} query aborted after cancellation", kind);
      return ExecuteResponse.cancelled();
    } catch (RuntimeException e) {
      LOG.error("Unexpected error executing local {} query: {}", kind, e.getMessage(), e);
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.INTERNAL, "Internal error: " + e.getMessage(), null, null, null));
    }
  }
}
