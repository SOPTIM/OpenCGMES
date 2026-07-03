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

package de.soptim.opencgmes.cimxml.graph;

import de.soptim.opencgmes.cimxml.CimHeaderVocabulary;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.graph.GraphWrapper;
import org.apache.jena.vocabulary.RDF;

/**
 * A wrapper for a graph that contains the model header information of a CIMXML document.
 *
 * <p>The model header is expected to contain exactly one instance of either md:FullModel or
 * dm:DifferenceModel. It may contain zero or more "Model.profile" references, each referencing one
 * of the registered profile ontologies. It may also contain zero or more Model.Supersedes and
 * Model.DependentOn references to other models.
 */
public interface CimModelHeader extends CimGraph {

  /**
   * Wraps a given graph as a {@link CimModelHeader}. If the graph is already an instance of
   * {@link CimModelHeader}, it is returned as is. If the graph is null, null is returned. If the
   * graph does not appear to be a CIM graph (no 'cim' namespace defined), an
   * IllegalArgumentException is thrown.
   *
   * @param graph The graph to wrap.
   * @return The wrapped graph as a {@link CimModelHeader}, or null if the input graph is null.
   * @throws IllegalArgumentException if the graph does not appear to be a CIM graph.
   */
  static CimModelHeader wrap(Graph graph) {
    if (graph == null) {
      return null;
    }
    if (graph instanceof CimModelHeader cimModelHeader) {
      return cimModelHeader;
    }
    if (CimGraph.getCimNs(graph) == null) {
      throw new IllegalArgumentException(
          "Graph does not appear to be a CIM graph. No proper 'cim' namespace defined.");
    }
    return new CimModelHeaderGraphWrapper(graph);
  }

  /**
   * Checks if the model is a full model.
   *
   * @return true if the model is a full model, false otherwise.
   */
  default boolean isFullModel() {
    return contains(Node.ANY, RDF.type.asNode(), CimHeaderVocabulary.TYPE_FULL_MODEL);
  }

  /**
   * Checks if the model is a difference model.
   *
   * @return true if the model is a difference model, false otherwise.
   */
  default boolean isDifferenceModel() {
    return contains(Node.ANY, RDF.type.asNode(), CimHeaderVocabulary.TYPE_DIFFERENCE_MODEL);
  }

  /**
   * Get the node representing the model (either md:FullModel or dm:DifferenceModel). The node is
   * expected to be an IRI.
   *
   * @return The model node.
   * @throws IllegalStateException if neither a FullModel nor a DifferenceModel is found in the
   *                               header graph.
   */
  default Node getModel() {
    final var itFullModel = find(Node.ANY, RDF.type.asNode(), CimHeaderVocabulary.TYPE_FULL_MODEL);
    try {
      if (itFullModel.hasNext()) {
        return itFullModel.next().getSubject();
      }
    } finally {
      itFullModel.close();
    }

    final var itDiffModel
        = find(Node.ANY, RDF.type.asNode(), CimHeaderVocabulary.TYPE_DIFFERENCE_MODEL);
    try {
      if (itDiffModel.hasNext()) {
        return itDiffModel.next().getSubject();
      }
    } finally {
      itDiffModel.close();
    }

    throw new IllegalStateException(
        "Found neither FullModel nor DifferenceModel in the header graph.");
  }

  /**
   * Get the profiles associated with the model. Each profile node in a model header references one
   * owlVersionIRI in {@link CimProfile#getOwlVersionIris()} of the matching profile ontology.
   *
   * @return A set of profile nodes (IRIs).
   */
  default Set<Node> getProfiles() {
    return stream(getModel(), CimHeaderVocabulary.PREDICATE_PROFILE, Node.ANY)
        .map(Triple::getObject)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Get the models that are superseded by this model. Each superseded model is referenced by its
   * IRI.
   *
   * @return A set of model IRIs that are superseded by this model.
   */
  default Set<Node> getSupersedes() {
    return stream(getModel(), CimHeaderVocabulary.PREDICATE_SUPERSEDES, Node.ANY)
        .map(Triple::getObject)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Gets all models that this model is dependent on. Each dependent model is referenced by its
   * IRI.
   *
   * @return A set of models (IRIs) that this model is dependent on.
   */
  default Set<Node> getDependentOn() {
    return stream(getModel(), CimHeaderVocabulary.PREDICATE_DEPENDENT_ON, Node.ANY)
        .map(Triple::getObject)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * An implementation of {@link CimModelHeader} that wraps a {@link Graph}.
   */
  class CimModelHeaderGraphWrapper extends GraphWrapper implements CimModelHeader {

    public CimModelHeaderGraphWrapper(Graph graph) {
      super(graph);
    }
  }
}
