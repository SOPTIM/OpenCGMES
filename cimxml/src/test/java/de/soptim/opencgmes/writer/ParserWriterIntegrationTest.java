package de.soptim.opencgmes.writer;

import static org.junit.Assert.assertEquals;

import de.soptim.opencgmes.cimxml.parser.ReaderCIMXML_StAX_SR;
import de.soptim.opencgmes.cimxml.parser.system.StreamCIMXMLToDatasetGraph;
import de.soptim.opencgmes.cimxml.writer.WriterCIMXML_StAX_SR;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.Test;

public class ParserWriterIntegrationTest {

  @Test
  public void fullModel_ParsingAndWritingYieldsSameResult() {
    final var cimxmlInstanceData = """
        <?xml version='1.0' encoding='UTF-8'?>
        <?iec61970-552 version="2.0"?><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xml:base="urn:uuid:">
          <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
            <md:Model.profile>http://example.org/MyCustom/1/1</md:Model.profile>
          </md:FullModel>
          <cim:ClassA rdf:about="#_49f26e7a-2ea9-4763-ba5f-560694d880fa">
            <cim:ClassA.floatProperty>8.15</cim:ClassA.floatProperty>
            <cim:ClassA.textProperty>My Text B</cim:ClassA.textProperty>
          </cim:ClassA>
          <cim:ClassA rdf:about="#_594bb6e5-8da5-45c2-892e-59a648f2f862">
            <cim:ClassA.floatProperty>47.11</cim:ClassA.floatProperty>
            <cim:ClassA.textProperty>My Text A</cim:ClassA.textProperty>
            <cim:ClassA.uriProperty rdf:resource="#_49f26e7a-2ea9-4763-ba5f-560694d880fa"/>
          </cim:ClassA>
        </rdf:RDF>""";

    final var parser = new ReaderCIMXML_StAX_SR();
    final var streamRDF = new StreamCIMXMLToDatasetGraph();
    parser.read(new StringReader(cimxmlInstanceData), streamRDF);

    var cimDatasetGraph = streamRDF.getCIMDatasetGraph();

    final var writer = new WriterCIMXML_StAX_SR();
    var stringWriter = new StringWriter();
    writer.write(stringWriter, cimDatasetGraph, null, true);
    assertEquals(cimxmlInstanceData, stringWriter.toString());
  }

  @Test
  public void differenceModel_ParsingAndWritingYieldsSameResult() {
    final var cimxmlInstanceData = """
        <?xml version='1.0' encoding='UTF-8'?>
        <?iec61970-552 version="2.0"?><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#" xml:base="urn:uuid:">
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

    final var parser = new ReaderCIMXML_StAX_SR();
    final var streamRDF = new StreamCIMXMLToDatasetGraph();
    parser.read(new StringReader(cimxmlInstanceData), streamRDF);

    var cimDatasetGraph = streamRDF.getCIMDatasetGraph();

    final var writer = new WriterCIMXML_StAX_SR();
    var stringWriter = new StringWriter();
    writer.write(stringWriter, cimDatasetGraph, null, true);
    assertEquals(cimxmlInstanceData, stringWriter.toString());
  }
}
