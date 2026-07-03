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

package de.soptim.opencgmes.parser;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.graph.CimProfile17;
import de.soptim.opencgmes.cimxml.graph.CimProfile18;
import de.soptim.opencgmes.cimxml.parser.ReaderCIMXML_StAX_SR;
import de.soptim.opencgmes.cimxml.parser.system.StreamCimXmlToDatasetGraph;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.*;

public class TestParserCIMXMLConformity {

    /**
     * Test that the parser can parse a CIMXML document with a version declaration.
     * And that the version is correctly parsed.
     */
    @Test
    public void parseIEC61970_552Version() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?iec61970-552 version="2.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        assertEquals("version=\"2.0\"", streamRDF.getVersionOfIec61970_552());
    }

    /**
     * Test that the parser can parse a CIMXML document without a version declaration.
     */
    @Test
    public void parseWithoutIEC61970_552Version() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        assertNull(streamRDF.getVersionOfIec61970_552());
    }

    @Test
    public void parseCimNamespace17() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:cim="http://iec.ch/TC57/CIM100#">
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        assertEquals(CimProfile17.CIM_NAMESPACE, streamRDF.getCimNamespace());
    }

    @Test
    public void parseCimNamespace18() {
        final var rdfxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:cim="https://cim.ucaiug.io/ns#">
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        assertEquals(CimProfile18.CIM_NAMESPACE, streamRDF.getCimNamespace());
    }

    @Test
    public void parseFullModelHeaderAndContentInDifferentGraphs() {
        final var rdfxml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:eu="http://iec.ch/TC57/CIM100-European#">
             <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
               <md:Model.profile>http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0</md:Model.profile>
             </md:FullModel>
             <cim:MyEquipment rdf:ID="_f67fc354-9e39-4191-a456-67537399bc48">
               <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
             </cim:MyEquipment>
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();
        parser.read(new StringReader(rdfxml), streamRDF);

        assertTrue(streamRDF.getCimDatasetGraph().isFullModel());

        assertNotNull(streamRDF.getCimDatasetGraph().getModelHeader());
        var modelHeader = streamRDF.getCimDatasetGraph().getModelHeader();
        assertEquals("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6", modelHeader.getModel().toString());
        assertEquals(1, modelHeader.getProfiles().size());
        assertTrue(modelHeader.getProfiles().stream().map(Node::getLiteralLexicalForm).toList()
                .contains("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"));

        var graph = streamRDF.getCimDatasetGraph().getDefaultGraph();
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyEquipment")
        ));
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
                NodeFactory.createLiteralString("My Custom Equipment")
        ));
    }

    @Test
    public void parseDifferenceModelHeaderAndContentInDifferentGraphs() {
        final var rdfxml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF
                xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#"
                xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                xmlns:cim="http://iec.ch/TC57/CIM100#"
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <dm:DifferenceModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">

                <dm:preconditions rdf:parseType="Statements">

                    <!-- expect the following element to be present in the model
                         before and after applying the differences -->
                    <rdf:Description rdf:about="#_135c601e-bad4-4872-ba8f-b15baf91bd2f">
                        <cim:IdentifiedObject.name>Name of my element</cim:IdentifiedObject.name>
                    </rdf:Description>

                </dm:preconditions>

                <dm:forwardDifferences rdf:parseType="Statements">

                    <!-- add the following property to the model (delete + add = update) -->
                    <rdf:Description rdf:about="#_135c601e-bad4-4872-ba8f-b15baf91bd2f">
                        <cim:MyElement.MyProperty>B</cim:MyElement.MyProperty>
                    </rdf:Description>

                    <!-- add the following new resource to the model -->
                    <cim:MyElement rdf:about="#_2d1e4820-8858-49de-b441-5a03e7c40035">
                        <cim:IdentifiedObject.name>Name of new element to add</cim:IdentifiedObject.name>
                        <cim:MyElement.MyProperty>property of new element</cim:MyElement.MyProperty>
                    </cim:MyElement>

                </dm:forwardDifferences>

                <dm:reverseDifferences rdf:parseType="Statements">

                    <!-- remove the following property from the model (delete + add = update) -->
                    <rdf:Description rdf:about="#_135c601e-bad4-4872-ba8f-b15baf91bd2f">
                        <cim:MyElement.MyProperty>A</cim:MyElement.MyProperty>
                    </rdf:Description>

                    <!-- remove the following resource from the model -->
                    <cim:MyElement rdf:about="#_c9fe6664-fcf0-44e6-9d20-656538b68d1c">
                        <cim:IdentifiedObject.name>Name of new element to remove entirely</cim:IdentifiedObject.name>
                        <cim:MyElement.MyProperty>property of new element to remove</cim:MyElement.MyProperty>
                    </cim:MyElement>

                </dm:reverseDifferences>

             </dm:DifferenceModel>
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        assertTrue(streamRDF.getCimDatasetGraph().isDifferenceModel());
        assertNotNull(streamRDF.getCimDatasetGraph().getModelHeader());
        var modelHeader = streamRDF.getCimDatasetGraph().getModelHeader();

        assertEquals("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6", modelHeader.getModel().toString());

        var preconditions = streamRDF.getCimDatasetGraph().getPreconditions();
        assertNotNull(preconditions);
        assertEquals(1, preconditions.size());
        assertTrue(preconditions.contains(
                NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
                NodeFactory.createLiteralString("Name of my element")
        ));

        var forwardDifferences = streamRDF.getCimDatasetGraph().getForwardDifferences();
        assertNotNull(forwardDifferences);
        assertEquals(4, forwardDifferences.size());
        assertTrue(forwardDifferences.contains(
                NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
                NodeFactory.createLiteralString("B")
        ));
        assertTrue(forwardDifferences.contains(
                NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
        ));
        assertTrue(forwardDifferences.contains(
                NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
                NodeFactory.createLiteralString("Name of new element to add")
        ));
        assertTrue(forwardDifferences.contains(
                NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
                NodeFactory.createLiteralString("property of new element")
        ));

        var reverseDifferences = streamRDF.getCimDatasetGraph().getReverseDifferences();
        assertNotNull(reverseDifferences);
        assertEquals(4, reverseDifferences.size());
        assertTrue(reverseDifferences.contains(
                NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
                NodeFactory.createLiteralString("A")
        ));
        assertTrue(reverseDifferences.contains(
                NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
        ));
        assertTrue(reverseDifferences.contains(
                NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
                NodeFactory.createLiteralString("Name of new element to remove entirely")
        ));
        assertTrue(reverseDifferences.contains(
                NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
                NodeFactory.createLiteralString("property of new element to remove")
        ));
    }

    @Test
    public void writePrefixesToAllGraphInDifferenceModel() {
        final var rdfxml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF
                xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#"
                xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                xmlns:cim="http://iec.ch/TC57/CIM100#"
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <dm:DifferenceModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">

                <dm:preconditions rdf:parseType="Statements">
                </dm:preconditions>

                <dm:forwardDifferences rdf:parseType="Statements">
                </dm:forwardDifferences>

                <dm:reverseDifferences rdf:parseType="Statements">
                </dm:reverseDifferences>

             </dm:DifferenceModel>
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        assertTrue(streamRDF.getCimDatasetGraph().isDifferenceModel());
        assertNotNull(streamRDF.getCimDatasetGraph().getModelHeader());
        var modelHeader = streamRDF.getCimDatasetGraph().getModelHeader();

        assertEquals(4, modelHeader.getPrefixMapping().numPrefixes());
        assertEquals("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6", modelHeader.getModel().toString());

        var preconditions = streamRDF.getCimDatasetGraph().getPreconditions();
        assertNotNull(preconditions);
        assertTrue(preconditions.getPrefixMapping().samePrefixMappingAs(modelHeader.getPrefixMapping()));
        assertEquals(0, preconditions.size());

        var forwardDifferences = streamRDF.getCimDatasetGraph().getForwardDifferences();
        assertNotNull(forwardDifferences);
        assertTrue(forwardDifferences.getPrefixMapping().samePrefixMappingAs(modelHeader.getPrefixMapping()));
        assertEquals(0, forwardDifferences.size());

        var reverseDifferences = streamRDF.getCimDatasetGraph().getReverseDifferences();
        assertNotNull(reverseDifferences);
        assertTrue(reverseDifferences.getPrefixMapping().samePrefixMappingAs(modelHeader.getPrefixMapping()));
        assertEquals(0, reverseDifferences.size());
    }

    @Test
    public void replaceUnderscoresInRdfAboutAndRdfId() {
        final var rdfxml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:eu="http://iec.ch/TC57/CIM100-European#">
              <cim:MyEquipment rdf:ID="_f67fc354-9e39-4191-a456-67537399bc48">
                <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
              </cim:MyEquipment>
              <cim:MyEquipment rdf:about="#_f67fc354-9e39-4191-a456-67537399bc48">
                <cim:MyEquipment.MyReference rdf:resource="#_d597b77b-c8c4-4d88-883e-f516eedb913b" />
              </cim:MyEquipment>
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR();
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        var graph = streamRDF.getCimDatasetGraph().getDefaultGraph();
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyEquipment")
        ));
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
                NodeFactory.createLiteralString("My Custom Equipment")
        ));
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyEquipment.MyReference"),
                NodeFactory.createURI("urn:uuid:d597b77b-c8c4-4d88-883e-f516eedb913b")
        ));
    }

    @Test
    public void replaceUnderscoresInRdfAboutAndRdfIdFixingMissingDashesInUuids() {
        final var rdfxml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:eu="http://iec.ch/TC57/CIM100-European#">
              <cim:MyEquipment rdf:ID="_f67fc3549e394191a45667537399bc48">
                <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
              </cim:MyEquipment>
              <cim:MyEquipment rdf:about="#_f67fc3549e394191a45667537399bc48">
                <cim:MyEquipment.MyReference rdf:resource="#_d597b77bc8c44d88883ef516eedb913b" />
              </cim:MyEquipment>
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR(ErrorHandlerFactory.errorHandlerNoWarnings);
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        parser.read(new StringReader(rdfxml), streamRDF);

        var graph = streamRDF.getCimDatasetGraph().getDefaultGraph();
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyEquipment")
        ));
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
                NodeFactory.createLiteralString("My Custom Equipment")
        ));
        assertTrue(graph.contains(
                NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyEquipment.MyReference"),
                NodeFactory.createURI("urn:uuid:d597b77b-c8c4-4d88-883e-f516eedb913b")
        ));

    }

    @Test
    public void fullModelWithProfilesAndDatatypes() {
        final var rdfxmlFileHeaderProfile = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
                xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
                xmlns:cim="http://iec.ch/TC57/CIM100#"
                xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                xml:base="http://iec.ch/TC57/CIM100"
                xmlns:eu="http://iec.ch/TC57/CIM100-European#"
                xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#">
                <rdf:Description rdf:about="#Package_FileHeaderProfile">
                    <rdf:type rdf:resource="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory"/>
                </rdf:Description>
                <rdf:Description rdf:about="http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile">
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:domain rdf:resource="http://iec.ch/TC57/61970-552/ModelDescription/1#Model"/>
                    <cims:dataType rdf:resource="http://iec.ch/TC57/CIM100-European#URI"/>
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                 </rdf:Description>
                 <rdf:Description rdf:about="http://iec.ch/TC57/CIM100-European#URI">
                    <rdfs:label xml:lang="en">URI</rdfs:label>
                    <cims:stereotype>Primitive</cims:stereotype>
                    <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                 </rdf:Description>
            </rdf:RDF>
            """;

        final var rdfxmlCimProfile = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF
               xmlns:cim="http://iec.ch/TC57/CIM100#"
               xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
               xmlns:dcat="http://www.w3.org/ns/dcat#"
               xmlns:owl="http://www.w3.org/2002/07/owl#"
               xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xml:base ="http://iec.ch/TC57/CIM100">
                <!-- ······························································································· -->
                <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                    <dcat:keyword>MYCUST</dcat:keyword>
                    <owl:versionIRI rdf:resource="http://example.org/MyCustom/1/1"/>
                    <owl:versionInfo xml:lang ="en">1.1.0</owl:versionInfo>
                   <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
                </rdf:Description >
                <!-- ······························································································· -->
                <rdf:Description rdf:about="#ClassA">
                    <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                    <rdfs:subClassOf rdf:resource="#IdentifiedObject"/>
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#concrete"/>
                </rdf:Description>
                <!-- ······························································································· -->
                <rdf:Description rdf:about="#ClassA.floatProperty">
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:domain rdf:resource="#ClassA"/>
                    <cims:dataType rdf:resource="#Float"/>
                 </rdf:Description>
                <!-- ······························································································· -->
                <rdf:Description rdf:about="#ClassA.textProperty">
                    <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                    <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                    <rdfs:domain rdf:resource="#ClassA"/>
                    <cims:dataType rdf:resource="#String"/>
                </rdf:Description>
                <!-- ······························································································· -->
                <rdf:Description rdf:about="#Float">
                    <rdfs:label xml:lang="en">Float</rdfs:label>
                    <cims:stereotype>Primitive</cims:stereotype>
                    <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                </rdf:Description>
                <!-- ······························································································· -->
                <rdf:Description rdf:about="#String">
                    <rdfs:label xml:lang="en">String</rdfs:label>
                    <cims:stereotype>Primitive</cims:stereotype>
                    <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                </rdf:Description>
            </rdf:RDF>
            """;

        final var cimxmlInstanceData = """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF
                xmlns:cim="http://iec.ch/TC57/CIM100#"
                xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
               <md:Model.profile>http://example.org/MyCustom/1/1</md:Model.profile>
             </md:FullModel>
             <cim:ClassA rdf:ID="_594bb6e5-8da5-45c2-892e-59a648f2f862">
               <cim:ClassA.floatProperty>47.11</cim:ClassA.floatProperty>
               <cim:ClassA.textProperty>My Text A</cim:ClassA.textProperty>
             </cim:ClassA>
             <cim:ClassA rdf:ID="_49f26e7a-2ea9-4763-ba5f-560694d880fa">
               <cim:ClassA.floatProperty>08.15</cim:ClassA.floatProperty>
               <cim:ClassA.textProperty>My Text B</cim:ClassA.textProperty>
             </cim:ClassA>
            </rdf:RDF>
            """;

        final var parser = new ReaderCIMXML_StAX_SR();

        final var streamFileHeaderHeaderProfile = new StreamCimXmlToDatasetGraph();
        parser.read(new StringReader(rdfxmlFileHeaderProfile), streamFileHeaderHeaderProfile);
        var fileHeaderGraph = streamFileHeaderHeaderProfile.getCimDatasetGraph().getDefaultGraph();
        var fileHeaderProfile = CimProfile.wrap(fileHeaderGraph);

        final var streamRDFProfile = new StreamCimXmlToDatasetGraph();
        parser.read(new StringReader(rdfxmlCimProfile), streamRDFProfile);
        var profileGraph = streamRDFProfile.getCimDatasetGraph().getDefaultGraph();
        var profile = CimProfile.wrap(profileGraph);


        var registry = new CimProfileRegistryStd();
        registry.register(fileHeaderProfile);
        registry.register(profile);


        final var streamInstanceData = new StreamCimXmlToDatasetGraph();
        parser.read(new StringReader(cimxmlInstanceData), registry, streamInstanceData);

        var instanceGraph = streamInstanceData.getCimDatasetGraph().getBody();
        assertNotNull(instanceGraph);
        assertEquals(6, instanceGraph.size());

        assertTrue(instanceGraph.contains(
                NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA")
        ));
        assertTrue(instanceGraph.contains(
                NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.floatProperty"),
                NodeFactory.createLiteral("47.11", null, XSDDatatype.XSDfloat)
        ));
        assertTrue(instanceGraph.contains(
                NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.textProperty"),
                NodeFactory.createLiteralString("My Text A")
        ));
        assertTrue(instanceGraph.contains(
                NodeFactory.createURI("urn:uuid:49f26e7a-2ea9-4763-ba5f-560694d880fa"),
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA")
        ));
        assertTrue(instanceGraph.contains(
                NodeFactory.createURI("urn:uuid:49f26e7a-2ea9-4763-ba5f-560694d880fa"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.floatProperty"),
                NodeFactory.createLiteral("08.15", null,  XSDDatatype.XSDfloat)
        ));
        assertTrue(instanceGraph.contains(
                NodeFactory.createURI("urn:uuid:49f26e7a-2ea9-4763-ba5f-560694d880fa"),
                NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.textProperty"),
                NodeFactory.createLiteralString("My Text B")
        ));
    }
}