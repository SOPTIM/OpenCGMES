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

  Node TYPE_CLASS_CATEGORY = NodeFactory.createURI(NS_CIMS + CLASS_CLASS_CATEGORY);

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
