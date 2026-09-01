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

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.shacl.validation.Severity;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.graph.GraphFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a notebook SHACL cell: the cell text is the <em>shapes graph</em> (Turtle), validated
 * against the cell's target data. Local-file targets are validated in-process with jena-shacl over
 * the {@link LocalStoreManager} union graph; HTTP targets hand the shapes to the endpoint's SHACL
 * service via {@link HttpShaclClient}. Either way the response carries the full validation report
 * as Turtle plus a {@link ShaclSummary} for the client's verdict banner.
 *
 * <p>A completed validation is a <em>successful</em> execution even when the data does not conform
 * — non-conformance is the result, not an error.
 *
 * <p>In-process validation has no native timeout or abort hook, so it runs under the timeout-aware
 * {@link ExecSupport#runCancellable} variant: cancellation and timeout resolve the response while
 * the validation itself finishes in the background (same trade-off as a cancelled UPDATE,
 * documented on {@link HttpQueryExecutor}).
 */
final class ShaclExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(ShaclExecutor.class);

  private ShaclExecutor() {}

  /** Executes a SHACL cell, returning a populated {@link ExecuteResponse}. */
  static ExecuteResponse execute(
      ExecuteRequest request, LocalStoreManager stores, ExecContext ctx) {
    Graph shapesGraph;
    try {
      shapesGraph = parseShapesTurtle(request.text());
    } catch (ShapesSyntaxException e) {
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.PARSE_ERROR, e.getMessage(), null, e.line, e.column));
    }

    ExecuteTarget target = request.target();
    if (target == null) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.NO_TARGET,
              "No data to validate. Add a `# [endpoint=<url or file>]` directive to the cell.",
              null,
              null,
              null));
    }
    if (ExecuteTarget.TYPE_FILES.equals(target.type())) {
      return validateLocal(shapesGraph, request, stores, ctx);
    }
    return HttpShaclClient.validate(request.text(), target, request.options(), ctx);
  }

  // ---- local validation ------------------------------------------------------------------------

  private static ExecuteResponse validateLocal(
      Graph shapesGraph, ExecuteRequest request, LocalStoreManager stores, ExecContext ctx) {
    ExecuteTarget target = request.target();
    if (target.files() == null || target.files().isEmpty()) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.NO_TARGET,
              "No data to validate. Add a `# [endpoint=<url or file>]` directive to the cell.",
              null,
              null,
              null));
    }

    Shapes shapes;
    try {
      shapes = ShaclValidator.get().parse(shapesGraph);
    } catch (RuntimeException e) {
      // Syntactically valid Turtle that is not a well-formed shapes graph (e.g. a broken
      // sh:property) — jena-shacl reports this without source positions.
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.PARSE_ERROR, "Invalid SHACL shapes: " + e.getMessage(), null, null, null));
    }

    DatasetGraph data;
    try {
      List<Path> paths = NotebookPaths.resolvePaths(target.files(), request.notebookUri());
      data = stores.unionFor(paths);
    } catch (LocalStoreManager.StoreException e) {
      return ExecuteResponse.failed(new ExecError(e.code(), e.getMessage(), null, null, null));
    }

    // The union's default graph spans all graphs of all files (see LocalStoreManager), so the
    // shapes validate everything the user pointed the cell at.
    Graph dataGraph = data.getDefaultGraph();
    String resolvedTarget = String.join(", ", target.files());
    return ExecSupport.runCancellable(
        ctx,
        () -> {},
        () -> runValidation(shapes, dataGraph, resolvedTarget),
        ExecSupport.timeoutMs(request.options()));
  }

  /** Runs on {@code ctx.executionPool()}. */
  private static ExecuteResponse runValidation(
      Shapes shapes, Graph dataGraph, String resolvedTarget) {
    long start = System.currentTimeMillis();
    try {
      ValidationReport report = ShaclValidator.get().validate(shapes, dataGraph);
      return toResponse(report, reportTurtle(report), start, resolvedTarget);
    } catch (CancellationException e) {
      LOG.debug("SHACL validation aborted after cancellation");
      return ExecuteResponse.cancelled();
    } catch (RuntimeException e) {
      LOG.error("Unexpected error during SHACL validation: {}", e.getMessage(), e);
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.INTERNAL, "Internal error: " + e.getMessage(), null, null, null));
    }
  }

  // ---- shared report handling (also used by HttpShaclClient) ------------------------------------

  /** Builds the SUCCESS response for a completed validation, whatever produced the report. */
  static ExecuteResponse toResponse(
      ValidationReport report, String reportTurtle, long startMs, String resolvedTarget) {
    int violations = 0;
    int warnings = 0;
    int infos = 0;
    for (ReportEntry entry : report.getEntries()) {
      Severity severity = entry.severity();
      if (Severity.Warning.equals(severity)) {
        warnings++;
      } else if (Severity.Info.equals(severity)) {
        infos++;
      } else {
        violations++; // sh:Violation is also the SHACL default for unknown severities
      }
    }
    ShaclSummary summary = new ShaclSummary(report.conforms(), violations, warnings, infos);
    ExecStats stats = ExecSupport.stats(startMs, report.getEntries().size(), false, resolvedTarget);
    return ExecuteResponse.successShacl(reportTurtle, summary, stats);
  }

  static String reportTurtle(ValidationReport report) {
    StringWriter sw = new StringWriter();
    RDFDataMgr.write(sw, report.getModel(), RDFFormat.TURTLE_PRETTY);
    return sw.toString();
  }

  // ---- shapes parsing
  // ----------------------------------------------------------------------------

  /** The cell text is not valid Turtle; position is 1-based where the parser reported one. */
  private static final class ShapesSyntaxException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Integer line;
    private final Integer column;

    ShapesSyntaxException(String message, Integer line, Integer column) {
      super(message);
      this.line = line;
      this.column = column;
    }
  }

  private static Graph parseShapesTurtle(String text) throws ShapesSyntaxException {
    Graph graph = GraphFactory.createDefaultGraph();
    PositionErrorHandler errors = new PositionErrorHandler();
    try {
      RDFParser.create()
          .source(new StringReader(text))
          .lang(Lang.TTL)
          .errorHandler(errors)
          .parse(graph);
    } catch (RiotException e) {
      throw new ShapesSyntaxException(
          errors.message != null ? errors.message : e.getMessage(), errors.line, errors.column);
    }
    return graph;
  }

  /** Records the first reported parse error's message and 1-based position. */
  private static final class PositionErrorHandler implements ErrorHandler {

    private String message;
    private Integer line;
    private Integer column;

    @Override
    public void warning(String message, long line, long col) {
      // Warnings don't fail the parse.
    }

    @Override
    public void error(String message, long line, long col) {
      record(message, line, col);
      throw new RiotException(message);
    }

    @Override
    public void fatal(String message, long line, long col) {
      record(message, line, col);
      throw new RiotException(message);
    }

    private void record(String message, long line, long col) {
      if (this.message == null) {
        this.message = message;
        this.line = line > 0 ? (int) line : null;
        this.column = col > 0 ? (int) col : null;
      }
    }
  }
}
