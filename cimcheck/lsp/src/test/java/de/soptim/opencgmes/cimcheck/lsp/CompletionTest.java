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

import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.core.schema.SchemaIndex;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.GraphFactory;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for code completion: {@link SparqlTextDocumentService#buildCompletionItems} and
 * {@link SparqlTextDocumentService#isClassContext}.
 */
public class CompletionTest {

    private static final String CIM = "http://iec.ch/TC57/CIM100#";
    private static final String PROFILE = "http://example.org/profile/EQ/1.0";

    private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
    private static final String CLASS_SUB     = CIM + "Substation";
    private static final String PROP_R        = CIM + "ACLineSegment.r";
    private static final String PROP_V        = CIM + "ACLineSegment.v";

    private SchemaIndex index;

    @Before
    public void setUp() {
        index = RdfsSchemaIndex.builder()
                .addProfile(PROFILE,
                        List.of(CLASS_AC_LINE, CLASS_SUB),
                        List.of(PROP_R, PROP_V))
                .build();
    }

    // ============================================================================================
    // SchemaIndex enumeration
    // ============================================================================================

    @Test
    public void allClasses_returnsRegisteredClasses() {
        Set<org.apache.jena.graph.Node> classes = index.allClasses();
        assertTrue(classes.contains(NodeFactory.createURI(CLASS_AC_LINE)));
        assertTrue(classes.contains(NodeFactory.createURI(CLASS_SUB)));
    }

    @Test
    public void allProperties_returnsRegisteredProperties() {
        Set<org.apache.jena.graph.Node> props = index.allProperties();
        assertTrue(props.contains(NodeFactory.createURI(PROP_R)));
        assertTrue(props.contains(NodeFactory.createURI(PROP_V)));
    }

    // ============================================================================================
    // buildCompletionItems — basic filtering
    // ============================================================================================

    @Test
    public void prefixTyped_returnsMatchingItems() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim: ?o }";
        // Cursor after "cim:" on line 1, col = indexOf("cim:") + 4
        int col = text.split("\n")[1].indexOf("cim:") + 4;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        assertFalse("should return items when prefix is known", items.isEmpty());
    }

    @Test
    public void localFilter_narrowsResults() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim:Sub ?o }";
        int col = text.split("\n")[1].indexOf("cim:Sub") + 7;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        assertTrue("should contain Substation", items.stream().anyMatch(i -> i.getLabel().equals("cim:Substation")));
        assertTrue("should not contain ACLineSegment", items.stream().noneMatch(i -> i.getLabel().contains("ACLine")));
    }

    @Test
    public void noPrefix_returnsEmpty() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s AC ?o }";
        int col = text.split("\n")[1].indexOf("AC") + 2;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        assertTrue("no recognized prefix → empty", items.isEmpty());
    }

    @Test
    public void unknownPrefix_returnsEmpty() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s xyz:AC ?o }";
        int col = text.split("\n")[1].indexOf("xyz:AC") + 6;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        assertTrue("unknown prefix → empty", items.isEmpty());
    }

    @Test
    public void insideComment_returnsEmpty() {
        String text = "PREFIX cim: <" + CIM + ">\n# cim:ACLineSegment";
        int line = 1;
        int col = text.split("\n")[1].indexOf("cim:ACLineSegment") + 10;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, line, col, index);
        assertTrue("tokens in # comments must not complete", items.isEmpty());
    }

    @Test
    public void results_areSortedAlphabetically() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim: ?o }";
        int col = text.split("\n")[1].indexOf("cim:") + 4;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        for (int i = 1; i < items.size(); i++) {
            assertTrue(items.get(i - 1).getLabel().compareTo(items.get(i).getLabel()) <= 0);
        }
    }

    // ============================================================================================
    // buildCompletionItems — kind assignment
    // ============================================================================================

    @Test
    public void classItem_hasClassKind() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim:Sub ?o }";
        int col = text.split("\n")[1].indexOf("cim:Sub") + 7;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        CompletionItem sub = items.stream()
                .filter(i -> i.getLabel().equals("cim:Substation"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Substation not found"));
        assertEquals(CompletionItemKind.Class, sub.getKind());
    }

    @Test
    public void propertyItem_hasPropertyKind() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim:ACLineSegment.r ?o }";
        int col = text.split("\n")[1].indexOf("cim:ACLineSegment.r") + 19;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        CompletionItem prop = items.stream()
                .filter(i -> i.getLabel().equals("cim:ACLineSegment.r"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ACLineSegment.r not found"));
        assertEquals(CompletionItemKind.Property, prop.getKind());
    }

    // ============================================================================================
    // buildCompletionItems — class context (after type predicates)
    // ============================================================================================

    @Test
    public void afterAKeyword_onlyClassesReturned() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s a cim: }";
        int col = text.split("\n")[1].indexOf("cim:") + 4;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        assertFalse(items.isEmpty());
        assertTrue("all items should be classes after 'a'",
                items.stream().allMatch(i -> i.getKind() == CompletionItemKind.Class));
    }

    @Test
    public void afterRdfType_onlyClassesReturned() {
        String text = "PREFIX cim: <" + CIM + ">\n"
                + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
                + "SELECT * WHERE { ?s rdf:type cim: }";
        String[] lines = text.split("\n");
        int lastLine = lines.length - 1;
        int col = lines[lastLine].indexOf("cim:") + 4;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, lastLine, col, index);
        assertFalse(items.isEmpty());
        assertTrue("all items should be classes after rdf:type",
                items.stream().allMatch(i -> i.getKind() == CompletionItemKind.Class));
    }

    @Test
    public void noTypeContext_propertiesAndClassesReturned() {
        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim: ?o }";
        int col = text.split("\n")[1].indexOf("cim:") + 4;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, index);
        boolean hasClass    = items.stream().anyMatch(i -> i.getKind() == CompletionItemKind.Class);
        boolean hasProperty = items.stream().anyMatch(i -> i.getKind() == CompletionItemKind.Property);
        assertTrue("should include classes",     hasClass);
        assertTrue("should include properties",  hasProperty);
    }

    // ============================================================================================
    // buildCompletionItems — documentation from schema (TTL-backed index)
    // ============================================================================================

    @Test
    public void commentPopulated_whenSchemaHasRdfsComment() {
        String ttl = "@prefix cim: <" + CIM + "> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + CLASS_AC_LINE + "> a owl:Class ;\n"
                + "    rdfs:comment \"A segment of line.\" .\n";
        SchemaIndex rich = indexFromTurtle(ttl);

        String text = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s a cim:ACLine }";
        String[] lines = text.split("\n");
        int col = lines[1].indexOf("cim:ACLine") + 10;
        List<CompletionItem> items = SparqlTextDocumentService.buildCompletionItems(text, 1, col, rich);
        CompletionItem item = items.stream()
                .filter(i -> i.getLabel().equals("cim:ACLineSegment"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ACLineSegment not found"));
        assertNotNull("documentation should be set", item.getDocumentation());
    }

    // ============================================================================================
    // isClassContext
    // ============================================================================================

    @Test
    public void isClassContext_afterA_true() {
        String src = "{ ?s a cim:";
        PrefixMapping pm = PrefixMapping.Factory.create();
        pm.setNsPrefix("cim", CIM);
        // tokenStart = index of the 'c' in "cim:" — the token being typed
        assertTrue(SparqlTextDocumentService.isClassContext(src, src.indexOf("cim:"), pm));
    }

    @Test
    public void isClassContext_afterSubject_false() {
        String src = "{ ?s cim:";
        PrefixMapping pm = PrefixMapping.Factory.create();
        pm.setNsPrefix("cim", CIM);
        // Subject "?s" precedes — not a type predicate
        assertFalse(SparqlTextDocumentService.isClassContext(src, src.indexOf("cim:"), pm));
    }

    // ============================================================================================
    // Helper
    // ============================================================================================

    private static SchemaIndex indexFromTurtle(String ttl) {
        Graph g = GraphFactory.createDefaultGraph();
        RDFParser.fromString(ttl, Lang.TURTLE).parse(g);
        var v = de.soptim.opencgmes.cimcheck.core.VersionIri.of(PROFILE);
        var schema = RdfsSchemaIndex.indexGraph(v, g);
        return RdfsSchemaIndex.builder().addProfile(schema).build();
    }
}
