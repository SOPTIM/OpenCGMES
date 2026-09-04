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

public class TestCimProfile16 {


    /**
     * Test that the parser can parse a CIMXML document with a version declaration.
     * And that the version is correctly parsed.
     */
    @Test
    public void parseProfileOntologyHeader() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
             <rdf:RDF xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:xsd="http://www.w3.org/2001/XMLSchema#" xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#" xml:base="http://iec.ch/TC57/2013/CIM-schema-cim16" xmlns:entsoe="http://entsoe.eu/CIM/SchemaExtension/3/1#" >
                <rdf:Description rdf:about="#Package_MyCustomProfile">
                    <rdfs:label xml:lang="en">MyCustomProfile</rdfs:label>
                    <rdf:type rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory"/>
                    <rdfs:comment rdf:parseType="Literal">My custom comment.</rdfs:comment>
                </rdf:Description>
                <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion">
                    <rdfs:label xml:lang="en">MyCustomVersion</rdfs:label>
                    <rdfs:comment  rdf:parseType="Literal">My custom version details.</rdfs:comment>
                    <cims:stereotype>Entsoe</cims:stereotype>
                    <cims:belongsToCategory rdf:resource="#Package_MyCustomProfile"/>
                    <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion.baseURIcore">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:label xml:lang="en">baseURIcore</rdfs:label>
                    <rdfs:domain rdf:resource="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion"/>
                    <cims:dataType rdf:resource="#String"/>
                    <cims:multiplicity rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#M:1..1" />
                    <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/MyCustom/Core/1/1</cims:isFixed>
                    <rdfs:comment  rdf:parseType="Literal">Profile and version identifier.</rdfs:comment>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion.baseURIoperation">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:label xml:lang="en">baseURIoperation</rdfs:label>
                    <rdfs:domain rdf:resource="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion"/>
                    <cims:dataType rdf:resource="#String"/>
                    <cims:multiplicity rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#M:1..1" />
                    <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/MyCustom/Operation/1/1</cims:isFixed>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion.baseURIshortCircuit">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:label xml:lang="en">baseURIshortCircuit</rdfs:label>
                    <rdfs:domain rdf:resource="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion"/>
                    <cims:dataType rdf:resource="#String"/>
                    <cims:multiplicity rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#M:1..1" />
                    <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/MyCustom/ShortCircuit/1/1</cims:isFixed>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion.entsoeURIcore">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:label xml:lang="en">entsoeURIcore</rdfs:label>
                    <rdfs:domain rdf:resource="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion"/>
                    <cims:dataType rdf:resource="#String"/>
                    <cims:multiplicity rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#M:1..1" />
                    <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://entsoe.eu/CIM/MyCustomCore/2/2</cims:isFixed>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion.entsoeURIoperation">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:label xml:lang="en">entsoeURIoperation</rdfs:label>
                    <rdfs:domain rdf:resource="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion"/>
                    <cims:dataType rdf:resource="#String"/>
                    <cims:multiplicity rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#M:1..1" />
                    <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://entsoe.eu/CIM/MyCustomOperation/2/2</cims:isFixed>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion.entsoeURIshortCircuit">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:label xml:lang="en">entsoeURIshortCircuit</rdfs:label>
                    <rdfs:domain rdf:resource="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion"/>
                    <cims:dataType rdf:resource="#String"/>
                    <cims:multiplicity rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#M:1..1" />
                    <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://entsoe.eu/CIM/MyCustomShortCircuit/2/2</cims:isFixed>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion.shortName">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:label xml:lang="en">shortName</rdfs:label>
                    <rdfs:domain rdf:resource="http://entsoe.eu/CIM/SchemaExtension/3/1#MyCustomVersion"/>
                    <cims:dataType rdf:resource="#String"/>
                    <cims:multiplicity rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#M:1..1" />
                    <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">MYCUST</cims:isFixed>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
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

        assertFalse(ontology.isHeaderProfile());
        assertEquals(CimProfile16.CIM_NAMESPACE, ontology.getCimNamespace());

        assertEquals(6, ontology.getOwlVersionIris().size());
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://example.org/MyCustom/Core/1/1")));
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://example.org/MyCustom/Operation/1/1")));
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://example.org/MyCustom/ShortCircuit/1/1")));
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://entsoe.eu/CIM/MyCustomCore/2/2")));
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://entsoe.eu/CIM/MyCustomOperation/2/2")));
        assertTrue(ontology.getOwlVersionIris().stream()
                .anyMatch(n -> n.getURI().equals("http://entsoe.eu/CIM/MyCustomShortCircuit/2/2")));

