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

package de.soptim.opencgmes.cimcheck.core;

import de.soptim.opencgmes.cimcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link SparqlValidationApi#checkShaclSyntaxOnly(Graph)} — the schema-independent
 * fallback that syntax-checks SPARQL fragments embedded in a SHACL shapes graph when no schema can
 * be resolved (e.g. an unreachable {@code # [endpoint=...]}). The SHACL counterpart of
 * {@link SyntaxOnlyValidationTest}.
 */
public class ShaclSyntaxOnlyValidationTest {

    private static final String PREAMBLE = """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix ex:  <http://example.org/> .
            """;

    private static Graph parse(String turtle) {
        Graph g = GraphFactory.createDefaultGraph();
        RDFParser.fromString(turtle, Lang.TURTLE).parse(g);
        return g;
    }

    @Test
    public void validEmbeddedSelectHasNoAnnotations() {
        Graph g = parse(PREAMBLE + """
                ex:Shape a sh:NodeShape ;
                  sh:sparql [ sh:select "SELECT $this WHERE { $this a cim:ACLineSegment }" ] .
                """);
        ShaclValidationResult result = SparqlValidationApi.checkShaclSyntaxOnly(g);
        assertEquals(1, result.embeddedResults().size());
        assertTrue(result.embeddedResults().get(0).result().annotations().isEmpty());
    }

    @Test
    public void brokenEmbeddedSelectYieldsSingleSyntaxError() {
        Graph g = parse(PREAMBLE + """
                ex:Shape a sh:NodeShape ;
                  sh:sparql [ sh:select "SELEECT $this WHERE { $this a cim:ACLineSegment }" ] .
                """);
        ShaclValidationResult result = SparqlValidationApi.checkShaclSyntaxOnly(g);
        assertEquals(1, result.embeddedResults().size());
        var annotations = result.embeddedResults().get(0).result().annotations();
        assertEquals(1, annotations.size());
        assertEquals(SparqlValidationCode.SYNTAX_ERROR, annotations.get(0).code());
    }

    @Test
    public void shapeAnnotationsAreEmptyBecauseNoSchemaIsConsulted() {
        // cim:Bogus is not a real class, but with no schema there is nothing to check it against,
        // so the fallback must not emit any shape-structure annotations.
        Graph g = parse(PREAMBLE + """
                ex:Shape a sh:NodeShape ;
                  sh:targetClass cim:Bogus ;
                  sh:sparql [ sh:select "SELECT $this WHERE { $this a cim:Bogus }" ] .
                """);
        ShaclValidationResult result = SparqlValidationApi.checkShaclSyntaxOnly(g);
        assertTrue(result.shapeAnnotations().isEmpty());
        assertTrue(result.embeddedResults().get(0).result().annotations().isEmpty());
    }

    @Test
    public void shPrefixesAreNotMisreportedAsSyntaxErrors() {
        // A fragment that relies on sh:prefixes must render with those prefixes prepended so the
        // use of cim: does not parse as a syntax error.
        Graph g = parse(PREAMBLE + """
                ex:Shape a sh:NodeShape ;
                  sh:sparql [
                    sh:prefixes ex:prefixes ;
                    sh:select "SELECT $this WHERE { $this a cim:ACLineSegment }"
                  ] .
                ex:prefixes sh:declare [ sh:prefix "cim" ; sh:namespace "http://iec.ch/TC57/CIM100#" ] .
                """);
        ShaclValidationResult result = SparqlValidationApi.checkShaclSyntaxOnly(g);
        assertEquals(1, result.embeddedResults().size());
        assertTrue(result.embeddedResults().get(0).result().annotations().isEmpty());
    }

    @Test
    public void graphWithoutEmbeddedSparqlYieldsNoResults() {
        Graph g = parse(PREAMBLE + """
                ex:Shape a sh:NodeShape ;
                  sh:targetClass cim:ACLineSegment .
                """);
        ShaclValidationResult result = SparqlValidationApi.checkShaclSyntaxOnly(g);
        assertTrue(result.shapeAnnotations().isEmpty());
        assertTrue(result.embeddedResults().isEmpty());
    }
}
