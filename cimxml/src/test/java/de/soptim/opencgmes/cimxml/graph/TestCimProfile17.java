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

package de.soptim.opencgmes.cimxml.graph;

import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.*;

public class TestCimProfile17 {


    /**
     * Test that the parser can parse a CIMXML document with a version declaration.
     * And that the version is correctly parsed.
     */
    @Test
    public void parseProfileOntologyHeader() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
               xmlns:cim="http://iec.ch/TC57/CIM100#"
               xmlns:dcat="http://www.w3.org/ns/dcat#"
               xmlns:owl="http://www.w3.org/2002/07/owl#"
               xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xml:base ="http://iec.ch/TC57/CIM100">

                <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                    <dcat:keyword>MYCUST</dcat:keyword>
                    <owl:versionIRI rdf:resource="http://example.org/MyCustom/Core/1/1"/>
                    <owl:versionIRI rdf:resource="http://example.org/MyCustom/Operation/1/1"/>
                    <owl:versionInfo xml:lang ="en">1.1.0</owl:versionInfo>
                   <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
                </rdf:Description >
            </rdf:RDF>
            """;

        var graph = GraphFactory.createGraphMem();

        RDFParser.create()
            .source(new StringReader(rdfxml))
            .lang(org.apache.jena.riot.Lang.RDFXML)
            .checking(false)
            .parse(graph);

        var ontology = CimProfile.wrap(graph);

        assertFalse(ontology.isHeaderProfile());
        assertEquals(CimProfile17.CIM_NAMESPACE, ontology.getCimNamespace());

        assertEquals(2, ontology.getOwlVersionIris().size());
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://example.org/MyCustom/Core/1/1")));
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://example.org/MyCustom/Operation/1/1")));
        assertEquals("1.1.0", ontology.getOwlVersionInfo());
        assertEquals("MYCUST", ontology.getDcatKeyword());
    }

    @Test
    public void parseProfileFileHeaderProfile() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:cim="http://iec.ch/TC57/CIM100#"
                xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                xml:base="http://iec.ch/TC57/CIM100">
                <rdf:Description rdf:about="#Package_FileHeaderProfile">
                    <rdfs:label xml:lang="en">FileHeaderProfile</rdfs:label>
                    <rdf:type rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory"/>
                </rdf:Description>
            </rdf:RDF>
            """;


        var graph = GraphFactory.createGraphMem();

        RDFParser.create()
                .source(new StringReader(rdfxml))
                .lang(org.apache.jena.riot.Lang.RDFXML)
                .checking(false)
                .parse(graph);

        var ontology = CimProfile.wrap(graph);

        assertTrue(ontology.isHeaderProfile());
        assertEquals(CimProfile17.CIM_NAMESPACE, ontology.getCimNamespace());
    }

}
