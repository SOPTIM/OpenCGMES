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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.vocabulary.RDF;

public class SerializerCIMXML_StAX_SR {

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

  public SerializerCIMXML_StAX_SR(XMLStreamWriter xmlStreamWriter, CimDatasetGraph cimDatasetGraph,
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
      var model = ModelFactory.createModelForGraph(cimDatasetGraph.getBody());
      var subjectIterator = getResourceIterator(model);
      while (subjectIterator.hasNext()) {
        writeDefinitionElement(subjectIterator.next());
      }
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
    var propertyIterator = getStatementIterator(fullModelResource);
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
    var propertyIterator = getStatementIterator(subjectNode);
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.6 Description element
  private void writeDescriptionElement(Resource subjectNode) throws XMLStreamException {
    xmlStreamWriter.writeStartElement(rdfUri, "Description");
    xmlStreamWriter.writeAttribute(rdfUri, about, replaceUrnUuidWithHash(subjectNode.getURI()));
    var propertyIterator = getStatementIterator(subjectNode);
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.7 Compound element
  private void writeCompoundElement(Resource subjectNode) throws XMLStreamException {
    var typeResource = subjectNode.getProperty(RDF.type).getResource();
    xmlStreamWriter.writeStartElement(typeResource.getNameSpace(), typeResource.getLocalName());
    var propertyIterator = getStatementIterator(subjectNode);
    while (propertyIterator.hasNext()) {
      writeProperty(propertyIterator.next());
    }
    xmlStreamWriter.writeEndElement();
  }

  // Section 7.2.3.8-10 Property element
  private void writeProperty(Statement property) throws XMLStreamException {
    if (property.getPredicate().equals(RDF.type)) {
      return;
    }
    xmlStreamWriter.writeStartElement(property.getPredicate().getNameSpace(),
        property.getPredicate().getLocalName());
    switch (getPropertyType(property.getObject())) {
      case LITERAL_PROPERTY -> xmlStreamWriter.writeCharacters(
          property.getLiteral().getLexicalForm()); // Section 7.2.3.8 Literal-Property element
      case COMPOUND_PROPERTY ->
          writeCompoundElement(property.getResource()); // Section 7.2.3.9 Compound-Property element
      case RESOURCE_PROPERTY -> xmlStreamWriter.writeAttribute(rdfUri, "resource",
          replaceUrnUuidWithHash(
              property.getResource().getURI())); // Section 7.2.3.10 Resource-Property element
    }
    xmlStreamWriter.writeEndElement();
  }

  private PropertyType getPropertyType(RDFNode propertyObject) {
    if (propertyObject.isURIResource()) {
      return PropertyType.RESOURCE_PROPERTY;
    } else if (propertyObject.isLiteral()) {
      return PropertyType.LITERAL_PROPERTY;
    } else { // isAnon
      return PropertyType.COMPOUND_PROPERTY;
    }
  }

  private enum PropertyType {
    LITERAL_PROPERTY,
    COMPOUND_PROPERTY,
    RESOURCE_PROPERTY
  }

  private void writeDifferenceModelElement() throws XMLStreamException {
    xmlStreamWriter.writeStartElement(differenceModelNamespaceUri, "DifferenceModel");
    var modelHeader = cimDatasetGraph.getModelHeader();
    var model = ModelFactory.createModelForGraph(modelHeader);
    var differenceModelNode = modelHeader.getModel();
    var differenceModelResource = model.getResource(differenceModelNode.getURI());
    xmlStreamWriter.writeAttribute(rdfUri, about, differenceModelResource.getURI());
    var propertyIterator = getStatementIterator(differenceModelResource);
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
    var subjectIterator = getDifferenceModelResourceIterator(model);
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

  private Iterator<Statement> getStatementIterator(Resource resource) {
    Iterator<Statement> propertyIterator = resource.listProperties();
    if (sorted) {
      var stmtList = ((StmtIterator) propertyIterator).toList();
      stmtList.sort(Comparator.<Statement, String>comparing(
              statement -> statement.getPredicate().getURI())
          .thenComparing(statement -> statement.getObject().toString()));
      propertyIterator = stmtList.iterator();
    }
    return propertyIterator;
  }

  private Iterator<Resource> getResourceIterator(Model model) {
    Iterator<Resource> resourceIterator = model.listSubjects();
    if (sorted) {
      var resList = ((ResIterator) resourceIterator).toList();
      resList.sort(Comparator.<Resource, String>comparing(
              resource -> resource.getProperty(RDF.type).getResource().getURI())
          .thenComparing(Resource::getURI));
      resourceIterator = resList.iterator();
    }
    return resourceIterator;
  }

  private Iterator<Resource> getDifferenceModelResourceIterator(Model model) {
    Iterator<Resource> resourceIterator = model.listSubjects();
    if (sorted) {
      Map<Boolean, List<Resource>> typePartitions = ((ResIterator) resourceIterator).toList()
          .stream()
          .collect(Collectors.partitioningBy(resource -> resource.hasProperty(RDF.type)));
      typePartitions.get(true).sort(Comparator.<Resource, String>comparing(
              resource -> resource.getProperty(RDF.type).getResource().getURI())
          .thenComparing(Resource::getURI));
      typePartitions.get(false).sort(Comparator.comparing(Resource::getURI));
      var resList = Stream.concat(typePartitions.get(true).stream(),
          typePartitions.get(false).stream()).toList();
      resourceIterator = resList.iterator();
    }
    return resourceIterator;
  }

  private static String replaceUrnUuidWithHash(String uri) {
    return uri.replace(baseUri, "#_");
  }
}
