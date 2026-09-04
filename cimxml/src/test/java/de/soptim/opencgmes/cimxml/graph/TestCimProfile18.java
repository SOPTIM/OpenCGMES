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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestCimProfile18 {

    @Test
    public void parseProfileFileHeaderProfile() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
              xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
              xmlns:cim="https://cim.ucaiug.io/ns#"
              xmlns:owl="http://www.w3.org/2002/07/owl#"
              xml:base="https://cim.ucaiug.io/ns" >
            <rdf:Description rdf:about="https://ap-voc.cim4.eu/DocumentHeader#Ontology">
                <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
                <owl:versionIRI rdf:resource="https://ap-voc.cim4.eu/DocumentHeader/2.3"/>
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
        assertEquals(CimProfile18.CIM_NAMESPACE, ontology.getCimNamespace());
    }

    /** CIM 18 profiles describe themselves the same way CIM 17 profiles do. */
    @Test
    public void readProfileMetadata() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
              xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
              xmlns:cim="https://cim.ucaiug.io/ns#"
              xmlns:dcat="http://www.w3.org/ns/dcat#"
              xmlns:dcterms="http://purl.org/dc/terms/"
              xmlns:owl="http://www.w3.org/2002/07/owl#"
              xml:base="https://cim.ucaiug.io/ns" >
            <rdf:Description rdf:about="http://example.org/test#Ontology">
                <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
                <dcat:keyword>TST</dcat:keyword>
                <owl:versionIRI rdf:resource="http://example.org/TestProfile/2.0"/>
                <owl:versionInfo xml:lang="en">2.0.0</owl:versionInfo>
                <dcterms:title xml:lang="en">Test Vocabulary</dcterms:title>
                <dcterms:description xml:lang="en">A vocabulary for exercising the metadata accessors.</dcterms:description>
            </rdf:Description>
            </rdf:RDF>
            """;

        var graph = GraphFactory.createGraphMem();

        RDFParser.create()
                .source(new StringReader(rdfxml))
                .lang(org.apache.jena.riot.Lang.RDFXML)
                .checking(false)
                .parse(graph);

        var metadata = CimProfile.wrap(graph).getMetadata();

        assertEquals(CimProfile18.CIM_NAMESPACE, metadata.cimNamespace());
        assertFalse(metadata.headerProfile());
        assertEquals("TST", metadata.keyword());
        assertEquals("Test Vocabulary", metadata.label());
        assertEquals("A vocabulary for exercising the metadata accessors.", metadata.description());
        assertEquals("2.0.0", metadata.versionInfo());
    }

}
