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

        // Pre-2020 header profiles have no ontology object at all; asking for its version must
        // report the absence rather than fail.
        assertNull(ontology.getOntologyNode());
        assertTrue(ontology.getOwlVersionIris().isEmpty());
        assertNull(ontology.getOwlVersionInfo());
        assertEquals("FileHeaderProfile", ontology.getLabel());
    }

    /**
     * A CGMES 3.0 profile describes itself on its ontology object.  The profile's package repeats
     * the name and is the fallback for a profile that leaves the ontology object sparse.
     */
    @Test
    public void readProfileMetadata() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
               xmlns:cim="http://iec.ch/TC57/CIM100#"
               xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
               xmlns:dcat="http://www.w3.org/ns/dcat#"
               xmlns:dcterms="http://purl.org/dc/terms/"
               xmlns:owl="http://www.w3.org/2002/07/owl#"
               xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xml:base ="http://example.org/test">

                <rdf:Description rdf:about="http://example.org/test#Ontology">
                    <dcat:keyword>TST</dcat:keyword>
                    <owl:versionIRI rdf:resource="http://example.org/MyCustom/Core/1/1"/>
                    <owl:versionInfo xml:lang ="en">1.1.0</owl:versionInfo>
                    <dcterms:title xml:lang ="en">Test Vocabulary</dcterms:title>
                    <dcterms:description xml:lang ="en">A vocabulary for exercising the metadata accessors.</dcterms:description>
                    <dcterms:issued rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime">2021-01-27T12:09:21Z</dcterms:issued>
                    <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
                </rdf:Description >
                <rdf:Description rdf:about="#Package_TestProfile">
                    <rdfs:label xml:lang="en">TestProfile</rdfs:label>
                    <rdf:type rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory"/>
                </rdf:Description>
                <rdf:Description rdf:about="#Package_Domain">
                    <rdfs:label xml:lang="en">Domain</rdfs:label>
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

        var profile = CimProfile.wrap(graph);

        // The categories that merely group classes must not be mistaken for the profile itself.
        assertEquals("http://example.org/test#Package_TestProfile",
                profile.getProfilePackage().getURI());

        var metadata = profile.getMetadata();

        assertEquals(CimProfile17.CIM_NAMESPACE, metadata.cimNamespace());
        assertFalse(metadata.headerProfile());
        assertEquals("TST", metadata.keyword());
        assertEquals("Test Vocabulary", metadata.label());
        assertEquals("A vocabulary for exercising the metadata accessors.", metadata.description());
        assertEquals(1, metadata.versionIris().size());
        assertEquals("1.1.0", metadata.versionInfo());
        assertEquals("2021-01-27T12:09:21Z", metadata.issued());
    }

    /**
     * A profile whose ontology object carries no title falls back to the label of its package, so
     * that a sparse profile is still named rather than anonymous.
     */
    @Test
    public void readLabelFromProfilePackageWhenOntologyHasNoTitle() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
               xmlns:cim="http://iec.ch/TC57/CIM100#"
               xmlns:dcat="http://www.w3.org/ns/dcat#"
               xmlns:owl="http://www.w3.org/2002/07/owl#"
               xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xml:base ="http://example.org/test">

                <rdf:Description rdf:about="http://example.org/test#Ontology">
                    <dcat:keyword>TST</dcat:keyword>
                    <owl:versionIRI rdf:resource="http://example.org/MyCustom/Core/1/1"/>
                    <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
                </rdf:Description >
                <rdf:Description rdf:about="#Package_TestProfile">
                    <rdfs:label xml:lang="en">TestProfile</rdfs:label>
                    <rdf:type rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory"/>
                    <rdfs:comment rdf:parseType="Literal">Described on the package only.</rdfs:comment>
                </rdf:Description>
            </rdf:RDF>
            """;

        var graph = GraphFactory.createGraphMem();

        RDFParser.create()
            .source(new StringReader(rdfxml))
            .lang(org.apache.jena.riot.Lang.RDFXML)
            .checking(false)
            .parse(graph);

        var profile = CimProfile.wrap(graph);

        assertEquals("TestProfile", profile.getLabel());
        assertEquals("Described on the package only.", profile.getDescription());
        assertNull(profile.getIssued());
    }

}
