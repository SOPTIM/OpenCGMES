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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@code cimvocabcheck.rdfArchitectTerms} workspace command, which tells an editor
 * which tokens of a document should Ctrl+Click into RDFArchitect.
 *
 * <p>The instance addressed here is deliberately unreachable: the answer must not depend on a
 * schema having been loaded from it, since the editor asks the moment a document is opened.
 */
public class RdfArchitectTermsCommandTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  /** A port nothing listens on, so the background schema load fails immediately. */
  private static final String INSTANCE = "http://127.0.0.1:1";

  private SparqlTextDocumentService documents;
  private SparqlWorkspaceService workspace;

  @Before
  public void setUp() {
    SchemaManager schemaManager = new SchemaManager();
    documents = new SparqlTextDocumentService(schemaManager, new NotebookDefaults());
    workspace =
        new SparqlWorkspaceService(
            schemaManager, documents, new NotebookCommandHandler(), new NotebookDefaults());
  }

  private void open(String uri, String text) {
    documents.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "sparql", 1, text)));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> run(String uri) throws Exception {
    return (Map<String, Object>)
        workspace
            .executeCommand(
                new ExecuteCommandParams(
                    SparqlWorkspaceService.CMD_RDFARCHITECT_TERMS, List.of(uri)))
            .get();
  }

  @Test
  public void reportsTheInstanceAndTheModelTermsOfADirectiveDocument() throws Exception {
    String uri = "file:///queries/live.rq";
    String query =
        "# [rdfarchitect="
            + INSTANCE
            + "/?dataset=cgmes]\n"
            + "PREFIX cim: <"
            + CIM
            + ">\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
            + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r ; rdfs:label ?l"
            + " }\n";
    open(uri, query);

    Map<String, Object> result = run(uri);

    assertEquals(INSTANCE, result.get("baseUrl"));
    assertEquals(List.of(CIM + "ACLineSegment", CIM + "ACLineSegment.r"), iris(terms(result)));
  }

  @Test
  public void rangesCoverTheTokenThatNamesTheTerm() throws Exception {
    String uri = "file:///queries/ranges.rq";
    String query =
        "# [rdfarchitect="
            + INSTANCE
            + "/?dataset=cgmes]\n"
            + "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE { ?s a cim:ACLineSegment }\n";
    open(uri, query);

    Map<String, Object> term = terms(run(uri)).get(0);

    String line = query.split("\n")[2];
    assertEquals(2, term.get("line"));
    assertEquals(line.indexOf("cim:ACLineSegment"), term.get("startCharacter"));
    assertEquals(
        line.indexOf("cim:ACLineSegment") + "cim:ACLineSegment".length(), term.get("endCharacter"));
  }

  @Test
  public void skipsPrefixDeclarationsAndStandardVocabulary() {
    String query =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "SELECT * WHERE { ?s rdf:type cim:Terminal ; <"
            + CIM
            + "Terminal.sequenceNumber> ?n }\n";

    List<String> found =
        SparqlTextDocumentService.schemaTermsIn(query, null).stream()
            .map(SparqlTextDocumentService.TermRange::iri)
            .toList();

    // The namespace IRIs of the PREFIX lines are declarations, not terms; rdf:type is standard.
    assertEquals(List.of(CIM + "Terminal", CIM + "Terminal.sequenceNumber"), found);
  }

  @Test
  public void treatsComparisonOperatorsAsOperators() {
    // "<" opens an IRI only when "> " follows without whitespace; the terms after one must still
    // be found.
    String query =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "SELECT * WHERE { ?s a cim:Terminal ; ?p ?n FILTER(?n < 5 && ?n > 1) "
            + "?s a cim:ACLineSegment }\n";

    List<String> found =
        SparqlTextDocumentService.schemaTermsIn(query, null).stream()
            .map(SparqlTextDocumentService.TermRange::iri)
            .toList();

    assertEquals(List.of(CIM + "Terminal", CIM + "ACLineSegment"), found);
  }

  @Test
  public void skipsCommentsAndUndeclaredPrefixes() {
    String query =
        "PREFIX cim: <"
            + CIM
            + ">\n"
            + "# cim:ACLineSegment is only mentioned here\n"
            + "SELECT * WHERE { ?s a other:Thing }\n";

    assertTrue(SparqlTextDocumentService.schemaTermsIn(query, null).isEmpty());
  }

  @Test
  public void reportsTermsEvenWhenNoInstanceIsKnownYet() throws Exception {
    // A bare dataset name with no window connected. Which instance holds it is the editor's to
    // say — but the terms are navigable all the same, and reporting "not RDFArchitect" here would
    // silently take Ctrl+Click away with nothing to explain why.
    String uri = "file:///queries/bare.rq";
    open(
        uri,
        "# [rdfarchitect=cgmes-3.0]\n"
            + "PREFIX cim: <"
            + CIM
            + ">\nSELECT * WHERE { ?s a cim:ACLineSegment }\n");

    Map<String, Object> result = run(uri);

    assertNull("the instance is unknown here", result.get("baseUrl"));
    assertEquals("cgmes-3.0", result.get("dataset"));
    assertEquals(List.of(CIM + "ACLineSegment"), iris(terms(result)));
  }

  @Test
  public void nullForADocumentThatIsNotBackedByRdfArchitect() throws Exception {
    String uri = "file:///queries/plain.rq";
    open(uri, "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s a cim:ACLineSegment }\n");

    assertNull(run(uri));
  }

  @Test
  public void nullForAnEndpointDocument() throws Exception {
    String uri = "file:///queries/endpoint.rq";
    open(
        uri,
        "# [endpoint=http://127.0.0.1:1/query]\n"
            + "PREFIX cim: <"
            + CIM
            + ">\nSELECT * WHERE { ?s a cim:ACLineSegment }\n");

    assertNull(run(uri));
  }

  @Test
  public void nullForAnUnknownDocument() throws Exception {
    assertNull(run("file:///not/open.rq"));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> terms(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("terms");
  }

  private static List<String> iris(List<Map<String, Object>> terms) {
    return terms.stream().map(t -> (String) t.get("iri")).toList();
  }
}