        assertNull(ontology.getOwlVersionInfo());
        assertEquals("MYCUST", ontology.getDcatKeyword());
    }

    @Test
    public void parseProfileFileHeaderProfile() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
                <rdf:Description rdf:about="http://iec.ch/TC57/61970-552/ModelDescription#Package_FileHeaderProfile">
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
        assertEquals(CimProfile16.CIM_NAMESPACE, ontology.getCimNamespace());
        assertEquals("FileHeaderProfile", ontology.getLabel());
    }

    /**
     * Pre-2020 ENTSO-E profiles encode {@code cims:isFixed} values with the non-standard
     * XML attribute syntax {@code <cims:isFixed rdfs:Literal="value" />} rather than a typed
     * literal.  Jena parses this as a blank-node object with an {@code rdfs:Literal} triple.
     * Verify that {@link CimProfile16} recognises this format correctly.
     */
    @Test
    public void parseProfileOntologyHeader_2016RdfsLiteralFormat() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
                     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                     xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
              <rdf:Description rdf:about="http://entsoe.eu/Test#TestVersion.shortName">
                <rdfs:domain rdf:resource="http://entsoe.eu/Test#TestVersion"/>
                <cims:isFixed rdfs:Literal="TST" />
              </rdf:Description>
              <rdf:Description rdf:about="http://entsoe.eu/Test#TestVersion.entsoeURI">
                <rdfs:domain rdf:resource="http://entsoe.eu/Test#TestVersion"/>
                <cims:isFixed rdfs:Literal="http://example.org/TestProfile/1" />
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

        assertFalse(profile.isHeaderProfile());
        assertEquals(CimProfile16.CIM_NAMESPACE, profile.getCimNamespace());
        assertEquals("TST", profile.getDcatKeyword());
        assertEquals(1, profile.getOwlVersionIris().size());
        assertEquals("http://example.org/TestProfile/1",
                profile.getOwlVersionIris().iterator().next().getURI());

        // Nothing declares a profile package here, so there is no name to report.
        assertNull(profile.getProfilePackage());
        assertNull(profile.getLabel());
        assertNull(profile.getDescription());
    }

    /**
     * A CGMES 2.4.15 profile has no ontology object, so its label and description come from the
     * profile's package.  The package is reached from the version class through
     * {@code cims:belongsToCategory} rather than by its name.
     */
    @Test
    public void readProfileMetadata() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
                     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                     xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#"
                     xml:base="http://example.org/test">
              <rdf:Description rdf:about="#Package_TestProfile">
                <rdfs:label xml:lang="en">TestProfile</rdfs:label>
                <rdf:type rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory"/>
                <rdfs:comment rdf:parseType="Literal">A profile for exercising the metadata accessors.</rdfs:comment>
              </rdf:Description>
              <rdf:Description rdf:about="#Package_Grouping">
                <rdfs:label xml:lang="en">Grouping</rdfs:label>
                <rdf:type rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory"/>
              </rdf:Description>
              <rdf:Description rdf:about="http://example.org/ext#TestVersion">
                <rdfs:label xml:lang="en">TestVersion</rdfs:label>
                <cims:belongsToCategory rdf:resource="#Package_TestProfile"/>
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
              </rdf:Description>
              <rdf:Description rdf:about="http://example.org/ext#TestVersion.shortName">
                <rdfs:domain rdf:resource="http://example.org/ext#TestVersion"/>
                <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">TST</cims:isFixed>
              </rdf:Description>
              <rdf:Description rdf:about="http://example.org/ext#TestVersion.entsoeURI">
                <rdfs:domain rdf:resource="http://example.org/ext#TestVersion"/>
                <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/TestProfile/1</cims:isFixed>
              </rdf:Description>
              <rdf:Description rdf:about="http://example.org/ext#TestVersion.date">
                <rdfs:domain rdf:resource="http://example.org/ext#TestVersion"/>
                <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#date">2020-09-04</cims:isFixed>
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

        assertEquals("http://example.org/test#Package_TestProfile",
                profile.getProfilePackage().getURI());
        assertEquals("TestProfile", profile.getLabel());
        assertEquals("A profile for exercising the metadata accessors.", profile.getDescription());
        assertEquals("2020-09-04", profile.getIssued());
        assertNull(profile.getOntologyNode());

        var metadata = profile.getMetadata();

        assertEquals(CimProfile16.CIM_NAMESPACE, metadata.cimNamespace());
        assertFalse(metadata.headerProfile());
        assertEquals("TST", metadata.keyword());
        assertEquals("TestProfile", metadata.label());
        assertEquals("A profile for exercising the metadata accessors.", metadata.description());
        assertEquals(1, metadata.versionIris().size());
        assertNull(metadata.versionInfo());
        assertEquals("2020-09-04", metadata.issued());
    }
}
