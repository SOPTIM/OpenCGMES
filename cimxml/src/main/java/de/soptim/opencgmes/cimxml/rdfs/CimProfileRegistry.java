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

package de.soptim.opencgmes.cimxml.rdfs;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import java.util.Map;
import java.util.Set;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.graph.Node;

/**
 * Registry for managing CIM profile ontologies and their associated datatypes.
 *
 * <p>The CIM Profile Registry is a central component for managing CIM profile ontologies
 * (RDFS schemas) that define the structure and datatypes used in CIM models. It provides:</p>
 *
 * <ul>
 *   <li>Registration and lookup of CIM profiles by version IRI</li>
 *   <li>Datatype mapping from CIM primitive types to RDF datatypes</li>
 *   <li>Property-to-datatype resolution for parsing CIMXML documents</li>
 *   <li>Support for header profiles and model profiles</li>
 * </ul>
 *
 * <h3>Profile Types:</h3>
 * <dl>
 *   <dt><b>Model Profiles</b></dt>
 *   <dd>Define the structure and properties for CIM model data (e.g., Equipment, Topology)</dd>
 *
 *   <dt><b>Header Profiles</b></dt>
 *   <dd>Define the structure for model headers (FullModel/DifferenceModel metadata)</dd>
 * </dl>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Create registry
 * CimProfileRegistry registry = new CimProfileRegistryStd();
 *
 * // Register profiles
 * CimProfile equipmentProfile = CimProfile.wrap(equipmentGraph);
 * registry.register(equipmentProfile);
 *
 * // Register custom datatype mappings
 * registry.registerPrimitiveType("Voltage", XSDDatatype.XSDdouble);
 * registry.registerPrimitiveType("Current", XSDDatatype.XSDdouble);
 *
 * // Query properties and datatypes
 * Set<Node> profileIris = Set.of(NodeFactory.createURI("http://example.org/Equipment/1.0"));
 * Map<Node, PropertyInfo> properties = registry.getPropertiesAndDatatypes(profileIris);
 * }</pre>
 *
 * <h3>Thread Safety:</h3>
 *
 * <p>Implementations must be thread-safe for all read operations. Registration operations
 * may require external synchronization.</p>
 *
 * @see CimProfile
 * @see PropertyInfo
 */
public interface CimProfileRegistry {

  /**
   * Registers an ontology graph for profiles in the registry. During registration, the data types
   * of all properties in the graph are extracted and stored in a map for fast lookup. Throws an
   * IllegalArgumentException if one of the profiles owlVersionIRIs is already registered or in case
   * of a header profile, if one has already been registered for the same CIM version.
   *
   * @param cimProfile The profile ontology to register.
   */
  void register(CimProfile cimProfile);

  /**
   * Checks if the registry contains all profile IRIs in the given set.
   *
   * @param owlVersionIris A set of profile IRIs as found in the model header.
   * @return true if all profile IRIs are registered, false otherwise.
   */
  boolean containsProfile(Set<Node> owlVersionIris);

  /**
   * Get all registered profiles that match the given set of profile IRIs.
   *
   * @param owlVersionIris A set of profile IRIs as found in the model header.
   * @return A set of matching profiles. If no profiles match, an empty set is returned.
   */
  Set<CimProfile> getMatchingProfiles(Set<Node> owlVersionIris);

  /**
   * Checks if the registry contains a header profile for the given CIM namespace.
   *
   * @param cimNamespace The 'cim' namespace to check.
   * @return true if a header profile for the given 'cim' namespace is registered, false otherwise.
   */
  boolean containsHeaderProfile(String cimNamespace);

  /**
   * Get all registered ontologies in the registry.
   *
   * @return A collection of all registered ontologies.
   */
  Set<CimProfile> getRegisteredProfiles();

  /**
   * Get all properties and their associated RDF datatypes for the given set of profile IRIs. The
   * set may contain profile IRIs for multiple ontologies. Throws an IllegalArgumentException if one
   * of the profile IRIs is not registered.
   *
   * @param owlVersionIris A set of profile IRIs as found in the model header.
   * @return A map of properties and their associated RDF datatypes. The map is thread-safe for
   *     reading. Returns an empty map if one of the profile IRIs is not registered.
   */
  Map<Node, PropertyInfo> getPropertiesAndDatatypes(Set<Node> owlVersionIris);

  /**
   * Get all properties and their associated RDF datatypes for the header profile of the given CIM
   * version. Throws an IllegalArgumentException if no header profile has been registered for the
   * given CIM version.
   *
   * @param cimNamespace The 'cim' namespace for which the header profile should be used.
   * @return A map of properties and their associated RDF datatypes. The map is thread-safe for
   *     reading. Returns an empty map if no header profile is registered for the given 'cim'
   *     namespace.
   */
  Map<Node, PropertyInfo> getHeaderPropertiesAndDatatypes(String cimNamespace);

  /**
   * Get a mapping of primitive type names to RDF datatypes for all registered profiles. This
   * includes primitive types from all registered ontologies.
   *
   * @return A map of primitive type names to RDF datatypes. The map is thread-safe for reading.
   */
  Map<String, RDFDatatype> getPrimitiveToRdfDatatypeMapping();

  /**
   * Registers a custom primitive type with the given CIM primitive type name and RDF datatype. If
   * the primitive type name is already registered, it will be overwritten with the new RDF
   * datatype. This method can be used to register custom primitive types that are not part of the
   * standard CIM profiles. The rdfDatatype must also be registered with Jena's TypeMapper.
   *
   * @param cimPrimitiveTypeName The CIM primitive type name, e.g. "string", "int", "float", etc.
   * @param rdfDatatype          The RDF datatype to associate with the given CIM primitive type
   *                             name.
   */
  void registerPrimitiveType(String cimPrimitiveTypeName, RDFDatatype rdfDatatype);

  /**
   * Information about a CIM property including its domain, range, and datatype.
   *
   * <p>This record encapsulates all metadata needed to properly parse and validate
   * a CIM property value:</p>
   *
   * <ul>
   *   <li><b>rdfType</b>: The class (domain) this property belongs to</li>
   *   <li><b>property</b>: The property URI</li>
   *   <li><b>cimDatatype</b>: The CIM datatype definition</li>
   *   <li><b>primitiveType</b>: RDF datatype for primitive properties (e.g., xsd:float)</li>
   *   <li><b>referenceType</b>: Target class for object properties</li>
   * </ul>
   *
   * <p>Either primitiveType or referenceType will be non-null, but not both:</p>
   * <ul>
   *   <li>If primitiveType is non-null, this is a datatype property</li>
   *   <li>If referenceType is non-null, this is an object property</li>
   * </ul>
   *
   * @param rdfType       The domain class of this property
   * @param property      The property URI
   * @param cimDatatype   The CIM datatype definition (may be null)
   * @param primitiveType The RDF datatype for primitive values (may be null)
   * @param referenceType The range class for object properties (may be null)
   */
  record PropertyInfo(Node rdfType, Node property, Node cimDatatype, RDFDatatype primitiveType,
                      Node referenceType) {

  }
}
