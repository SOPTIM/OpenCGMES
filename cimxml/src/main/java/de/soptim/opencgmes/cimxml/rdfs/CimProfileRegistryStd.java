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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.RDFLangString;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.sparql.exec.QueryExec;

/**
 * Standard implementation of the {@link CimProfileRegistry}. This implementation is thread-safe.
 * Registration of custom primitive type mappings should be done before any other operations on the
 * registry. The primitive type mapping is static for all instances of the registry.
 */
public class CimProfileRegistryStd implements CimProfileRegistry {

  private static final Query typedPropertiesQuery = QueryFactory.create(
      """
      PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX cims: <http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#>
      
      SELECT ?rdfType ?property ?cimDatatype ?primitiveType ?referenceType
      WHERE
      {
        {
           ?property rdfs:domain ?rdfType;
                     rdfs:range ?referenceType;
          OPTIONAL {
           ?property cims:AssociationUsed ?associationUsed
       }
       FILTER(!BOUND(?associationUsed) || ?associationUsed = "Yes")
        }
        UNION
        {
          ?property rdfs:domain ?rdfType;
                    cims:dataType ?cimDatatype.
          {
            ?cimDatatype cims:stereotype "CIMDatatype".
            []  rdfs:domain ?cimDatatype;
                rdfs:label ?label;
                #rdfs:label "value";
                cims:dataType/cims:stereotype "Primitive";
                cims:dataType/rdfs:label ?primitiveType.
            FILTER (!bound(?label) ||  str(?label) = "value")
          }
          UNION
          {
            ?cimDatatype cims:stereotype "Primitive";
                         rdfs:label ?primitiveType.
          }
        }
      }
      """);
  private final ErrorHandler errorHandler;
  private final Map<Set<Node>, CimProfile> multiVersionIriProfiles = new ConcurrentHashMap<>();
  private final Map<Node, CimProfile> singleVersionIriProfiles = new ConcurrentHashMap<>();
  private final Map<String, CimProfile> headerProfilesByCimNamespace = new ConcurrentHashMap<>();
  private final Map<CimProfile, Map<Node, PropertyInfo>> profilePropertiesCache
      = new ConcurrentHashMap<>();
  private final Map<Set<CimProfile>, Map<Node, PropertyInfo>> profileSetPropertiesCache
      = new ConcurrentHashMap<>();
  private final Map<String, RDFDatatype> primitiveToRdfDatatypeMap;

  /**
   * Creates a new instance of the registry using the standard Jena error handler.
   */
  public CimProfileRegistryStd() {
    this(ErrorHandlerFactory.errorHandlerStd);
  }

  /**
   * Creates a new instance of the registry using the given error handler.
   *
   * @param errorHandler The error handler to use for logging warnings and errors.
   */
  public CimProfileRegistryStd(ErrorHandler errorHandler) {
    this.errorHandler = errorHandler;
    this.primitiveToRdfDatatypeMap = initPrimitiveToRdfDatatypeMapUsingXsdDatatypesOnly();
  }

