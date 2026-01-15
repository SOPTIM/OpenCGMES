package de.soptim.opencgmes.writer;

import de.soptim.opencgmes.cimxml.CimHeaderVocabulary;
import de.soptim.opencgmes.cimxml.sparql.core.LinkedCimDatasetGraph;
import de.soptim.opencgmes.cimxml.writer.WriterCIMXML_StAX_SR;
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

    final var writer = new WriterCIMXML_StAX_SR();
    var byteArrayOutputStream = new ByteArrayOutputStream();
    writer.write(byteArrayOutputStream, cimDatasetGraph, null, true);
//    assertEquals(byteArrayOutputStream.toString(), cimxmlInstanceData); TODO: Compare XML result
  }
}
