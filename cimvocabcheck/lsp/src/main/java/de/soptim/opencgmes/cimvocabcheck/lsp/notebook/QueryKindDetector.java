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

import de.soptim.opencgmes.cimvocabcheck.core.InvalidQueryException;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.SparqlQueryAnalyzer;
import org.apache.jena.query.Query;
import org.apache.jena.update.UpdateRequest;

/**
 * Classifies a cell's text as a SPARQL query (with its {@link QueryKind}) or a SPARQL Update
 * request, by attempting both parses in turn.
 *
 * <p>Reuses {@link SparqlQueryAnalyzer#parse} / {@link SparqlQueryAnalyzer#parseUpdate} directly
 * rather than a separate parsing path, so notebook execution shares the same base-IRI policy
 * ({@link SparqlQueryAnalyzer#RELATIVE_IRI_BASE}) as validation.
 */
final class QueryKindDetector {

  private QueryKindDetector() {}

  /** Outcome of classifying a cell's text. */
  sealed interface Result {}

  /** The text parsed as a SPARQL query of the given {@link QueryKind}. */
  record AsQuery(QueryKind kind, Query query) implements Result {}

  /** The text parsed as a SPARQL Update request. */
  record AsUpdate(UpdateRequest update) implements Result {}

  /** The text is neither a valid query nor a valid update. */
  record ParseFailure(String message, Integer line, Integer column) implements Result {}

  /**
   * Attempts to parse {@code text} as a SPARQL query first, then as a SPARQL Update request. If
   * both fail, the <em>query</em> parser's error is reported — matching the fallback precedent in
   * {@code SparqlValidationApi#validateAutoDetectRaw}.
   */
  static Result detect(String text) {
    SparqlQueryAnalyzer analyzer = new SparqlQueryAnalyzer();
    InvalidQueryException queryError;
    try {
      Query query = analyzer.parse(text);
      return new AsQuery(kindOf(query), query);
    } catch (InvalidQueryException e) {
      queryError = e;
    }
    try {
      return new AsUpdate(analyzer.parseUpdate(text));
    } catch (InvalidQueryException ignored) {
      // Neither parse succeeded — report the query error, per SparqlValidationApi precedent.
      return new ParseFailure(queryError.getMessage(), queryError.line(), queryError.column());
    }
  }

  private static QueryKind kindOf(Query query) {
    if (query.isSelectType()) {
      return QueryKind.SELECT;
    }
    if (query.isAskType()) {
      return QueryKind.ASK;
    }
    if (query.isConstructType()) {
      return QueryKind.CONSTRUCT;
    }
    if (query.isDescribeType()) {
      return QueryKind.DESCRIBE;
    }
    throw new IllegalStateException("Unknown query type: " + query);
  }
}
