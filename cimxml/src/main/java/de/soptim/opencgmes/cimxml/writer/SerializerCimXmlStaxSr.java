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
import java.util.Comparator;
import java.util.Map;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.vocabulary.RDF;

class SerializerCimXmlStaxSr {

  private static final String rdfUri = RDF.uri;
  private static final String cimModelDescriptionUri = "http://iec.ch/TC57/61970-552/ModelDescription/1#";
  private static final String differenceModelNamespaceUri = "http://iec.ch/TC57/61970-552/DifferenceModel/1#";
  private static final String xmlNS = "http://www.w3.org/XML/1998/namespace";
  private static final String baseUri = "urn:uuid:";

  private static final String cimxmlStandard = "iec61970-552";
  private static final String cimxmlVersionString = "version=\"2.0\"";
  private static final String about = "about";

  private final XMLStreamWriter xmlStreamWriter;
  private final CimDatasetGraph cimDatasetGraph;
  private final Map<String, String> prefixMap;
  private final boolean sorted;

  private boolean isDifferenceModel;
  private Graph currentGraph;

  private enum PropertyType {
    LITERAL_PROPERTY,
    COMPOUND_PROPERTY,
    RESOURCE_PROPERTY
  }

  SerializerCimXmlStaxSr(XMLStreamWriter xmlStreamWriter,
      CimDatasetGraph cimDatasetGraph,
      PrefixMap prefixMap, boolean sorted) {
    this.xmlStreamWriter = xmlStreamWriter;
    this.cimDatasetGraph = cimDatasetGraph;
    this.prefixMap =
        prefixMap == null ? cimDatasetGraph.prefixes().getMapping() : prefixMap.getMapping();
    this.sorted = sorted;
  }

  void serialize() throws XMLStreamException {
    if (cimDatasetGraph.isFullModel()) {
      isDifferenceModel = false;
    } else if (cimDatasetGraph.isDifferenceModel()) {
      isDifferenceModel = true;
    } else {
      throw new RiotException(
          "Dataset must be either a FullModel or a DifferenceModel!"
      );
    }
    verifyNamespacesAndSetPrefixes();
    serializeModel();
  }

  private void verifyNamespacesAndSetPrefixes() throws XMLStreamException {
    if (!prefixMap.getOrDefault("rdf", "").equals(rdfUri)) {
      throw new RiotException("The rdf prefix must be set correctly!");
    }
    if (!prefixMap.getOrDefault("md", "").equals(cimModelDescriptionUri)) {
      throw new RiotException("The md prefix must be set correctly!");
    }
    if (isDifferenceModel && !prefixMap.getOrDefault("dm", "")
        .equals(differenceModelNamespaceUri)) {
      throw new RiotException("The dm prefix must be set correctly in a DifferenceModel!");
    }
    if (!prefixMap.containsKey("cim")) {
      throw new RiotException("The cim prefix must be set!");
    }
    for (var entry : prefixMap.entrySet()) {
      xmlStreamWriter.setPrefix(entry.getKey(), entry.getValue());
    }
  }

  private void serializeModel() throws XMLStreamException {
    xmlStreamWriter.writeStartDocument();
    xmlStreamWriter.writeProcessingInstruction(cimxmlStandard, cimxmlVersionString);
    writeDocumentElement();
    xmlStreamWriter.writeEndDocument();
  }

