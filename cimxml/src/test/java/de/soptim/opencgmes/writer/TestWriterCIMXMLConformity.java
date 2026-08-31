/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.writer;

import static org.junit.Assert.assertEquals;

import de.soptim.opencgmes.cimxml.CimHeaderVocabulary;
import de.soptim.opencgmes.cimxml.sparql.core.LinkedCimDatasetGraph;
import de.soptim.opencgmes.cimxml.writer.WriterCimXmlStaxSr;
import java.io.ByteArrayOutputStream;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Test;

public class TestWriterCIMXMLConformity {

  @Test
  public void writeFullModelGraph() {
    var graph = GraphFactory.createDefaultGraph();
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA")
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.floatProperty"),
        NodeFactory.createLiteral("47.11", null, XSDDatatype.XSDfloat)
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.textProperty"),
        NodeFactory.createLiteralString("My Text A")
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:49f26e7a-2ea9-4763-ba5f-560694d880fa"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA")
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:49f26e7a-2ea9-4763-ba5f-560694d880fa"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.floatProperty"),
        NodeFactory.createLiteral("08.15", null, XSDDatatype.XSDfloat)
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:49f26e7a-2ea9-4763-ba5f-560694d880fa"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.textProperty"),
        NodeFactory.createLiteralString("My Text B")
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.uriProperty"),
        NodeFactory.createURI("urn:uuid:49f26e7a-2ea9-4763-ba5f-560694d880fa")
    );
    var modelHeaderGraph = GraphFactory.createDefaultGraph();
    modelHeaderGraph.add(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),
        NodeFactory.createLiteralString("http://example.org/MyCustom/1/1")
    );
    modelHeaderGraph.add(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")
    );
    var cimDatasetGraph = new LinkedCimDatasetGraph(graph);
    cimDatasetGraph.addGraph(CimHeaderVocabulary.TYPE_FULL_MODEL, modelHeaderGraph);
    PrefixMap prefixes = cimDatasetGraph.prefixes();
    prefixes.add("cim", "http://iec.ch/TC57/CIM100#");
    prefixes.add("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
    prefixes.add("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
    modelHeaderGraph.getPrefixMapping().setNsPrefixes(prefixes.getMapping());

    final var cimxmlInstanceData = """
        <?xml version='1.0' encoding='UTF-8'?>
        <?iec61970-552 version="2.0"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xml:base="urn:uuid:">
          <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
            <md:Model.profile>http://example.org/MyCustom/1/1</md:Model.profile>
          </md:FullModel>
          <cim:ClassA rdf:about="#_49f26e7a-2ea9-4763-ba5f-560694d880fa">
            <cim:ClassA.floatProperty>08.15</cim:ClassA.floatProperty>
            <cim:ClassA.textProperty>My Text B</cim:ClassA.textProperty>
          </cim:ClassA>
          <cim:ClassA rdf:about="#_594bb6e5-8da5-45c2-892e-59a648f2f862">
            <cim:ClassA.floatProperty>47.11</cim:ClassA.floatProperty>
            <cim:ClassA.textProperty>My Text A</cim:ClassA.textProperty>
            <cim:ClassA.uriProperty rdf:resource="#_49f26e7a-2ea9-4763-ba5f-560694d880fa"/>
          </cim:ClassA>
        </rdf:RDF>""";

    final var writer = new WriterCimXmlStaxSr();
    var byteArrayOutputStream = new ByteArrayOutputStream();
    writer.write(byteArrayOutputStream, cimDatasetGraph, null, true);
    assertEquals(cimxmlInstanceData, byteArrayOutputStream.toString());
  }

  @Test
  public void writeFullModelGraph_withCompoundProperty() {
    var graph = GraphFactory.createDefaultGraph();
    var compoundNode = NodeFactory.createBlankNode();
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA")
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.compoundProperty"),
        compoundNode
    );
    graph.add(
        compoundNode,
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ExampleCompoundType")
    );
    graph.add(
        compoundNode,
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ExampleCompoundType.textProperty"),
        NodeFactory.createLiteralString("My Text A")
    );
    graph.add(
        compoundNode,
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ExampleCompoundType.intProperty"),
        NodeFactory.createLiteral("1234", null, XSDDatatype.XSDinteger)
    );
    var modelHeaderGraph = GraphFactory.createDefaultGraph();
    modelHeaderGraph.add(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),
        NodeFactory.createLiteralString("http://example.org/MyCustom/1/1")
    );
    modelHeaderGraph.add(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")
    );
    var cimDatasetGraph = new LinkedCimDatasetGraph(graph);
    cimDatasetGraph.addGraph(CimHeaderVocabulary.TYPE_FULL_MODEL, modelHeaderGraph);
    PrefixMap prefixes = cimDatasetGraph.prefixes();
    prefixes.add("cim", "http://iec.ch/TC57/CIM100#");
    prefixes.add("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
    prefixes.add("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
    modelHeaderGraph.getPrefixMapping().setNsPrefixes(prefixes.getMapping());

    final var cimxmlInstanceData = """
        <?xml version='1.0' encoding='UTF-8'?>
        <?iec61970-552 version="2.0"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xml:base="urn:uuid:">
          <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
            <md:Model.profile>http://example.org/MyCustom/1/1</md:Model.profile>
          </md:FullModel>
          <cim:ClassA rdf:about="#_594bb6e5-8da5-45c2-892e-59a648f2f862">
            <cim:ClassA.compoundProperty>
              <cim:ExampleCompoundType>
                <cim:ExampleCompoundType.intProperty>1234</cim:ExampleCompoundType.intProperty>
                <cim:ExampleCompoundType.textProperty>My Text A</cim:ExampleCompoundType.textProperty>
              </cim:ExampleCompoundType>
            </cim:ClassA.compoundProperty>
          </cim:ClassA>
        </rdf:RDF>""";

    final var writer = new WriterCimXmlStaxSr();
    var byteArrayOutputStream = new ByteArrayOutputStream();
    writer.write(byteArrayOutputStream, cimDatasetGraph, null, true);
    assertEquals(cimxmlInstanceData, byteArrayOutputStream.toString());
  }

  @Test
  public void writeFullModelGraph_escapesXMLEntities() {
    var graph = GraphFactory.createDefaultGraph();
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA")
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.textProperty"),
        NodeFactory.createLiteralString("3 < 5 & 6 > 4")
    );
    graph.add(
        NodeFactory.createURI("urn:uuid:594bb6e5-8da5-45c2-892e-59a648f2f862"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.uriProperty"),
        NodeFactory.createURI("http://example.com/ha\"sCha'rsNee<dingEsc&ape")
    );
    var modelHeaderGraph = GraphFactory.createDefaultGraph();
    modelHeaderGraph.add(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),
        NodeFactory.createLiteralString("http://example.org/MyCustom/1/1")
    );
    modelHeaderGraph.add(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")
    );
    var cimDatasetGraph = new LinkedCimDatasetGraph(graph);
    cimDatasetGraph.addGraph(CimHeaderVocabulary.TYPE_FULL_MODEL, modelHeaderGraph);
    PrefixMap prefixes = cimDatasetGraph.prefixes();
    prefixes.add("cim", "http://iec.ch/TC57/CIM100#");
    prefixes.add("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
    prefixes.add("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
    modelHeaderGraph.getPrefixMapping().setNsPrefixes(prefixes.getMapping());

    final var cimxmlInstanceData = """
        <?xml version='1.0' encoding='UTF-8'?>
        <?iec61970-552 version="2.0"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xml:base="urn:uuid:">
          <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
            <md:Model.profile>http://example.org/MyCustom/1/1</md:Model.profile>
          </md:FullModel>
          <cim:ClassA rdf:about="#_594bb6e5-8da5-45c2-892e-59a648f2f862">
            <cim:ClassA.textProperty>3 &lt; 5 &amp; 6 > 4</cim:ClassA.textProperty>
            <cim:ClassA.uriProperty rdf:resource="http://example.com/ha&quot;sCha&apos;rsNee&lt;dingEsc&amp;ape"/>
          </cim:ClassA>
        </rdf:RDF>""";

    final var writer = new WriterCimXmlStaxSr();
    var byteArrayOutputStream = new ByteArrayOutputStream();
    writer.write(byteArrayOutputStream, cimDatasetGraph, null, true);
    assertEquals(cimxmlInstanceData, byteArrayOutputStream.toString());
  }

  @Test
  public void writeDifferenceModelGraph() {
    var preconditions = GraphFactory.createDefaultGraph();
    preconditions.add(
        NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("Name of my element")
    );
    var forwardDifferences = GraphFactory.createDefaultGraph();
    forwardDifferences.add(
        NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("B")
    );
    forwardDifferences.add(
        NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
    );
    forwardDifferences.add(
        NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("Name of new element to add")
    );
    forwardDifferences.add(
        NodeFactory.createURI("urn:uuid:2d1e4820-8858-49de-b441-5a03e7c40035"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("property of new element")
    );
    var reverseDifferences = GraphFactory.createDefaultGraph();
    reverseDifferences.add(
        NodeFactory.createURI("urn:uuid:135c601e-bad4-4872-ba8f-b15baf91bd2f"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("A")
    );
    reverseDifferences.add(
        NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement")
    );
    reverseDifferences.add(
        NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name"),
        NodeFactory.createLiteralString("Name of new element to remove entirely")
    );
    reverseDifferences.add(
        NodeFactory.createURI("urn:uuid:c9fe6664-fcf0-44e6-9d20-656538b68d1c"),
        NodeFactory.createURI("http://iec.ch/TC57/CIM100#MyElement.MyProperty"),
        NodeFactory.createLiteralString("property of new element to remove")
    );
    var modelHeaderGraph = GraphFactory.createDefaultGraph();
    modelHeaderGraph.add(
        NodeFactory.createURI("urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6"),
        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
        NodeFactory.createURI("http://iec.ch/TC57/61970-552/DifferenceModel/1#DifferenceModel")
    );
    var cimDatasetGraph = new LinkedCimDatasetGraph();
    cimDatasetGraph.addGraph(CimHeaderVocabulary.TYPE_DIFFERENCE_MODEL, modelHeaderGraph);
    cimDatasetGraph.addGraph(CimHeaderVocabulary.GRAPH_FORWARD_DIFFERENCES, forwardDifferences);
    cimDatasetGraph.addGraph(CimHeaderVocabulary.GRAPH_REVERSE_DIFFERENCES, reverseDifferences);
    cimDatasetGraph.addGraph(CimHeaderVocabulary.GRAPH_PRECONDITIONS, preconditions);
    PrefixMap prefixes = cimDatasetGraph.prefixes();
    prefixes.add("cim", "http://iec.ch/TC57/CIM100#");
    prefixes.add("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
    prefixes.add("dm", "http://iec.ch/TC57/61970-552/DifferenceModel/1#");
    prefixes.add("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
    modelHeaderGraph.getPrefixMapping().setNsPrefixes(prefixes.getMapping());

    final var cimxmlInstanceData = """
        <?xml version='1.0' encoding='UTF-8'?>
        <?iec61970-552 version="2.0"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#" xml:base="urn:uuid:">
          <dm:DifferenceModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
            <dm:preconditions rdf:parseType="Statements">
              <rdf:Description rdf:about="#_135c601e-bad4-4872-ba8f-b15baf91bd2f">
                <cim:IdentifiedObject.name>Name of my element</cim:IdentifiedObject.name>
              </rdf:Description>
            </dm:preconditions>
            <dm:forwardDifferences rdf:parseType="Statements">
              <cim:MyElement rdf:about="#_2d1e4820-8858-49de-b441-5a03e7c40035">
                <cim:IdentifiedObject.name>Name of new element to add</cim:IdentifiedObject.name>
                <cim:MyElement.MyProperty>property of new element</cim:MyElement.MyProperty>
              </cim:MyElement>
              <rdf:Description rdf:about="#_135c601e-bad4-4872-ba8f-b15baf91bd2f">
                <cim:MyElement.MyProperty>B</cim:MyElement.MyProperty>
              </rdf:Description>
            </dm:forwardDifferences>
            <dm:reverseDifferences rdf:parseType="Statements">
              <cim:MyElement rdf:about="#_c9fe6664-fcf0-44e6-9d20-656538b68d1c">
                <cim:IdentifiedObject.name>Name of new element to remove entirely</cim:IdentifiedObject.name>
                <cim:MyElement.MyProperty>property of new element to remove</cim:MyElement.MyProperty>
              </cim:MyElement>
              <rdf:Description rdf:about="#_135c601e-bad4-4872-ba8f-b15baf91bd2f">
                <cim:MyElement.MyProperty>A</cim:MyElement.MyProperty>
              </rdf:Description>
            </dm:reverseDifferences>
          </dm:DifferenceModel>
        </rdf:RDF>""";

    final var writer = new WriterCimXmlStaxSr();
    var byteArrayOutputStream = new ByteArrayOutputStream();
    writer.write(byteArrayOutputStream, cimDatasetGraph, null, true);
    assertEquals(cimxmlInstanceData, byteArrayOutputStream.toString());
  }
}
