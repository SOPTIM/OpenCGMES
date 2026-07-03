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

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;

/**
 * A wrapper for a graph that contains a CIM profile ontology as defined in CIM 18.
 */
public class CimProfile18 extends CimProfile17 {

  public static final String CIM_NAMESPACE = "https://cim.ucaiug.io/ns#";
  private static final String DOCUMENT_HEADER_VERSION_IRI_START = "https://ap-voc.cim4.eu/DocumentHeader";

  /**
   * Wraps the given graph as a CimProfile18.
   *
   * @param graph The graph to wrap.
   * @throws IllegalArgumentException if the graph does not contain the required information to be a
   *                                  CimProfile18.
   */
  public CimProfile18(Graph graph) {
    super(graph, CimProfile18::isCim18HeaderProfile);
  }

  /**
   * Checks if the given graph is a header profile. A header profile is identified by having an
   * ontology with a version IRI that starts with
   * {@code https://ap-voc.cim4.eu/DocumentHeader}.
   *
   * @param graph The graph to check.
   * @return true if the graph is a header profile, false otherwise.
   */
  public static boolean isCim18HeaderProfile(Graph graph) {
    if (!hasOntology(graph)) {
      return false;
    }
    var ontology = getOntology(graph);

    // look for https://ap.cim4.eu/DocumentHeader# without # in version IRIs
    return graph.stream(ontology, PREDICATE_OWL_VERSION_IRI, Node.ANY)
        .anyMatch(t
            -> t.getObject().isURI()
            && t.getObject().getURI().startsWith(DOCUMENT_HEADER_VERSION_IRI_START));
  }
}
