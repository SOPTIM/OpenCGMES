package de.soptim.opencgmes.cimxml.writer;

import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import javax.xml.stream.XMLStreamException;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.vocabulary.RDF;
import org.codehaus.stax2.XMLStreamWriter2;

public class SerializerCIMXML_StAX_SR {


  private static final String rdfUri = RDF.uri;
  private static final String cimModelDescriptionUri = "http://iec.ch/TC57/61970-552/ModelDescription/1#";
  private static final String differenceModelNamespaceUri = "http://iec.ch/TC57/61970-552/DifferenceModel/1#";
  private static final String xmlNS = "http://www.w3.org/XML/1998/namespace";
  private static final String baseUri = "urn:uuid:";

  private static final String cimxmlStandard = "iec61970-552";
  private static final String cimxmlVersionString = "version=\"2.0\"";

  private final XMLStreamWriter2 xmlStreamWriter;
  private final CimDatasetGraph cimDatasetGraph;
  private final PrefixMap prefixMap;

  public SerializerCIMXML_StAX_SR(XMLStreamWriter2 xmlStreamWriter, CimDatasetGraph cimDatasetGraph,
      PrefixMap prefixMap) {
    this.xmlStreamWriter = xmlStreamWriter;
    this.cimDatasetGraph = cimDatasetGraph;
    this.prefixMap = prefixMap == null ? cimDatasetGraph.prefixes() : prefixMap;
  }

  void serialize() throws XMLStreamException {
    for (var entry : prefixMap.getMapping().entrySet()) {
      xmlStreamWriter.setPrefix(entry.getKey(), entry.getValue());
    }
    if (cimDatasetGraph.isFullModel()) {
      serializeFullModel();
    } else if (cimDatasetGraph.isDifferenceModel()) {
      serializeDifferenceModel();
    } else {
      throw new RiotException(
          "Dataset must be either a FullModel or a DifferenceModel!"
      );
    }
  }

  private void serializeFullModel() throws XMLStreamException {
    xmlStreamWriter.writeStartDocument();
    xmlStreamWriter.writeProcessingInstruction(cimxmlStandard, cimxmlVersionString);
    writeDocumentElement();
    xmlStreamWriter.writeEndDocument();
  }

  // Section 7.2.3.3 Document element
  private void writeDocumentElement() throws XMLStreamException {
    xmlStreamWriter.writeStartElement(rdfUri, "RDF");
    xmlStreamWriter.writeNamespace("rdf", rdfUri);
    xmlStreamWriter.writeNamespace("cim", prefixMap.get("cim"));
    xmlStreamWriter.writeNamespace("md", cimModelDescriptionUri);
    xmlStreamWriter.writeAttribute(xmlNS, "base", baseUri);
    writeFullModelElement();
    var model = ModelFactory.createModelForGraph(cimDatasetGraph.getBody());
    ResIterator subjectIterator = model.listSubjects();
    while (subjectIterator.hasNext()) {
      writeDefinitionElement(subjectIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.4 Full-model element
  private void writeFullModelElement() throws XMLStreamException {
    xmlStreamWriter.writeStartElement(cimModelDescriptionUri, "FullModel");
    var modelHeader = cimDatasetGraph.getModelHeader();
    var model = ModelFactory.createModelForGraph(modelHeader);
    var fullModelNode = modelHeader.getModel();
    var fullModelResource = model.getResource(fullModelNode.getURI());
    var propertyIterator = fullModelResource.listProperties();
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.5 Definition element
  // Only supports rdf:about variant
  private void writeDefinitionElement(Resource subjectNode) throws XMLStreamException {
    var typeResource = subjectNode.getProperty(RDF.type).getResource();
    xmlStreamWriter.writeStartElement(typeResource.getNameSpace(), typeResource.getLocalName());
    xmlStreamWriter.writeAttribute(rdfUri, "about", replaceUrnUuidWithHash(subjectNode.getURI()));
    var propertyIterator = subjectNode.listProperties();
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.6 Description element
  private void writeDescriptionElement(Resource subjectNode) throws XMLStreamException {
    xmlStreamWriter.writeStartElement(rdfUri, "Description");
    xmlStreamWriter.writeAttribute(rdfUri, "about", replaceUrnUuidWithHash(subjectNode.getURI()));
    var propertyIterator = subjectNode.listProperties();
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.7 Compound element
  private void writeCompoundElement() throws XMLStreamException {

  }

  // Section 7.2.3.8 Literal-Property element
  private void writeLiteralPropertyElement(Statement property) throws XMLStreamException {
    xmlStreamWriter.writeStartElement(property.getPredicate().getNameSpace(),
        property.getPredicate().getLocalName());
    xmlStreamWriter.writeRaw(property.getLiteral().getValue().toString());
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.9 Compound-Property element
  private void writeCompoundPropertyElement() throws XMLStreamException {

  }

  // Section 7.2.3.10 Resource-Property element
  private void writeResourcePropertyElement(Statement property) throws XMLStreamException {
    xmlStreamWriter.writeStartElement(property.getPredicate().getNameSpace(),
        property.getPredicate().getLocalName());
    xmlStreamWriter.writeAttribute(rdfUri, "resource",
        replaceUrnUuidWithHash(property.getResource().getURI()));
    xmlStreamWriter.writeEndElement();
  }

  private void writeProperty(Statement property) throws XMLStreamException {
    if (property.getPredicate().equals(RDF.type)) {
      return;
    }
    if (property.getObject().isLiteral()) {
      writeLiteralPropertyElement(property);
    } else {
      writeResourcePropertyElement(property);
    }
  }

  private void serializeDifferenceModel() {

  }

  private static String replaceUrnUuidWithHash(String uri) {
    return uri.replace(baseUri, "#_");
  }
}