  // Section 7.2.3.3 Document element
  private void writeDocumentElement() throws XMLStreamException {
    xmlStreamWriter.writeStartElement(rdfUri, "RDF");
    for (var entry : prefixMap.entrySet()) {
      xmlStreamWriter.writeNamespace(entry.getKey(), entry.getValue());
    }
    xmlStreamWriter.writeAttribute(xmlNS, "base", baseUri);
    if (isDifferenceModel) {
      writeDifferenceModelElement();
    } else {
      writeFullModelElement();
      currentGraph = cimDatasetGraph.getBody();
      writeDefinitionElements();
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.4 Full-model element
  private void writeFullModelElement() throws XMLStreamException {
    xmlStreamWriter.writeStartElement(cimModelDescriptionUri, "FullModel");
    var modelHeader = cimDatasetGraph.getModelHeader();
    currentGraph = modelHeader;
    var fullModelNode = modelHeader.getModel();
    xmlStreamWriter.writeAttribute(rdfUri, about, fullModelNode.getURI());
    writeProperties(fullModelNode);
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.5 Definition element
  // Only supports rdf:about variant
  private void writeDefinitionElement(Triple typeTriple) {
    try {
      var typeNode = typeTriple.getObject();
      var subjectNode = typeTriple.getSubject();
      xmlStreamWriter.writeStartElement(typeNode.getNameSpace(), typeNode.getLocalName());
      xmlStreamWriter.writeAttribute(rdfUri, about, replaceUrnUuidWithHash(subjectNode.getURI()));
      writeProperties(subjectNode);
      xmlStreamWriter.writeEndElement();
    } catch (XMLStreamException e) {
      throw new RiotException(e);
    }
  }

  // Section 7.2.3.6 Description element
  private void writeDescriptionElement(Node subjectNode) {
    try {
      xmlStreamWriter.writeStartElement(rdfUri, "Description");
      xmlStreamWriter.writeAttribute(rdfUri, about, replaceUrnUuidWithHash(subjectNode.getURI()));
      writeProperties(subjectNode);
      xmlStreamWriter.writeEndElement();
    } catch (XMLStreamException e) {
      throw new RiotException(e);
    }
  }

  // Section 7.2.3.7 Compound element
  private void writeCompoundElement(Node subjectNode) throws XMLStreamException {
    var typeTripleOpt = currentGraph.stream(subjectNode, RDF.type.asNode(), Node.ANY).findFirst();
    if (typeTripleOpt.isPresent()) {
      var typeTriple = typeTripleOpt.get();
      var typeNode = typeTriple.getObject();
      xmlStreamWriter.writeStartElement(typeNode.getNameSpace(), typeNode.getLocalName());
      writeProperties(subjectNode);
      xmlStreamWriter.writeEndElement();
    } else {
      throw new RiotException("A compound element is missing a type triple!");
    }
  }

  // Section 7.2.3.8-10 Property element
  private void writeProperty(Triple propertyTriple) {
    try {
      if (propertyTriple.getPredicate().equals(RDF.type.asNode())) {
        return;
      }
      xmlStreamWriter.writeStartElement(propertyTriple.getPredicate().getNameSpace(),
          propertyTriple.getPredicate().getLocalName());
      switch (getPropertyType(propertyTriple.getObject())) {
        case LITERAL_PROPERTY -> xmlStreamWriter.writeCharacters(
            propertyTriple.getObject()
                .getLiteralLexicalForm()); // Section 7.2.3.8 Literal-Property element
        case COMPOUND_PROPERTY -> writeCompoundElement(
            propertyTriple.getObject()); // Section 7.2.3.9 Compound-Property element
        case RESOURCE_PROPERTY -> xmlStreamWriter.writeAttribute(rdfUri, "resource",
            replaceUrnUuidWithHash(
                propertyTriple.getObject().getURI())); // Section 7.2.3.10 Resource-Property element
        default -> throw new RiotException(
            "Unexpected property type: " + getPropertyType(propertyTriple.getObject()));
      }
      xmlStreamWriter.writeEndElement();
    } catch (XMLStreamException e) {
      throw new RiotException(e);
    }
  }

  private PropertyType getPropertyType(Node propertyObject) {
    if (propertyObject.isURI()) {
      return PropertyType.RESOURCE_PROPERTY;
    } else if (propertyObject.isLiteral()) {
      return PropertyType.LITERAL_PROPERTY;
    } else { // isBlank
      return PropertyType.COMPOUND_PROPERTY;
    }
  }

  private void writeDifferenceModelElement() throws XMLStreamException {
    xmlStreamWriter.writeStartElement(differenceModelNamespaceUri, "DifferenceModel");
    var modelHeader = cimDatasetGraph.getModelHeader();
    currentGraph = modelHeader;
    var differenceModelNode = modelHeader.getModel();
    xmlStreamWriter.writeAttribute(rdfUri, about, differenceModelNode.getURI());
    writeProperties(differenceModelNode);
    writeDifferenceModelSubgraph("preconditions", cimDatasetGraph.getPreconditions());
    writeDifferenceModelSubgraph("forwardDifferences", cimDatasetGraph.getForwardDifferences());
    writeDifferenceModelSubgraph("reverseDifferences", cimDatasetGraph.getReverseDifferences());
    xmlStreamWriter.writeEndElement();
  }

  private void writeDifferenceModelSubgraph(String graphName, Graph graph)
      throws XMLStreamException {
    currentGraph = graph;
    xmlStreamWriter.writeStartElement(differenceModelNamespaceUri, graphName);
    xmlStreamWriter.writeAttribute(rdfUri, "parseType", "Statements");
    if (graph != null) {
      writeDefinitionElements();
      writeDescriptionElements();
    }
    xmlStreamWriter.writeEndElement();
  }

  private void writeProperties(Node subjectNode) {
    var tripleStream = currentGraph.stream(subjectNode, Node.ANY, Node.ANY);
    if (sorted) {
      tripleStream = tripleStream.sorted(Comparator.<Triple, String>comparing(
          triple -> triple.getPredicate().getURI()
      ).thenComparing(triple -> triple.getObject().toString()));
    }
    tripleStream.forEachOrdered(this::writeProperty);
  }

  private void writeDefinitionElements() {
    var tripleStream = currentGraph.stream(Node.ANY, RDF.type.asNode(), Node.ANY);
    if (sorted) {
      tripleStream = tripleStream.sorted(Comparator.<Triple, String>comparing(
          triple -> triple.getObject().getURI()
      ).thenComparing(triple -> triple.getSubject().getURI()));
    }
    tripleStream.forEachOrdered(this::writeDefinitionElement);
  }

  private void writeDescriptionElements() {
    var subjectStream = currentGraph.stream()
        .map(Triple::getSubject)
        .distinct()
        .filter(node -> !currentGraph.contains(node, RDF.type.asNode(), Node.ANY));
    if (sorted) {
      subjectStream = subjectStream.sorted(Comparator.comparing(Node::getURI));
    }
    subjectStream.forEachOrdered(this::writeDescriptionElement);
  }

  private static String replaceUrnUuidWithHash(String uri) {
    return uri.replace(baseUri, "#_");
  }
}
