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

import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import java.util.Objects;
import java.util.Set;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.vocabulary.RDFS;

/**
 * Represents a CIM profile ontology (RDFS schema) graph.
 *
 * <p>A CIM profile defines a subset of the CIM schema for specific use cases, such as
 * equipment models, topology, or state variables. Profiles are versioned and identified by their
 * version IRIs.</p>
 *
 * <h3>Profile Structure:</h3>
 *
 * <p>CIM profiles can be defined in different formats depending on the CIM version:</p>
 *
 * <h4>CIM 16 (CGMES 2.4.15):</h4>
 * <ul>
 *   <li>Version IRIs defined via {@code cims:isFixed} properties</li>
 *   <li>Keywords via {@code {Profile}Version.shortName}</li>
 *   <li>Multiple version IRIs possible (baseURI, entsoeURI)</li>
 * </ul>
 *
 * <h4>CIM 17/18 (CGMES 3.0+):</h4>
 * <ul>
 *   <li>Version IRIs via {@code owl:versionIRI}</li>
 *   <li>Keywords via {@code dcat:keyword}</li>
 *   <li>Version info via {@code owl:versionInfo}</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Load and wrap a profile graph
 * Graph profileGraph = loadProfileFromFile("Equipment.rdf");
 * CimProfile profile = CimProfile.wrap(profileGraph);
 *
 * // Query profile metadata
 * String cimNamespace = profile.getCimNamespace();
 * Set<Node> versionIris = profile.getOwlVersionIRIs();
 * String keyword = profile.getDcatKeyword();
 * boolean isHeader = profile.isHeaderProfile();
 * }</pre>
 *
 * @see CimProfileRegistry
 * @since Jena 5.6.0
 */
public interface CimProfile extends CimGraph {

  String NS_CIMS = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
  String CLASS_CLASS_CATEGORY = "ClassCategory";
  String PACKAGE_FILE_HEADER_PROFILE = "#Package_FileHeaderProfile";

  String NS_DCTERMS = "http://purl.org/dc/terms/";

  Node TYPE_CLASS_CATEGORY = NodeFactory.createURI(NS_CIMS + CLASS_CLASS_CATEGORY);
  Node PREDICATE_DCTERMS_TITLE = NodeFactory.createURI(NS_DCTERMS + "title");
  Node PREDICATE_DCTERMS_DESCRIPTION = NodeFactory.createURI(NS_DCTERMS + "description");
  Node PREDICATE_DCTERMS_ISSUED = NodeFactory.createURI(NS_DCTERMS + "issued");

  /**
   * Wraps a graph as a CimProfile. If the graph is already a CimProfile, it is returned as is.
   * Otherwise, a new ProfileOntologyImpl is created wrapping the graph.
   *
   * <p>If the graph does not appear to be a CIM graph (no 'cim' namespace defined), an
   * IllegalArgumentException is thrown.
   *
   * @param graph The graph to wrap.
   * @return A CimProfile wrapping the given graph.
   * @throws IllegalArgumentException if the graph does not appear to be a CIM graph.
   */
  static CimProfile wrap(Graph graph) throws IllegalArgumentException {
    if (graph instanceof CimProfile po) {
      return po;
    }
    var cimNamespace = CimGraph.getCimNs(graph);
    if (cimNamespace == null) {
      throw new IllegalArgumentException(
          "Graph does not appear to be a CIM graph. No proper 'cim' namespace defined.");
    }
    var factory = CimNamespaceFactoryRegistry.getProfileFactory(cimNamespace);
    if (factory == null) {
      throw new IllegalArgumentException(
          "No profile factory registered for 'cim' namespace URI: " + cimNamespace);
    }
    return factory.apply(graph);
  }

  /**
   * The header profile describes the RDF schema for a CIM model header or document header. These
   * profiles are not references by the model. So in the profile registry header profiles usually
   * are not selected by their versionIRI.
   *
   * @return true if this profile is a header profile, false otherwise.
   */
  boolean isHeaderProfile();

  /**
   * Abbreviation or keyword for the profile. This is usually dcat:keyword. For CGMES 2.4.15
   * profiles it is "{Profile}Version.shortName cims:isFixed ?keyword".
   *
   * <p>In CGMES 2.4.15 file header profiles do not contain a "shortName" or "keyword". But the new
   * ontology document header typically contain "FH" as keyword. To maintain compatibility, "FH"
   * shall be used for old CGMES 2.4.15 file header profiles.
   *
   * @return The keyword for the profile, or null if no keyword is defined.
   */
  String getDcatKeyword();

  /**
   * The version IRIs of the profile. This is usually owl:versionIRI. For CGMES 2.4.15 profiles it
   * is "{Profile}Version.baseURI.{*} cims:isFixed ?versionIRI" and "{Profile}Version.entsoeURI{*}
   * cims:isFixed ?versionIRI".
   *
   * <p>One profile can have multiple version IRIs, at least in CGMES 2.4.15 profiles.
   *
   * @return The version IRI of the profile, or null if no version IRI is defined.
   */
  Set<Node> getOwlVersionIris();

  /**
   * Return owl:versionInfo of the ontology object of the profile. For CGMES 2.4.15, there is no
   * such version info.
   *
   * @return The version info of the profile, or null if no version info is defined.
   */
  String getOwlVersionInfo();