  private static Map<String, RDFDatatype> initPrimitiveToRdfDatatypeMapUsingXsdDatatypesOnly() {
    var map = new ConcurrentHashMap<String, RDFDatatype>();
    map.put("Base64Binary", XSDDatatype.XSDbase64Binary);
    map.put("Boolean", XSDDatatype.XSDboolean);
    map.put("Byte", XSDDatatype.XSDbyte);
    map.put("Date", XSDDatatype.XSDdate);
    map.put("DateTime", XSDDatatype.XSDdateTime);
    map.put("DateTimeStamp", XSDDatatype.XSDdateTimeStamp);
    map.put("Day", XSDDatatype.XSDgDay);
    map.put("DayTimeDuration", XSDDatatype.XSDdayTimeDuration);
    map.put("Decimal", XSDDatatype.XSDdecimal);
    map.put("Double", XSDDatatype.XSDdouble);
    map.put("Duration", XSDDatatype.XSDduration);
    map.put("Float", XSDDatatype.XSDfloat);
    map.put("HexBinary", XSDDatatype.XSDhexBinary);
    map.put("Int", XSDDatatype.XSDint);
    map.put("Integer", XSDDatatype.XSDinteger);
    map.put("IRI", XSDDatatype.XSDstring);
    map.put("LangString", RDFLangString.rdfLangString);
    map.put("Long", XSDDatatype.XSDlong);
    map.put("Month", XSDDatatype.XSDgMonth);
    map.put("MonthDay", XSDDatatype.XSDgMonthDay);
    map.put("NegativeInteger", XSDDatatype.XSDnegativeInteger);
    map.put("NonNegativeInteger", XSDDatatype.XSDnonNegativeInteger);
    map.put("NonPositiveInteger", XSDDatatype.XSDnonPositiveInteger);
    map.put("PositiveInteger", XSDDatatype.XSDpositiveInteger);
    map.put("String", XSDDatatype.XSDstring);
    map.put("StringFixedLanguage", XSDDatatype.XSDstring);
    map.put("StringIRI", XSDDatatype.XSDstring);
    map.put("Time", XSDDatatype.XSDtime);
    map.put("UnsignedByte", XSDDatatype.XSDunsignedByte);
    map.put("UnsignedInt", XSDDatatype.XSDunsignedInt);
    map.put("UnsignedLong", XSDDatatype.XSDunsignedLong);
    map.put("UnsignedShort", XSDDatatype.XSDunsignedShort);
    map.put("URI", XSDDatatype.XSDanyURI);
    map.put("UUID", XSDDatatype.XSDstring);
    map.put("Version", XSDDatatype.XSDstring);
    map.put("Year", XSDDatatype.XSDgYear);
    map.put("YearMonth", XSDDatatype.XSDgYearMonth);
    map.put("YearMonthDuration", XSDDatatype.XSDyearMonthDuration);
    return map;
  }

  @Override
  public void register(CimProfile cimProfile) {
    if (cimProfile.isHeaderProfile()) {
      final var cimNamespace = cimProfile.getCimNamespace();
      if (cimNamespace == null) {
        throw new IllegalArgumentException(
            "Header profile must have the 'cim' prefix and namespace URI defined.");
      }
      if (headerProfilesByCimNamespace.containsKey(cimNamespace)) {
        throw new IllegalArgumentException(
            "Header profile for 'cim' namespace URI '" + cimNamespace + "' is already registered.");
      }
      headerProfilesByCimNamespace.put(cimNamespace, cimProfile);
      profilePropertiesCache.put(cimProfile, getTypedProperties(cimProfile));
      return;
    }

    var owlVersionIris = cimProfile.getOwlVersionIris();
    if (owlVersionIris == null || owlVersionIris.isEmpty()) {
      throw new IllegalArgumentException("Profile ontology must have at least one owlVersionIRI.");
    }

    if (owlVersionIris.size() == 1) {
      var iri = owlVersionIris.iterator().next();
      if (singleVersionIriProfiles.containsKey(iri)) {
        throw new IllegalArgumentException(
            "Profile ontology with owlVersionIRI " + iri + " is already registered.");
      }
      singleVersionIriProfiles.put(iri, cimProfile);
    } else {
      if (multiVersionIriProfiles.containsKey(owlVersionIris)) {
        throw new IllegalArgumentException(
            "Profile ontology with owlVersionIris " + owlVersionIris + " is already registered.");
      }
      multiVersionIriProfiles.put(owlVersionIris, cimProfile);
    }
    profilePropertiesCache.put(cimProfile, getTypedProperties(cimProfile));
  }

