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

package de.soptim.opencgmes.cimxml.parser.system;

import de.soptim.opencgmes.cimxml.CimXmlDocumentContext;
import de.soptim.opencgmes.cimxml.graph.CimModelHeader;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import de.soptim.opencgmes.cimxml.sparql.core.LinkedCimDatasetGraph;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;

/**
 * An implementation of {@link StreamCimXml} that populates a {@link LinkedCimDatasetGraph} with the
 * triples from the CIMXML file being processed.
 *
 * <p>This class manages multiple named graphs within the dataset, switching the current graph
 * context based on the {@link CimXmlDocumentContext}. It uses different indexing strategies for
 * different contexts to optimize performance.
 */
public class StreamCimXmlToDatasetGraph implements StreamCimXml {

  private final LinkedCimDatasetGraph linkedCimDatasetGraph;
  private String versionOfIec61970_552 = null;
  private Graph currentGraph;
  private CimXmlDocumentContext currentContext;
  private String cimNamespace = null;

  /**
   * Creates a new instance of {@link StreamCimXmlToDatasetGraph} and initializes the default graph
   * for the body context. The default graph is created as an in-memory graph and is associated with
   * the body context in the linked dataset graph.
   */
  public StreamCimXmlToDatasetGraph() {
    // init default graph for body context
    currentContext = CimXmlDocumentContext.body;
    currentGraph = GraphFactory.createGraphMem();
    linkedCimDatasetGraph = new LinkedCimDatasetGraph(currentGraph);
  }

  @Override
  public String getVersionOfIec61970_552() {
    return versionOfIec61970_552;
  }

  @Override
  public void setVersionOfIec61970_552(String versionOfCimXml) {
    this.versionOfIec61970_552 = versionOfCimXml;
  }

  @Override
  public String getCimNamespace() {
    return cimNamespace;
  }

  @Override
  public void setCimNamespace(String cimNamespace) {
    this.cimNamespace = cimNamespace;
  }

  @Override
  public CimDatasetGraph getCimDatasetGraph() {
    return linkedCimDatasetGraph;
  }

  private void setCurrentGraphAndCreateIfNecessary(Node graphName) {
    if (linkedCimDatasetGraph.containsGraph(graphName)) {
      currentGraph = linkedCimDatasetGraph.getGraph(graphName);
    } else {
      final Graph newGraph = GraphFactory.createGraphMem();
      newGraph.getPrefixMapping().setNsPrefixes(currentGraph.getPrefixMapping());
      currentGraph = newGraph;
      linkedCimDatasetGraph.addGraph(graphName, currentGraph);
    }
  }

  @Override
  public void start() {
    // Nothing to do
  }

  @Override
  public void triple(Triple triple) {
    currentGraph.add(triple);
  }

  @Override
  public void quad(Quad quad) {
    throw new UnsupportedOperationException("Quads are not supported in this context.");
  }

  @Override
  public void base(String base) {
    // Nothing to do
  }

  @Override
  public void prefix(String prefix, String iri) {
    linkedCimDatasetGraph.prefixes().add(prefix, iri);
    currentGraph.getPrefixMapping().setNsPrefix(prefix, iri);
  }

  @Override
  public void version(String version) {
    // Nothing to do
  }

  @Override
  public void finish() {
    // Nothing to do
  }

  @Override
  public CimModelHeader getModelHeader() {
    return linkedCimDatasetGraph.getModelHeader();
  }

  @Override
  public CimXmlDocumentContext getCurrentContext() {
    return currentContext;
  }

  @Override
  public void setCurrentContext(CimXmlDocumentContext context) {
    switchContext(context);
  }

  /**
   * Switches the current graph context based on the provided {@link CimXmlDocumentContext}. This
   * method updates the current graph to the appropriate named graph in the dataset, creating it if
   * it does not already exist.
   *
   * @param cimDocumentContext the new document context to switch to
   */
  private void switchContext(CimXmlDocumentContext cimDocumentContext) {
    var graphName = CimXmlDocumentContext.getGraphName(cimDocumentContext);
    setCurrentGraphAndCreateIfNecessary(graphName);
    currentContext = cimDocumentContext;
  }
}