  /**
   * The {@code owl:Ontology} object carrying this profile's metadata.
   *
   * <p>CGMES 3.0 profiles describe themselves through such an object. CGMES 2.4.15 profiles have
   * none — they keep the same information on a "{Profile}Version" class and on the profile's
   * package — so implementations for those versions return null.</p>
   *
   * @return The ontology node, or null if the profile has none.
   */
  default Node getOntologyNode() {
    return null;
  }

  /**
   * The {@code cims:ClassCategory} that stands for the profile itself, as opposed to the
   * categories that group its classes.
   *
   * <p>This is where a CGMES 2.4.15 profile keeps its name and description, and where a CGMES 3.0
   * profile repeats them next to the ontology object.</p>
   *
   * @return The profile's package node, or null if the profile has none.
   */
  default Node getProfilePackage() {
    return null;
  }

  /**
   * The profile's name as it should be shown to a reader, e.g. "Core Equipment Vocabulary" or, for
   * a CGMES 2.4.15 profile, "EquipmentProfile".
   *
   * <p>Read from {@code dcterms:title} on the ontology object, falling back to {@code rdfs:label}
   * there and then to {@code rdfs:label} on the profile's package. The fallbacks are what make
   * this work across CIM versions: CGMES 2.4.15 only has the last of them.</p>
   *
   * @return The label of the profile, or null if none is defined.
   */
  default String getLabel() {
    var ontology = getOntologyNode();
    var title = literalOf(this, ontology, PREDICATE_DCTERMS_TITLE);
    if (title == null) {
      title = literalOf(this, ontology, RDFS.label.asNode());
    }
    if (title == null) {
      title = literalOf(this, getProfilePackage(), RDFS.label.asNode());
    }
    return title;
  }

  /**
   * A prose description of what the profile is for.
   *
   * <p>Read from {@code dcterms:description} on the ontology object, falling back to
   * {@code rdfs:comment} there and then to {@code rdfs:comment} on the profile's package.</p>
   *
   * @return The description of the profile, or null if none is defined.
   */
  default String getDescription() {
    var ontology = getOntologyNode();
    var description = literalOf(this, ontology, PREDICATE_DCTERMS_DESCRIPTION);
    if (description == null) {
      description = literalOf(this, ontology, RDFS.comment.asNode());
    }
    if (description == null) {
      description = literalOf(this, getProfilePackage(), RDFS.comment.asNode());
    }
    return description;
  }

  /**
   * The date the profile was issued, in the lexical form it is written in.
   *
   * @return The issue date of the profile, or null if none is defined.
   */
  default String getIssued() {
    return literalOf(this, getOntologyNode(), PREDICATE_DCTERMS_ISSUED);
  }

  /**
   * Everything the profile says about itself, gathered in one call.
   *
   * <p>Each accessor scans the graph separately, so collecting the fields through this method
   * keeps a caller that needs several of them from walking the profile once per field.</p>
   *
   * @return The metadata of this profile, never null.
   */
  default CimProfileMetadata getMetadata() {
    return new CimProfileMetadata(
        getCimNamespace(),
        isHeaderProfile(),
        getDcatKeyword(),
        getLabel(),
        getDescription(),
        getOwlVersionIris(),
        getOwlVersionInfo(),
        getIssued());
  }

  /**
   * The lexical form of the first literal the graph holds for a subject and predicate.
   *
   * <p>The lexical form rather than the literal value because profile metadata is language-tagged
   * ({@code xml:lang="en"}) in CGMES 3.0 and, in CGMES 2.4.15, written as
   * {@code rdf:parseType="Literal"} and so parsed as an XML literal.</p>
   *
   * @param graph     The graph to read from.
   * @param subject   The subject to read, or null to read nothing.
   * @param predicate The predicate to read.
   * @return The literal's lexical form, or null if there is no such literal.
   */
  static String literalOf(Graph graph, Node subject, Node predicate) {
    if (subject == null) {
      return null;
    }
    return graph.stream(subject, predicate, Node.ANY)
        .map(Triple::getObject)
        .filter(Node::isLiteral)
        .map(Node::getLiteralLexicalForm)
        .findFirst()
        .orElse(null);
  }

  /**
   * Checks if this profile is equal to another profile. Two profiles are equal if they have the
   * same CIM version and the same set of version IRIs, or if both are header profiles.
   *
   * @param other The other profile to compare to.
   * @return true if the profiles are equal, false otherwise.
   */
  default boolean equals(CimProfile other) {
    if (other == null) {
      return false;
    }
    if (!Objects.equals(this.getCimNamespace(), other.getCimNamespace())) {
      return false;
    }
    if (isHeaderProfile()) {
      return other.isHeaderProfile();
    }
    return this.getOwlVersionIris().equals(other.getOwlVersionIris());
  }

  /**
   * Calculates the hash code for this profile. If the model is a header profile, the hash code is
   * based on the CIM version and the fact that it is a header profile. If the model is not a header
   * profile, the hash code is based on the CIM version and the set of version IRIs.
   *
   * @return The hash code for this profile.
   */
  default int calculateHashCode() {
    // hash code from isHeader, cim namespace and version IRIs
    int result = Boolean.hashCode(isHeaderProfile());
    final var ns = getCimNamespace();
    if (ns != null) {
      result = 31 * result + getCimNamespace().hashCode();
    }
    if (!isHeaderProfile()) {
      result = 31 * result + getOwlVersionIris().hashCode();
    }
    return result;
  }

}
