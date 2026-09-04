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

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.graph.GraphWrapper;
import org.apache.jena.vocabulary.RDF;

/**
 * A wrapper for a graph that contains a CIM profile ontology as defined in CIM 17.
 */
public class CimProfile17 extends GraphWrapper implements CimProfile {

  public static final String CIM_NAMESPACE = "http://iec.ch/TC57/CIM100#";

  protected static final String NS_OWL = "http://www.w3.org/2002/07/owl#";
  protected static final Node CLASS_ONTOLOGY = NodeFactory.createURI(NS_OWL + "Ontology");
  protected static final String NS_DCAT = "http://www.w3.org/ns/dcat#";
  protected static final Node PREDICATE_DCAT_KEYWORD = NodeFactory.createURI(NS_DCAT + "keyword");
  protected static final Node PREDICATE_OWL_VERSION_IRI = NodeFactory.createURI(
      NS_OWL + "versionIRI");
  protected static final Node PREDICATE_OWL_VERSION_INFO = NodeFactory.createURI(
      NS_OWL + "versionInfo");

  /**
   * A profile's own package is named "Package_{Profile}Profile"; the packages that merely group
   * its classes are named after the class category, e.g. "Package_Domain".
   */
  protected static final String PACKAGE_INFIX = "Package_";
  protected static final String PROFILE_POSTFIX = "Profile";

  private final boolean isHeaderProfile;

  /**
   * Wraps the given graph as a CimProfile17.
   *
   * @param graph The graph to wrap.
   * @throws IllegalArgumentException if the graph does not contain the required information to be a
   *                                  CimProfile17.
   */
  public CimProfile17(Graph graph) {
    this(graph, CimProfile17::isCim17HeaderProfile);
  }

  /**
   * Wraps the given graph as a CimProfile17, using the provided predicate to determine if the graph
   * is a header profile.
   *
   * @param graph The graph to wrap.
   * @param isHeaderProfilePredicate A predicate that determines if the graph is a header profile.
   * @throws IllegalArgumentException if the graph does not contain the required information to be a
   *                                  CimProfile17.
   */
  protected CimProfile17(Graph graph, Predicate<Graph> isHeaderProfilePredicate) {
    super(graph);
    this.isHeaderProfile = isHeaderProfilePredicate.test(graph);
    if (this.isHeaderProfile) {
      return;
    }
    if (!hasOntology(graph)) {
      throw new IllegalArgumentException(
          "Graph does not contain the required ontology subject for a CIM profile.");
    }
    if (!hasVersionIriAndKeyword(graph)) {
      throw new IllegalArgumentException("Graphs ontology does not contain the required versionIRI"
          + " and keyword for a CIM profile.");
    }
  }

  @Override
  public String getDcatKeyword() {
    if (isHeaderProfile()) {
      // CGMES v3.0 file header profiles do not have a keyword.
      return "FH"; // Use "FH" for compatibility with old CGMES 2.4.15 file header profiles.
    }
    return CimProfile.literalOf(this, getOntologyNode(), PREDICATE_DCAT_KEYWORD);
  }

  @Override
  public Set<Node> getOwlVersionIris() {
    var ontology = getOntologyNode();
    if (ontology == null) {
      return Set.of();
    }
    return stream(ontology, PREDICATE_OWL_VERSION_IRI, Node.ANY)
        .map(Triple::getObject)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public String getOwlVersionInfo() {
    return CimProfile.literalOf(this, getOntologyNode(), PREDICATE_OWL_VERSION_INFO);
  }

  @Override
  public Node getOntologyNode() {
    return stream(Node.ANY, RDF.type.asNode(), CLASS_ONTOLOGY)
        .map(Triple::getSubject)
        .findFirst()
        .orElse(null);
  }

  @Override
  public Node getProfilePackage() {
    return stream(Node.ANY, RDF.type.asNode(), TYPE_CLASS_CATEGORY)
        .map(Triple::getSubject)
        .filter(Node::isURI)
        .filter(subject -> subject.getURI().contains(PACKAGE_INFIX))
        .filter(subject -> subject.getURI().endsWith(PROFILE_POSTFIX))
        .findFirst()
        .orElse(null);
  }

  @Override
  public final boolean equals(Object other) {
    if (!(other instanceof CimProfile17 that)) {
      return false;
    }
    return this.equals(that);
  }

  @Override
  public int hashCode() {
    return this.calculateHashCode();
  }

  /**
   * Get the ontology node in this graph. The ontology node is defined as a subject with type
   * owl:Ontology. If no such node is found, an exception is thrown.
   *
   * @return The ontology node.
   * @throws NoSuchElementException if no ontology node is found.
   */
  public Node getOntology() {
    return getOntology(this);
  }

  /**
   * Get the ontology node in the graph. The ontology node is defined as a subject with type
   * owl:Ontology. If no such node is found, an exception is thrown.
   *
   * @param graph The graph to search in.
   * @return The ontology node.
   * @throws NoSuchElementException if no ontology node is found.
   */
  public static Node getOntology(Graph graph) {
    return graph.stream(Node.ANY, RDF.type.asNode(), CLASS_ONTOLOGY).findAny()
        .map(Triple::getSubject).orElseThrow();
  }

  /**
   * Find the ontology node in the graph. The ontology node is defined as a subject with type
   * owl:Ontology. If no such node is found, null is returned.
   *
   * @param graph The graph to search in.
   * @return The ontology node or null if not found.
   */
  static boolean hasOntology(Graph graph) {
    return graph.contains(Node.ANY, RDF.type.asNode(), CLASS_ONTOLOGY);
  }

  /**
   * Checks if the given graph contains both a DCAT keyword and an OWL version IRI.
   *
   * @param graph The graph to check.
   * @return true if both a DCAT keyword and an OWL version IRI are present, false otherwise.
   */
  public static boolean hasVersionIriAndKeyword(Graph graph) {
    return graph.contains(Node.ANY, PREDICATE_DCAT_KEYWORD, Node.ANY)
        && graph.contains(Node.ANY, PREDICATE_OWL_VERSION_IRI, Node.ANY);
  }

  @Override
  public boolean isHeaderProfile() {
    return this.isHeaderProfile;
  }

  /**
   * Checks if the given graph is a header profile. A header profile is defined as a graph that
   * contains a subject with type "cims:ClassCategory" and the subject URI ends with
   * "#Package_FileHeaderProfile".
   *
   * @param graph The graph to check.
   * @return true if the graph is a header profile, false otherwise.
   */
  public static boolean isCim17HeaderProfile(Graph graph) {
    return graph.stream(Node.ANY, RDF.type.asNode(), TYPE_CLASS_CATEGORY)
        .anyMatch(t
            -> t.getSubject().isURI()
            && t.getSubject().getURI().endsWith(PACKAGE_FILE_HEADER_PROFILE));
  }
}
