/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimcheck.lsp;

import de.soptim.opencgmes.cimcheck.lsp.SparqlTextDocumentService.Kind;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Covers the document classification and URI-directory logic that lets SPARQL Notebook cells
 * — which arrive under the {@code vscode-notebook-cell} scheme with no file extension — flow
 * through the same validation path as {@code .rq}/{@code .ttl} files.
 */
public class DocumentClassificationTest {

    // ---- File extensions (unchanged file/IntelliJ path) ------------------------------------

    @Test
    public void sparqlFileByExtension() {
        assertEquals(Kind.SPARQL, SparqlTextDocumentService.classify("file:///q/query.rq", null));
        assertEquals(Kind.SPARQL, SparqlTextDocumentService.classify("file:///q/query.sparql", null));
    }

    @Test
    public void shaclFileByExtension() {
        assertEquals(Kind.SHACL, SparqlTextDocumentService.classify("file:///s/shapes.ttl", null));
        assertEquals(Kind.SHACL, SparqlTextDocumentService.classify("file:///s/shapes.shacl", null));
    }

    @Test
    public void extensionWinsOverLanguageId() {
        assertEquals(Kind.SHACL,
                SparqlTextDocumentService.classify("file:///s/shapes.ttl", "sparql"));
    }

    // ---- Notebook cells (no extension → languageId decides) --------------------------------

    @Test
    public void sparqlNotebookCellByLanguageId() {
        // SPARQL Notebook assigns code cells the languageId "sparql".
        String cellUri = "vscode-notebook-cell:/home/u/analysis.sparqlbook#W0sZmlsZQ";
        assertEquals(Kind.SPARQL, SparqlTextDocumentService.classify(cellUri, "sparql"));
    }

    @Test
    public void turtleCellByLanguageId() {
        String cellUri = "vscode-notebook-cell:/home/u/analysis.sparqlbook#X1sabc";
        assertEquals(Kind.SHACL, SparqlTextDocumentService.classify(cellUri, "turtle"));
    }

    @Test
    public void unsupportedWhenNoExtensionAndUnknownLanguage() {
        assertNull(SparqlTextDocumentService.classify("untitled:Untitled-1", "plaintext"));
        assertNull(SparqlTextDocumentService.classify("untitled:Untitled-1", null));
    }

    // ---- Directory resolution for relative endpoint paths ----------------------------------

    @Test
    public void documentDirOfNotebookCellStripsFragment() {
        // Relative "# [endpoint=...]" paths resolve against the notebook's own directory.
        String cellUri = "vscode-notebook-cell:/home/u/proj/analysis.sparqlbook#W0sZmlsZQ";
        assertEquals(Path.of("/home/u/proj"), SparqlTextDocumentService.documentDir(cellUri));
    }

    @Test
    public void documentDirOfFileUri() {
        assertEquals(Path.of("/home/u/proj"),
                SparqlTextDocumentService.documentDir("file:///home/u/proj/query.rq"));
    }
}
