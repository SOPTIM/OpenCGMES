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

package de.soptim.opencgmes.cimcheck.core.schema;

import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * Schema index built from raw RDFS/CIM profile graphs.
 *
 * <p>For each registered profile we walk the graph once and collect:
 *
 * <ul>
 *   <li><b>Classes:</b> subjects of {@code rdf:type rdfs:Class} / {@code owl:Class}, subjects of
 *       {@code rdfs:domain} (treated as candidate property domains), objects of {@code rdfs:domain}
 *       and {@code rdfs:range} (the classes they reference).
 *   <li><b>Properties:</b> subjects of {@code rdf:type rdf:Property} / {@code owl:ObjectProperty} /
 *       {@code owl:DatatypeProperty}, plus any subject with an {@code rdfs:domain} or {@code
 *       rdfs:range}.
 * </ul>
 *
 * <p>Use {@link #builder()} to add profiles. {@link #fromCimRegistry(CimProfileRegistry)} is a
 * convenience that reuses the property/class declarations already present in CIM profiles.
 */
public final class RdfsSchemaIndex implements SchemaIndex {

  private static final Node RDF_TYPE = RDF.type.asNode();
  private static final Node RDFS_CLASS = RDFS.Class.asNode();
  private static final Node OWL_CLASS = OWL2.Class.asNode();
  private static final Node RDF_PROPERTY = RDF.Property.asNode();
  private static final Node OWL_OBJECT_PROPERTY = OWL2.ObjectProperty.asNode();
  private static final Node OWL_DATATYPE_PROPERTY = OWL2.DatatypeProperty.asNode();
  private static final Node RDFS_DOMAIN = RDFS.domain.asNode();
  private static final Node RDFS_RANGE = RDFS.range.asNode();
  private static final Node RDFS_SUBCLASS_OF = RDFS.subClassOf.asNode();
  private static final Node RDFS_DATATYPE = RDFS.Datatype.asNode();
  private static final Node RDFS_LABEL = RDFS.label.asNode();
  private static final Node RDFS_COMMENT = RDFS.comment.asNode();

  private final Map<VersionIri, ProfileSchema> profiles;
  private final Map<Node, List<VersionIri>> classToProfiles;
  private final Map<Node, List<VersionIri>> propertyToProfiles;

  private RdfsSchemaIndex(Map<VersionIri, ProfileSchema> profiles) {
    this.profiles = Map.copyOf(profiles);
    var c = new LinkedHashMap<Node, List<VersionIri>>();
    var p = new LinkedHashMap<Node, List<VersionIri>>();
    for (var e : this.profiles.entrySet()) {
      for (var cls : e.getValue().classes()) {
        c.computeIfAbsent(cls, k -> new ArrayList<>()).add(e.getKey());
      }
      for (var prop : e.getValue().properties()) {
        p.computeIfAbsent(prop, k -> new ArrayList<>()).add(e.getKey());
      }
    }
    this.classToProfiles = deepImmutable(c);
    this.propertyToProfiles = deepImmutable(p);
  }

  private static <K, V> Map<K, List<V>> deepImmutable(Map<K, List<V>> in) {
    var out = new LinkedHashMap<K, List<V>>(in.size());
    for (var e : in.entrySet()) {
      out.put(e.getKey(), List.copyOf(e.getValue()));
    }
    return Map.copyOf(out);
  }

  @Override
  public boolean classExists(Node classUri, Collection<VersionIri> scope) {
    if (classUri == null || !classUri.isURI()) {
      return false;
    }
    var found = classToProfiles.get(classUri);
    if (found == null) {
      return false;
    }
    if (scope == null) {
      return true;
    }
    for (var p : found) {
      if (scope.contains(p)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean propertyExists(Node propertyUri, Collection<VersionIri> scope) {
    if (propertyUri == null || !propertyUri.isURI()) {
      return false;
    }
    var found = propertyToProfiles.get(propertyUri);
    if (found == null) {
      return false;
    }
    if (scope == null) {
      return true;
    }
    for (var p : found) {
      if (scope.contains(p)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public List<VersionIri> findClass(Node classUri) {
    if (classUri == null || !classUri.isURI()) {
      return List.of();
    }
    return classToProfiles.getOrDefault(classUri, List.of());
  }

  @Override
  public List<VersionIri> findProperty(Node propertyUri) {
    if (propertyUri == null || !propertyUri.isURI()) {
      return List.of();
    }
    return propertyToProfiles.getOrDefault(propertyUri, List.of());
  }

  @Override
  public List<VersionIri> getAllProfiles() {
    return List.copyOf(profiles.keySet());
  }

  @Override
  public Set<Node> allClasses() {
    return classToProfiles.keySet();
  }

  @Override
  public Set<Node> allProperties() {
    return propertyToProfiles.keySet();
  }

  /** Returns the indexed profile schemas keyed by version IRI. */
  public Map<VersionIri, ProfileSchema> profiles() {
    return profiles;
  }

  // ---- Semantic lookups ------------------------------------------------------------------

  @Override
  public Set<Node> domainsOf(Node propertyUri, Collection<VersionIri> scope) {
    return unionAcrossScope(scope, ps -> ps.propertyDomain().get(propertyUri));
  }

  @Override
  public Set<Node> rangesOf(Node propertyUri, Collection<VersionIri> scope) {
    return unionAcrossScope(scope, ps -> ps.propertyRange().get(propertyUri));
  }

  @Override
  public Set<Node> superClassesOf(Node classUri, Collection<VersionIri> scope) {
    if (classUri == null || !classUri.isURI()) {
      return Set.of();
    }
    var out = new LinkedHashSet<Node>();
    out.add(classUri);
    var queue = new ArrayDeque<Node>();
    queue.add(classUri);
    while (!queue.isEmpty()) {
      Node cur = queue.poll();
      Set<Node> direct = unionAcrossScope(scope, ps -> ps.subClassOf().get(cur));
      for (Node sup : direct) {
        if (out.add(sup)) {
          queue.add(sup);
        }
      }
    }
    return Set.copyOf(out);
  }

  @Override
  public boolean isSubClassOf(Node sub, Node sup, Collection<VersionIri> scope) {
    if (sub == null || sup == null) {
      return false;
    }
    if (sub.equals(sup)) {
      return true;
    }
    // Inline BFS to avoid building the full closure when an early hit is enough.
    var seen = new HashSet<Node>();
    var queue = new ArrayDeque<Node>();
    seen.add(sub);
    queue.add(sub);
    while (!queue.isEmpty()) {
      Node cur = queue.poll();
      Set<Node> direct = unionAcrossScope(scope, ps -> ps.subClassOf().get(cur));
      for (Node n : direct) {
        if (n.equals(sup)) {
          return true;
        }
        if (seen.add(n)) {
          queue.add(n);
        }
      }
    }
    return false;
  }

  @Override
  public java.util.Optional<String> labelOf(Node term, Collection<VersionIri> scope) {
    return firstStringAcrossScope(scope, ps -> ps.termLabels().get(term));
  }

  @Override
  public java.util.Optional<String> commentOf(Node term, Collection<VersionIri> scope) {
    return firstStringAcrossScope(scope, ps -> ps.termComments().get(term));
  }

  private java.util.Optional<String> firstStringAcrossScope(
      Collection<VersionIri> scope, Function<ProfileSchema, String> lookup) {
    Collection<VersionIri> effective = (scope == null) ? profiles.keySet() : scope;
    for (VersionIri v : effective) {
      ProfileSchema ps = profiles.get(v);
      if (ps == null) {
        continue;
      }
      String val = lookup.apply(ps);
      if (val != null && !val.isBlank()) {
        return java.util.Optional.of(val.strip());
      }
    }
    return java.util.Optional.empty();
  }

  /** Union of profile-local sets retrieved via {@code lookup}, over every profile in scope. */
  private Set<Node> unionAcrossScope(
      Collection<VersionIri> scope, Function<ProfileSchema, Set<Node>> lookup) {
    Collection<VersionIri> effective = (scope == null) ? profiles.keySet() : scope;
    var out = new LinkedHashSet<Node>();
    for (VersionIri v : effective) {
      ProfileSchema ps = profiles.get(v);
      if (ps == null) {
        continue;
      }
      Set<Node> sub = lookup.apply(ps);
      if (sub != null) {
        out.addAll(sub);
      }
    }
    return out.isEmpty() ? Set.of() : Set.copyOf(out);
  }

  /** Indexes one graph as the contents of profile {@code versionIri}. */
  public static ProfileSchema indexGraph(VersionIri versionIri, Graph graph) {
    var classes = new HashSet<Node>();
    var properties = new HashSet<Node>();
    var propertyDomain = new LinkedHashMap<Node, Set<Node>>();
    var propertyRange = new LinkedHashMap<Node, Set<Node>>();
    var subClassOf = new LinkedHashMap<Node, Set<Node>>();
    var termLabels = new LinkedHashMap<Node, String>();
    var termComments = new LinkedHashMap<Node, String>();
    var it = graph.find(Node.ANY, Node.ANY, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        Node s = t.getSubject();
        Node p = t.getPredicate();
        Node o = t.getObject();

        if (RDF_TYPE.equals(p) && o.isURI()) {
          if (RDFS_CLASS.equals(o) || OWL_CLASS.equals(o) || RDFS_DATATYPE.equals(o)) {
            if (s.isURI()) {
              classes.add(s);
            }
          } else if ((RDF_PROPERTY.equals(o)
                  || OWL_OBJECT_PROPERTY.equals(o)
                  || OWL_DATATYPE_PROPERTY.equals(o))
              && s.isURI()) {
            properties.add(s);
          }
        } else if (RDFS_DOMAIN.equals(p)) {
          if (s.isURI()) {
            properties.add(s);
            if (o.isURI()) {
              propertyDomain.computeIfAbsent(s, k -> new HashSet<>()).add(o);
            }
          }
          if (o.isURI()) {
            classes.add(o);
          }
        } else if (RDFS_RANGE.equals(p)) {
          if (s.isURI()) {
            properties.add(s);
            if (o.isURI()) {
              propertyRange.computeIfAbsent(s, k -> new HashSet<>()).add(o);
            }
          }
          if (o.isURI() && !isDatatypeUri(o.getURI())) {
            classes.add(o);
          }
        } else if (RDFS_SUBCLASS_OF.equals(p)) {
          if (s.isURI()) {
            classes.add(s);
            if (o.isURI()) {
              classes.add(o);
              subClassOf.computeIfAbsent(s, k -> new HashSet<>()).add(o);
            }
          }
        } else if (RDFS_LABEL.equals(p)) {
          if (s.isURI() && o.isLiteral()) {
            termLabels.putIfAbsent(s, o.getLiteralLexicalForm());
          }
        } else if (RDFS_COMMENT.equals(p) && s.isURI() && o.isLiteral()) {
          termComments.putIfAbsent(s, o.getLiteralLexicalForm());
        }
      }
    } finally {
      it.close();
    }
    return new ProfileSchema(
        versionIri,
        classes,
        properties,
        propertyDomain,
        propertyRange,
        subClassOf,
        termLabels,
        termComments);
  }

  /** Returns a new {@link Builder} for assembling an index. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Convenience: build an index from every profile registered in a CIM profile registry.
   *
   * <p>Each CIM profile may declare multiple {@code owl:versionIRI}s. We register each version IRI
   * as its own logical profile, sharing the same underlying graph index.
   *
   * <p>For each profile we run {@link #indexGraph} for the generic baseline (classes, {@code
   * rdfs:subClassOf}, etc.) and then overlay the CIM-specific {@link
   * CimProfileRegistry.PropertyInfo} map. The overlay is the only source of property <em>range</em>
   * information for CIM profiles, because CIM uses {@code cims:dataType} (which the registry
   * resolves to an XSD {@link org.apache.jena.datatypes.RDFDatatype}) rather than {@code
   * rdfs:range}. Without this step, {@code DATATYPE_MISMATCH} cannot fire on real CIM data.
   */
  public static RdfsSchemaIndex fromCimRegistry(CimProfileRegistry registry) {
    var b = builder();
    for (CimProfile profile : registry.getRegisteredProfiles()) {
      if (profile.isHeaderProfile()) {
        continue; // header profiles are not addressable by version IRI
      }
      Set<Node> versionIris = profile.getOwlVersionIRIs();
      Map<Node, CimProfileRegistry.PropertyInfo> infos =
          registry.getPropertiesAndDatatypes(versionIris);
      for (Node iriNode : versionIris) {
        VersionIri v = new VersionIri(iriNode);
        ProfileSchema baseline = indexGraph(v, profile);
        b.addProfile(enrichWithCimPropertyInfo(v, baseline, infos));
      }
    }
    return b.build();
  }

  /**
   * Merge the generic {@link #indexGraph} baseline with the CIM property-info map. Adds domain
   * assertions (from {@link CimProfileRegistry.PropertyInfo#rdfType()}) and range assertions (from
   * {@link CimProfileRegistry.PropertyInfo#primitiveType()} as an XSD URI or {@link
   * CimProfileRegistry.PropertyInfo#referenceType()} as a class URI).
   */
  private static ProfileSchema enrichWithCimPropertyInfo(
      VersionIri v, ProfileSchema baseline, Map<Node, CimProfileRegistry.PropertyInfo> infos) {

    if (infos == null || infos.isEmpty()) {
      return baseline;
    }

    var classes = new LinkedHashSet<>(baseline.classes());
    var properties = new LinkedHashSet<>(baseline.properties());
    var propertyDomain = mutableCopy(baseline.propertyDomain());
    var propertyRange = mutableCopy(baseline.propertyRange());

    for (var info : infos.values()) {
      Node prop = info.property();
      if (prop == null) {
        continue;
      }
      properties.add(prop);

      if (info.rdfType() != null && info.rdfType().isURI()) {
        propertyDomain.computeIfAbsent(prop, k -> new LinkedHashSet<>()).add(info.rdfType());
        classes.add(info.rdfType());
      }

      if (info.primitiveType() != null) {
        Node xsd = NodeFactory.createURI(info.primitiveType().getURI());
        propertyRange.computeIfAbsent(prop, k -> new LinkedHashSet<>()).add(xsd);
      } else if (info.referenceType() != null && info.referenceType().isURI()) {
        propertyRange.computeIfAbsent(prop, k -> new LinkedHashSet<>()).add(info.referenceType());
        classes.add(info.referenceType());
      }
    }

    return new ProfileSchema(
        v,
        classes,
        properties,
        propertyDomain,
        propertyRange,
        baseline.subClassOf(),
        baseline.termLabels(),
        baseline.termComments());
  }

  private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
  private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

  private static boolean isDatatypeUri(String iri) {
    return iri.startsWith(XSD_NS) || iri.equals(RDF_NS + "langString");
  }

  private static Map<Node, Set<Node>> mutableCopy(Map<Node, Set<Node>> in) {
    var out = new LinkedHashMap<Node, Set<Node>>(in.size());
    for (var e : in.entrySet()) {
      out.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
    }
    return out;
  }

  /** Builder for adding raw profile graphs. */
  public static final class Builder {
    private final Map<VersionIri, ProfileSchema> profiles = new LinkedHashMap<>();

    /** Adds a profile indexed from the given RDFS {@code graph}. */
    public Builder addProfile(VersionIri versionIri, Graph graph) {
      profiles.put(versionIri, indexGraph(versionIri, graph));
      return this;
    }

    /** Adds an already-indexed {@link ProfileSchema}. */
    public Builder addProfile(ProfileSchema schema) {
      profiles.put(schema.versionIri(), schema);
      return this;
    }

    /** Adds a minimal profile from the given class and property nodes. */
    public Builder addProfile(String iri, Set<Node> classes, Set<Node> properties) {
      var v = VersionIri.of(iri);
      profiles.put(v, ProfileSchema.minimal(v, classes, properties));
      return this;
    }

    /** Adds a minimal profile from the given class and property IRIs. */
    public Builder addProfile(String iri, Iterable<String> classes, Iterable<String> properties) {
      var v = VersionIri.of(iri);
      var c = new HashSet<Node>();
      var p = new HashSet<Node>();
      for (String s : classes) {
        c.add(NodeFactory.createURI(s));
      }
      for (String s : properties) {
        p.add(NodeFactory.createURI(s));
      }
      profiles.put(v, ProfileSchema.minimal(v, c, p));
      return this;
    }

    /**
     * Rich addProfile for tests / programmatic schema construction with explicit domain, range and
     * subClassOf maps.
     */
    public Builder addProfile(
        String iri,
        Iterable<String> classes,
        Iterable<String> properties,
        Map<String, ? extends Iterable<String>> propertyDomain,
        Map<String, ? extends Iterable<String>> propertyRange,
        Map<String, ? extends Iterable<String>> subClassOf) {
      var v = VersionIri.of(iri);
      var c = new HashSet<Node>();
      var p = new HashSet<Node>();
      for (String s : classes) {
        c.add(NodeFactory.createURI(s));
      }
      for (String s : properties) {
        p.add(NodeFactory.createURI(s));
      }
      profiles.put(
          v,
          new ProfileSchema(
              v,
              c,
              p,
              toNodeMap(propertyDomain),
              toNodeMap(propertyRange),
              toNodeMap(subClassOf),
              Map.of(),
              Map.of()));
      return this;
    }

    private static Map<Node, Set<Node>> toNodeMap(Map<String, ? extends Iterable<String>> in) {
      if (in == null) {
        return Map.of();
      }
      var out = new LinkedHashMap<Node, Set<Node>>();
      for (var e : in.entrySet()) {
        var set = new HashSet<Node>();
        for (String s : e.getValue()) {
          set.add(NodeFactory.createURI(s));
        }
        out.put(NodeFactory.createURI(e.getKey()), set);
      }
      return out;
    }

    /** Builds the immutable {@link RdfsSchemaIndex}. */
    public RdfsSchemaIndex build() {
      return new RdfsSchemaIndex(profiles);
    }
  }
}
