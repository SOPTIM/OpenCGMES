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

package de.soptim.opencgmes.cimxml.writer;

import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import javax.xml.stream.XMLStreamException;
import org.apache.jena.graph.Graph;
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
  private static final String about = "about";

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
    xmlStreamWriter.writeAttribute(rdfUri, about, fullModelResource.getURI());
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
    xmlStreamWriter.writeAttribute(rdfUri, about, replaceUrnUuidWithHash(subjectNode.getURI()));
    var propertyIterator = subjectNode.listProperties();
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.6 Description element
  private void writeDescriptionElement(Resource subjectNode) throws XMLStreamException {
    xmlStreamWriter.writeStartElement(rdfUri, "Description");
    xmlStreamWriter.writeAttribute(rdfUri, about, replaceUrnUuidWithHash(subjectNode.getURI()));
    var propertyIterator = subjectNode.listProperties();
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.7 Compound element
  private void writeCompoundElement(Resource subjectNode) throws XMLStreamException {
    var typeResource = subjectNode.getProperty(RDF.type).getResource();
    xmlStreamWriter.writeStartElement(typeResource.getNameSpace(), typeResource.getLocalName());
    var propertyIterator = subjectNode.listProperties();
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.8 Literal-Property element
  private void writeLiteralPropertyElement(Statement property) throws XMLStreamException {
    xmlStreamWriter.writeStartElement(property.getPredicate().getNameSpace(),
        property.getPredicate().getLocalName());
    xmlStreamWriter.writeCharacters(property.getLiteral().getValue().toString());
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.9 Compound-Property element
  private void writeCompoundPropertyElement(Statement property) throws XMLStreamException {
    xmlStreamWriter.writeStartElement(property.getPredicate().getNameSpace(),
        property.getPredicate().getLocalName());
    writeCompoundElement(property.getResource());
    xmlStreamWriter.writeEndElement();
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
    if (property.getObject().isURIResource()) {
      writeResourcePropertyElement(property);
    } else if (property.getObject().isLiteral()) {
      writeLiteralPropertyElement(property);
    } else { // isAnon
      writeCompoundPropertyElement(property);
    }
  }

  private void serializeDifferenceModel() throws XMLStreamException {
    xmlStreamWriter.writeStartDocument();
    xmlStreamWriter.writeProcessingInstruction(cimxmlStandard, cimxmlVersionString);
    xmlStreamWriter.writeStartElement(rdfUri, "RDF");
    xmlStreamWriter.writeNamespace("rdf", rdfUri);
    xmlStreamWriter.writeNamespace("cim", prefixMap.get("cim"));
    xmlStreamWriter.writeNamespace("dm", differenceModelNamespaceUri);
    xmlStreamWriter.writeAttribute(xmlNS, "base", baseUri);
    writeDifferenceModelElement();
    xmlStreamWriter.writeEndElement();
    xmlStreamWriter.writeEndDocument();
  }

  private void writeDifferenceModelElement() throws XMLStreamException {
    xmlStreamWriter.writeStartElement(differenceModelNamespaceUri, "DifferenceModel");
    var modelHeader = cimDatasetGraph.getModelHeader();
    var model = ModelFactory.createModelForGraph(modelHeader);
    var differenceModelNode = modelHeader.getModel();
    var differenceModelResource = model.getResource(differenceModelNode.getURI());
    xmlStreamWriter.writeAttribute(rdfUri, about, differenceModelResource.getURI());
    var propertyIterator = differenceModelResource.listProperties();
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    writeDifferenceModelSubgraph("preconditions", cimDatasetGraph.getPreconditions());
    writeDifferenceModelSubgraph("forwardDifferences", cimDatasetGraph.getForwardDifferences());
    writeDifferenceModelSubgraph("reverseDifferences", cimDatasetGraph.getReverseDifferences());
    xmlStreamWriter.writeEndElement();
  }

  private void writeDifferenceModelSubgraph(String graphName, Graph graph)
      throws XMLStreamException {
    xmlStreamWriter.writeStartElement(differenceModelNamespaceUri, graphName);
    xmlStreamWriter.writeAttribute(rdfUri, "parseType", "Statements");
    var model = ModelFactory.createModelForGraph(graph);
    ResIterator subjectIterator = model.listSubjects();
    while (subjectIterator.hasNext()) {
      var currentSubject = subjectIterator.next();
      if (currentSubject.hasProperty(RDF.type)) {
        writeDefinitionElement(currentSubject);
      } else {
        writeDescriptionElement(currentSubject);
      }
    }
    xmlStreamWriter.writeEndElement();
  }

  private static String replaceUrnUuidWithHash(String uri) {
    return uri.replace(baseUri, "#_");
  }
}
