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

/**
 * Result of the {@code cimvocabcheck.notebook.execute} workspace command.
 *
 * <p>{@code resultsJson} (SELECT/ASK) and {@code turtle} (CONSTRUCT/DESCRIBE) are opaque,
 * pre-serialized payload strings — the client renders them without re-interpreting their structure
 * beyond the {@code queryKind} tag. At most one of the two is set; both are {@code null} for a
 * successful UPDATE and for any non-{@code SUCCESS} status.
 *
 * @param status the outcome of the execution: an {@link ExecutionStatus} name. Status and kind are
 *     carried as strings because lsp4j's message Gson serializes enum fields as ordinals, which no
 *     client should have to decode (pinned by {@code NotebookCommandHandlerTest}).
 * @param queryKind the detected kind of the cell's text: a {@link QueryKind} name; {@code null}
 *     unless {@code status} is {@code SUCCESS}.
 * @param resultsJson SPARQL 1.1 Query Results JSON for a SELECT or ASK, {@code null} otherwise.
 * @param turtle Turtle serialization of the result graph for a CONSTRUCT or DESCRIBE, or of the
 *     validation report for a SHACL run; {@code null} otherwise.
 * @param shaclSummary aggregated verdict of a SHACL run; {@code null} unless {@code queryKind} is
 *     {@code SHACL}.
 * @param stats execution metadata; {@code null} unless {@code status} is {@code SUCCESS}.
 * @param error failure details; {@code null} unless {@code status} is not {@code SUCCESS}.
 */
record ExecuteResponse(
    String status,
    String queryKind,
    String resultsJson,
    String turtle,
    ShaclSummary shaclSummary,
    ExecStats stats,
    ExecError error) {

  /** A successful SELECT or ASK execution. */
  static ExecuteResponse successJson(QueryKind kind, String resultsJson, ExecStats stats) {
    return new ExecuteResponse(
        ExecutionStatus.SUCCESS.name(), kind.name(), resultsJson, null, null, stats, null);
  }

  /** A successful CONSTRUCT or DESCRIBE execution. */
  static ExecuteResponse successTurtle(QueryKind kind, String turtle, ExecStats stats) {
    return new ExecuteResponse(
        ExecutionStatus.SUCCESS.name(), kind.name(), null, turtle, null, stats, null);
  }

  /** A successful UPDATE execution (no result payload). */
  static ExecuteResponse successUpdate(ExecStats stats) {
    return new ExecuteResponse(
        ExecutionStatus.SUCCESS.name(), QueryKind.UPDATE.name(), null, null, null, stats, null);
  }

  /** A completed SHACL validation run (successful even when the data does not conform). */
  static ExecuteResponse successShacl(String reportTurtle, ShaclSummary summary, ExecStats stats) {
    return new ExecuteResponse(
        ExecutionStatus.SUCCESS.name(),
        QueryKind.SHACL.name(),
        null,
        reportTurtle,
        summary,
        stats,
        null);
  }

  /** A failed execution. */
  static ExecuteResponse failed(ExecError error) {
    return new ExecuteResponse(ExecutionStatus.ERROR.name(), null, null, null, null, null, error);
  }

  /** An execution that was cancelled before it produced a result. */
  static ExecuteResponse cancelled() {
    return new ExecuteResponse(
        ExecutionStatus.CANCELLED.name(),
        null,
        null,
        null,
        null,
        null,
        new ExecError(ErrorCode.CANCELLED, "Execution was cancelled.", null, null, null));
  }
}
