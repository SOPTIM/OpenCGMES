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

package de.soptim.opencgmes.cimxml.sparql.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import java.io.StringReader;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.junit.Test;

public class TestCimDatasetGraph {

  @Test
  public void fullModelToSingleGraph() {
    final var rdfxml = """
        <?xml version="1.0" encoding="utf-8"?>
        <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:eu="http://iec.ch/TC57/CIM100-European#">
         <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
           <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
         </md:FullModel>
         <cim:MyEquipment rdf:ID="_f67fc354-9e39-4191-a456-67537399bc48">
           <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
         </cim:MyEquipment>
        </rdf:RDF>
        """;

    var model = new CimXmlParser(ErrorHandlerFactory.errorHandlerNoWarnings)
        .parseCimModel(new StringReader(rdfxml));

    var fullGraph = model.fullModelToSingleGraph();
    assertNotNull(fullGraph);
    assertEquals(4, fullGraph.size());

    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),
        NodeFactory.createLiteralString("http://soptim.de/CIM/MyProfile/1.1")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyEquipment")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:f67fc354-9e39-4191-a456-67537399bc48"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("My Custom Equipment")
    ));
  }

  @Test
  public void differenceModelToFullModel() {
    final var rdfXmlFullModel = """
        <?xml version="1.0" encoding="utf-8"?>
        <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:eu="http://iec.ch/TC57/CIM100-European#">
         <md:FullModel rdf:about="urn:uuid:d4336345-ad68-4566-afab-d9798ec5ca86">
           <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
         </md:FullModel>
         <cim:MyElement rdf:ID="_135c601e-bad4-4872-ba8f-b15baf91bd2f">
           <cim:IdentifiedObject.name>Name of my element</cim:IdentifiedObject.name>
           <cim:MyElement.MyProperty>A</cim:MyElement.MyProperty>
         </cim:MyElement>
         <cim:MyElement rdf:ID="_c9fe6664-fcf0-44e6-9d20-656538b68d1c">
           <cim:IdentifiedObject.name>Name of new element to remove entirely</cim:IdentifiedObject.name>
           <cim:MyElement.MyProperty>property of new element to remove</cim:MyElement.MyProperty>
         </cim:MyElement>
         <cim:MyElement rdf:ID="_5a70f6b8-8c77-41f9-9793-6fe5bd67b756">
           <cim:IdentifiedObject.name>Name of element to remain</cim:IdentifiedObject.name>
           <cim:MyElement.MyProperty>property of new element to remain</cim:MyElement.MyProperty>
         </cim:MyElement>
        </rdf:RDF>
        """;

    final var rdfxmlDifferenceModel = """
        <?xml version="1.0" encoding="utf-8"?>
        <rdf:RDF
            xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#"
            xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
            xmlns:cim="http://iec.ch/TC57/CIM100#"
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
         <dm:DifferenceModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
            <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
            <md:Model.Supersedes>urn:uuid:d4336345-ad68-4566-afab-d9798ec5ca86</md:Model.Supersedes>
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

    var predecessorFullModel = new CimXmlParser(ErrorHandlerFactory.errorHandlerNoWarnings)
        .parseCimModel(new StringReader(rdfXmlFullModel));
    var differenceModel = new CimXmlParser(ErrorHandlerFactory.errorHandlerNoWarnings)
        .parseCimModel(new StringReader(rdfxmlDifferenceModel));

    var fullGraph = differenceModel.differenceModelToFullModel(predecessorFullModel);
    assertNotNull(fullGraph);
    assertEquals(9, fullGraph.size());
    // the element to remain unchanged
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:5a70f6b8-8c77-41f9-9793-6fe5bd67b756"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:5a70f6b8-8c77-41f9-9793-6fe5bd67b756"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("Name of element to remain")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:5a70f6b8-8c77-41f9-9793-6fe5bd67b756"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("property of new element to remain")
    ));

    // the unchanged properties
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("Name of my element")
    ));

    // the updated property of the unchanged element
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("B")
    ));

    // the newly added element
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("Name of new element to add")
    ));
    assertTrue(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("property of new element")
    ));

    // the removed element must not be present
    assertFalse(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
    ));
    assertFalse(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("Name of new element to remove entirely")
    ));
    assertFalse(fullGraph.contains(
        NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("property of new element to remove")
    ));
  }
}