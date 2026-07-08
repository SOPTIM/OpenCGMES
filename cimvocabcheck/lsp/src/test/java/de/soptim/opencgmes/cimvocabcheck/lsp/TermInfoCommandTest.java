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

import com.google.gson.JsonPrimitive;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@code cimvocabcheck.termInfo} workspace command: resolving the schema term under a
 * cursor position of an open document to its full IRI.
 */
public class TermInfoCommandTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String DOC_URI = "file:///queries/query.rq";
  private static final String QUERY =
      "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s a cim:ACLineSegment }\n";

  private SparqlTextDocumentService documents;
  private SparqlWorkspaceService workspace;

  @Before
  public void setUp() {
    SchemaManager schemaManager = new SchemaManager();
    documents = new SparqlTextDocumentService(schemaManager, new NotebookDefaults());
    workspace =
        new SparqlWorkspaceService(
            schemaManager, documents, new NotebookCommandHandler(), new NotebookDefaults());
    documents.didOpen(
        new DidOpenTextDocumentParams(
            new org.eclipse.lsp4j.TextDocumentItem(DOC_URI, "sparql", 1, QUERY)));
  }

  private Object run(Object... args) throws Exception {
    return workspace
        .executeCommand(
            new ExecuteCommandParams(SparqlWorkspaceService.CMD_TERM_INFO, List.of(args)))
        .get();
  }

  @Test
  public void resolvesPrefixedNameToFullIri() throws Exception {
    int character = QUERY.split("\n")[1].indexOf("cim:ACLineSegment") + 5;
    Object result = run(DOC_URI, 1, character);
    assertEquals(Map.of("iri", CIM + "ACLineSegment"), result);
  }

  @Test
  public void acceptsJsonRpcArgumentForms() throws Exception {
    // Over JSON-RPC, lsp4j delivers all arguments as Gson JsonPrimitives.
    int character = QUERY.split("\n")[1].indexOf("cim:ACLineSegment") + 5;
    Object result =
        run(new JsonPrimitive(DOC_URI), new JsonPrimitive(1), new JsonPrimitive(character));
    assertEquals(Map.of("iri", CIM + "ACLineSegment"), result);
  }

  @Test
  public void nullWhenNoTermAtPosition() throws Exception {
    assertNull(run(DOC_URI, 1, 0)); // on the SELECT keyword
  }

  @Test
  public void nullForUnknownDocument() throws Exception {
    assertNull(run("file:///not/open.rq", 1, 25));
  }

  @Test
  public void nullOnMissingArguments() throws Exception {
    assertNull(run(DOC_URI));
  }
}
