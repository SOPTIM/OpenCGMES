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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QueryKindDetectorTest {

  @Test
  public void detectsSelect() {
    var result = QueryKindDetector.detect("SELECT * WHERE { ?s ?p ?o }");
    assertTrue(result instanceof QueryKindDetector.AsQuery);
    var asQuery = (QueryKindDetector.AsQuery) result;
    assertEquals(QueryKind.SELECT, asQuery.kind());
  }

  @Test
  public void detectsAsk() {
    var result = QueryKindDetector.detect("ASK { ?s ?p ?o }");
    assertTrue(result instanceof QueryKindDetector.AsQuery);
    assertEquals(QueryKind.ASK, ((QueryKindDetector.AsQuery) result).kind());
  }

  @Test
  public void detectsConstruct() {
    var result = QueryKindDetector.detect("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }");
    assertTrue(result instanceof QueryKindDetector.AsQuery);
    assertEquals(QueryKind.CONSTRUCT, ((QueryKindDetector.AsQuery) result).kind());
  }

  @Test
  public void detectsDescribe() {
    var result = QueryKindDetector.detect("DESCRIBE <http://example.org/s>");
    assertTrue(result instanceof QueryKindDetector.AsQuery);
    assertEquals(QueryKind.DESCRIBE, ((QueryKindDetector.AsQuery) result).kind());
  }

  @Test
  public void detectsInsertDataUpdate() {
    var result =
        QueryKindDetector.detect("INSERT DATA { <http://example.org/s> <http://example.org/p> 1 }");
    assertTrue(result instanceof QueryKindDetector.AsUpdate);
    assertNotNull(((QueryKindDetector.AsUpdate) result).update());
  }

  @Test
  public void detectsDeleteWhereUpdate() {
    var result = QueryKindDetector.detect("DELETE WHERE { ?s ?p ?o }");
    assertTrue(result instanceof QueryKindDetector.AsUpdate);
  }

  @Test
  public void reportsParseFailureWithPositionWhenTheParserProvidesOne() {
    // An "expected token" parse error (as opposed to a lexical error, see below) carries a
    // real 1-based line/column from Jena's parser.
    var result = QueryKindDetector.detect("SELECT * WHERE { ?s ?p");
    assertTrue(result instanceof QueryKindDetector.ParseFailure);
    var failure = (QueryKindDetector.ParseFailure) result;
    assertNotNull(failure.message());
    assertEquals(Integer.valueOf(1), failure.line());
    assertNotNull(failure.column());
  }

  @Test
  public void reportsParseFailureWithoutPositionForLexicalErrors() {
    // Jena reports some failures (e.g. a lexical error on completely non-SPARQL text) with
    // line/column 0, which SparqlQueryAnalyzer normalizes to null rather than a misleading "0".
    var result = QueryKindDetector.detect("this is not sparql at all");
    assertTrue(result instanceof QueryKindDetector.ParseFailure);
    var failure = (QueryKindDetector.ParseFailure) result;
    assertNotNull(failure.message());
    assertNull(failure.line());
    assertNull(failure.column());
  }

  @Test
  public void emptyTextParsesAsAnEmptySparqlUpdate() {
    // A SPARQL Update request may contain zero operations, so blank/whitespace-only cell text
    // is a valid (no-op) update rather than a parse failure.
    var result = QueryKindDetector.detect("");
    assertTrue(result instanceof QueryKindDetector.AsUpdate);
  }
}