  @Override
  public boolean containsProfile(Set<Node> owlVersionIris) {
    if (owlVersionIris == null || owlVersionIris.isEmpty()) {
      throw new IllegalArgumentException("At least one profile owlVersionIRI must be provided.");
    }
    for (var iri : owlVersionIris) {
      if (!singleVersionIriProfiles.containsKey(iri)) {
        var foundInMulti = false;
        for (var registeredVersionIris : multiVersionIriProfiles.keySet()) {
          if (registeredVersionIris.contains(iri)) {
            foundInMulti = true;
            break;
          }
        }
        if (!foundInMulti) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public Set<CimProfile> getMatchingProfiles(Set<Node> owlVersionIris) {
    if (owlVersionIris == null || owlVersionIris.isEmpty()) {
      throw new IllegalArgumentException("At least one profile owlVersionIRI must be provided.");
    }

    if (owlVersionIris.size() == 1) {
      var versionIri = owlVersionIris.iterator().next();
      if (singleVersionIriProfiles.containsKey(versionIri)) {
        return Set.of(singleVersionIriProfiles.get(versionIri));
      }
    }

    var profile = multiVersionIriProfiles.get(owlVersionIris);
    if (profile != null) {
      return Set.of(profile);
    }

    final var set = new HashSet<CimProfile>();
    for (var owlVersionIri : owlVersionIris) {
      final var p = singleVersionIriProfiles.get(owlVersionIri);
      if (p == null) {
        var foundInMulti = false;
        for (var entrySet : multiVersionIriProfiles.entrySet()) {
          if (entrySet.getKey().contains(owlVersionIri)) {
            foundInMulti = true;
            set.add(entrySet.getValue());
            break;
          }
        }
        if (!foundInMulti) {
          return Collections.emptySet();
        }
      } else {
        set.add(p);
      }
    }
    return set;
  }

  @Override
  public boolean containsHeaderProfile(String cimNamespace) {
    Objects.requireNonNull(cimNamespace, "cimNamespace");
    return headerProfilesByCimNamespace.containsKey(cimNamespace);
  }

  @Override
  public Set<CimProfile> getRegisteredProfiles() {
    return profilePropertiesCache.keySet();
  }

  @Override
  public Map<Node, PropertyInfo> getPropertiesAndDatatypes(Set<Node> owlVersionIris) {
    if (owlVersionIris == null || owlVersionIris.isEmpty()) {
      throw new IllegalArgumentException("At least one profile owlVersionIRI must be provided.");
    }

    final var set = getMatchingProfiles(owlVersionIris);

    if (set.size() == 1) {
      return profilePropertiesCache.get(set.iterator().next());
    }

    Map<Node, PropertyInfo> properties = profileSetPropertiesCache.get(set);
    if (properties != null) {
      return properties;
    }

    properties = new HashMap<>(1024);
    for (var p : set) {
      properties.putAll(profilePropertiesCache.get(p));
    }
    properties = Collections.unmodifiableMap(properties);
    profileSetPropertiesCache.put(set, properties);
    return properties;
  }

  @Override
  public Map<Node, PropertyInfo> getHeaderPropertiesAndDatatypes(String cimNamespace) {
    Objects.requireNonNull(cimNamespace, "cimNamespace");
    final var profile = headerProfilesByCimNamespace.get(cimNamespace);
    if (profile == null) {
      return Collections.emptyMap();
    }
    return profilePropertiesCache.get(profile);
  }

  @Override
  public Map<String, RDFDatatype> getPrimitiveToRdfDatatypeMapping() {
    return Collections.unmodifiableMap(primitiveToRdfDatatypeMap);
  }

  @Override
  public void registerPrimitiveType(String cimPrimitiveTypeName, RDFDatatype rdfDatatype) {
    Objects.requireNonNull(cimPrimitiveTypeName, "cimPrimitiveTypeName");
    Objects.requireNonNull(rdfDatatype, "rdfDatatype");
    primitiveToRdfDatatypeMap.put(cimPrimitiveTypeName, rdfDatatype);
  }

  private Map<Node, PropertyInfo> getTypedProperties(Graph g) {
    final var map = new HashMap<Node, PropertyInfo>(1024);
    QueryExec.graph(g)
        .query(typedPropertiesQuery)
        .select()
        .forEachRemaining(vars -> { // ?class ?property ?primitiveType ?referenceType
          final var rdfType = vars.get("rdfType");
          final var property = vars.get("property");
          final var cimDatatype = vars.get("cimDatatype");
          final var primitiveType = vars.get("primitiveType");
          final var referenceType = vars.get("referenceType");
          map.put(property, new PropertyInfo(
              rdfType,
              property,
              cimDatatype,
              primitiveType != null
                  ? getXsdDatatype(primitiveType.getLiteralLexicalForm())
                  : null,
              referenceType));
        });
    return Collections.unmodifiableMap(map);
  }

  private RDFDatatype getXsdDatatype(String primitiveType) {
    var dt = primitiveToRdfDatatypeMap.get(primitiveType);
    if (dt != null) {
      return dt;
    }
    errorHandler.warning("Unknown mapping from CIM primitive'" + primitiveType
        + "' to XSD datatype. Using xsd:string as fallback.", -1, -1);
    return XSDDatatype.XSDstring;
  }
}
