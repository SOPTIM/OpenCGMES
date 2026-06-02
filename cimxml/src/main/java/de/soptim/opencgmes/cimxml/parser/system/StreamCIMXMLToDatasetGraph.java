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

import de.soptim.opencgmes.cimxml.CimVersion;
import de.soptim.opencgmes.cimxml.CimXmlDocumentContext;
import de.soptim.opencgmes.cimxml.graph.CimModelHeader;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import de.soptim.opencgmes.cimxml.sparql.core.LinkedCimDatasetGraph;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.mem.GraphMemRoaring;
import org.apache.jena.mem.IndexingStrategy;
import org.apache.jena.sparql.core.Quad;

/**
 * An implementation of {@link StreamCIMXML} that populates a {@link LinkedCimDatasetGraph}
 * with the triples from the CIMXML file being processed.
 * <p>
 * This class manages multiple named graphs within the dataset, switching the current graph
 * context based on the {@link CimXmlDocumentContext}. It uses different indexing strategies
 * for different contexts to optimize performance.
 */
public class StreamCIMXMLToDatasetGraph implements StreamCIMXML {

    private final LinkedCimDatasetGraph linkedCIMDatasetGraph;
    private String versionOfIEC61970_552 = null;
    private Graph currentGraph;
    private CimXmlDocumentContext currentContext;
    private CimVersion versionOfCIMXML = CimVersion.NO_CIM;

    public StreamCIMXMLToDatasetGraph() {
        // init default graph for body context
        currentContext = CimXmlDocumentContext.body;
        currentGraph = new GraphMemRoaring(IndexingStrategy.LAZY_PARALLEL);
        linkedCIMDatasetGraph = new LinkedCimDatasetGraph(currentGraph);
    }

    @Override
    public String getVersionOfIEC61970_552() {
        return versionOfIEC61970_552;
    }

    @Override
    public CimVersion getVersionOfCIMXML() {
        return versionOfCIMXML;
    }

    @Override
    public void setVersionOfCIMXML(CimVersion versionOfCIMXML) {
        this.versionOfCIMXML = versionOfCIMXML;
    }

    @Override
    public CimDatasetGraph getCIMDatasetGraph() {
        return linkedCIMDatasetGraph;
    }

    private void setCurrentGraphAndCreateIfNecessary(Node graphName, IndexingStrategy indexingStrategy) {
        if (linkedCIMDatasetGraph.containsGraph(graphName)) {
            currentGraph = linkedCIMDatasetGraph.getGraph(graphName);
        } else {
            final var newGraph = new GraphMemRoaring(indexingStrategy);
            newGraph.getPrefixMapping().setNsPrefixes(currentGraph.getPrefixMapping());
            currentGraph = newGraph;
            linkedCIMDatasetGraph.addGraph(graphName, currentGraph);
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
    public void version(String version) {
        // Nothing to do
    }

    @Override
    public void prefix(String prefix, String iri) {
        linkedCIMDatasetGraph.prefixes().add(prefix, iri);
        currentGraph.getPrefixMapping().setNsPrefix(prefix, iri);
    }

    @Override
    public void finish() {
        // Initialize indexes in parallel for all graphs that use LAZY_PARALLEL indexing strategy.
        linkedCIMDatasetGraph.getGraphs().parallelStream().forEach(graph -> {
            if (graph instanceof GraphMemRoaring roaring && !roaring.isIndexInitialized()) {
                roaring.initializeIndexParallel();
            }
        });
    }

    @Override
    public CimModelHeader getModelHeader() {
        return linkedCIMDatasetGraph.getModelHeader();
    }

    @Override
    public void setVersionOfIEC61970_552(String versionOfIEC61970_552) {
        this.versionOfIEC61970_552 = versionOfIEC61970_552;
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
     * Switches the current graph context based on the provided {@link CimXmlDocumentContext}.
     * This method updates the current graph to the appropriate named graph in the dataset,
     * creating it if it does not already exist. The indexing strategy is chosen based on the
     * context to optimize performance for different types of data.
     * @param cimDocumentContext the new document context to switch to
     */
    private void switchContext(CimXmlDocumentContext cimDocumentContext) {
        var indexingStrategy = switch (cimDocumentContext) {
            // The metadata is usually very small, so we use a minimal indexing strategy.
            case fullModel, differenceModel -> IndexingStrategy.MINIMAL;
            // The data parts can be large, so we use a lazy parallel indexing strategy.
            default -> IndexingStrategy.LAZY_PARALLEL;
        };
        var graphName = CimXmlDocumentContext.getGraphName(cimDocumentContext);
        setCurrentGraphAndCreateIfNecessary(graphName, indexingStrategy);
        currentContext = cimDocumentContext;
    }
}
