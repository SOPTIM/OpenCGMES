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

package de.soptim.opencgmes.sparql.validation;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Direct unit tests for {@link SourceLocator}.
 *
 * <p>The validator-level tests (e.g. {@link SparqlValidationApiTest}) exercise the locator
 * indirectly via the {@code line}/{@code column} fields of emitted annotations; these tests
 * cover the locator algorithm in isolation, including the three lookup forms (full IRI,
 * prefixed name, {@code a} keyword) and the earliest-match selection.</p>
 */
public class SourceLocatorTest {

    private static final String CIM = "http://iec.ch/TC57/CIM100#";

    @Test
    public void fullIriFormIsFound() {
        String q = "SELECT * WHERE { ?s <http://iec.ch/TC57/CIM100#ACLineSegment> ?o }";
        var loc = SourceLocator.locate(q, NodeFactory.createURI(CIM + "ACLineSegment"));
        assertEquals(Integer.valueOf(1), loc.line());
        assertEquals(Integer.valueOf(21), loc.column());
    }

    @Test
    public void prefixedNameIsFoundViaPrefixMap() {
        String q = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim:ACLineSegment ?o }";
        Query parsed = QueryFactory.create(q);
        var loc = SourceLocator.locate(q,
                NodeFactory.createURI(CIM + "ACLineSegment"),
                parsed.getPrefixMapping());
        // "cim:" appears on line 2 inside the WHERE.
        assertEquals(Integer.valueOf(2), loc.line());
        // 1-based column of the 'c' in "cim:"
        assertTrue("expected column at start of cim:..., got " + loc.column(),
                loc.column() > 0);
    }

    @Test
    public void aKeywordResolvesToRdfType() {
        String q = "SELECT * WHERE { ?s a ?cls }";
        var loc = SourceLocator.locate(q, RDF.type.asNode(),
                PrefixMapping.Factory.create());
        assertEquals(Integer.valueOf(1), loc.line());
        // 'a' is at "{ ?s a ?cls }" — find its column manually.
        int idx = q.indexOf("a ?cls");
        assertEquals(Integer.valueOf(idx + 1), loc.column());
    }

    @Test
    public void aKeywordNotMatchedInsidePrefixedLocalName() {
        // The local name "ab" must NOT be picked up as the 'a' keyword.
        String q = "PREFIX cim: <" + CIM + ">\nSELECT * WHERE { ?s cim:abc ?o }";
        Query parsed = QueryFactory.create(q);
        var loc = SourceLocator.locate(q, RDF.type.asNode(), parsed.getPrefixMapping());
        // No 'a' keyword in predicate position; locator should return UNKNOWN.
        assertNull("no rdf:type usage in query, but locator returned " + loc, loc.line());
        assertNull(loc.column());
    }

    @Test
    public void earliestOccurrenceWins() {
        // Term written first as full IRI then as prefixed name — locator returns the earlier one.
        String q = "PREFIX cim: <" + CIM + ">\n"
                + "SELECT * WHERE {\n"
                + "  ?s <" + CIM + "ACLineSegment.r> ?r .\n"
                + "  ?s cim:ACLineSegment.r ?r2 .\n"
                + "}";
        Query parsed = QueryFactory.create(q);
        var loc = SourceLocator.locate(q,
                NodeFactory.createURI(CIM + "ACLineSegment.r"),
                parsed.getPrefixMapping());
        assertEquals("earliest occurrence is on line 3 (the full IRI form)",
                Integer.valueOf(3), loc.line());
    }

    @Test
    public void prefixDeclaredButFullIriUsedStillResolves() {
        // PrefixMapping declares cim:, but the query body uses the full IRI form.
        String q = "PREFIX cim: <" + CIM + ">\n"
                + "SELECT * WHERE { ?s <" + CIM + "Foo> ?o }";
        Query parsed = QueryFactory.create(q);
        var loc = SourceLocator.locate(q,
                NodeFactory.createURI(CIM + "Foo"),
                parsed.getPrefixMapping());
        assertNotNull(loc.line());
        assertEquals(Integer.valueOf(2), loc.line());
    }

    @Test
    public void unknownTermReturnsUnknown() {
        String q = "SELECT * WHERE { ?s ?p ?o }";
        var loc = SourceLocator.locate(q, NodeFactory.createURI("http://example.org/Missing"));
        assertNull(loc.line());
        assertNull(loc.column());
    }

    @Test
    public void nullInputsAreSafe() {
        assertEquals(SourceLocator.UNKNOWN,
                SourceLocator.locate(null, RDF.type.asNode(), PrefixMapping.Factory.create()));
        assertEquals(SourceLocator.UNKNOWN,
                SourceLocator.locate("SELECT * WHERE { }", null, PrefixMapping.Factory.create()));
    }

    @Test
    public void blankNodeAndVariableNodesReturnUnknown() {
        var blank = NodeFactory.createBlankNode();
        var v = NodeFactory.createVariable("x");
        assertEquals(SourceLocator.UNKNOWN, SourceLocator.locate("SELECT *", blank));
        assertEquals(SourceLocator.UNKNOWN, SourceLocator.locate("SELECT *", v));
    }

}
