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

package de.soptim.opencgmes.sparql.validation.schema;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.sparql.validation.VersionIri;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema index built from raw RDFS/CIM profile graphs.
 *
 * <p>For each registered profile we walk the graph once and collect:</p>
 * <ul>
 *   <li><b>Classes:</b> subjects of {@code rdf:type rdfs:Class} / {@code owl:Class}, subjects of
 *       {@code rdfs:domain} (treated as candidate property domains), objects of {@code rdfs:domain}
 *       and {@code rdfs:range} (the classes they reference).</li>
 *   <li><b>Properties:</b> subjects of {@code rdf:type rdf:Property} / {@code owl:ObjectProperty}
 *       / {@code owl:DatatypeProperty}, plus any subject with an {@code rdfs:domain} or
 *       {@code rdfs:range}.</li>
 * </ul>
 *
 * <p>Use {@link #builder()} to add profiles. {@link #fromCimRegistry(CimProfileRegistry)} is
 * a convenience that reuses the property/class declarations already present in CIM profiles.</p>
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
        for (var e : in.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        return Map.copyOf(out);
    }

    @Override
    public boolean classExists(Node classUri, Collection<VersionIri> scope) {
        if (classUri == null || !classUri.isURI()) return false;
        var found = classToProfiles.get(classUri);
        if (found == null) return false;
        if (scope == null) return true;
        for (var p : found) {
            if (scope.contains(p)) return true;
        }
        return false;
    }

    @Override
    public boolean propertyExists(Node propertyUri, Collection<VersionIri> scope) {
        if (propertyUri == null || !propertyUri.isURI()) return false;
        var found = propertyToProfiles.get(propertyUri);
        if (found == null) return false;
        if (scope == null) return true;
        for (var p : found) {
            if (scope.contains(p)) return true;
        }
        return false;
    }

    @Override
    public List<VersionIri> findClass(Node classUri) {
        if (classUri == null || !classUri.isURI()) return List.of();
        return classToProfiles.getOrDefault(classUri, List.of());
    }

    @Override
    public List<VersionIri> findProperty(Node propertyUri) {
        if (propertyUri == null || !propertyUri.isURI()) return List.of();
        return propertyToProfiles.getOrDefault(propertyUri, List.of());
    }

    @Override
    public List<VersionIri> getAllProfiles() {
        return List.copyOf(profiles.keySet());
    }

    public Map<VersionIri, ProfileSchema> profiles() {
        return profiles;
    }

    /** Indexes one graph as the contents of profile {@code versionIri}. */
    public static ProfileSchema indexGraph(VersionIri versionIri, Graph graph) {
        var classes = new HashSet<Node>();
        var properties = new HashSet<Node>();
        var it = graph.find(Node.ANY, Node.ANY, Node.ANY);
        try {
            while (it.hasNext()) {
                Triple t = it.next();
                Node s = t.getSubject();
                Node p = t.getPredicate();
                Node o = t.getObject();

                if (RDF_TYPE.equals(p) && o.isURI()) {
                    if (RDFS_CLASS.equals(o) || OWL_CLASS.equals(o)) {
                        if (s.isURI()) classes.add(s);
                    } else if (RDF_PROPERTY.equals(o)
                            || OWL_OBJECT_PROPERTY.equals(o)
                            || OWL_DATATYPE_PROPERTY.equals(o)) {
                        if (s.isURI()) properties.add(s);
                    }
                } else if (RDFS_DOMAIN.equals(p)) {
                    if (s.isURI()) properties.add(s);
                    if (o.isURI()) classes.add(o);
                } else if (RDFS_RANGE.equals(p)) {
                    if (s.isURI()) properties.add(s);
                    if (o.isURI()) classes.add(o);
                }
            }
        } finally {
            it.close();
        }
        return new ProfileSchema(versionIri, classes, properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience: build an index from every profile registered in a CIM profile registry.
     *
     * <p>Each CIM profile may declare multiple {@code owl:versionIRI}s. We register each
     * version IRI as its own logical profile, sharing the same underlying graph index.</p>
     */
    public static RdfsSchemaIndex fromCimRegistry(CimProfileRegistry registry) {
        var b = builder();
        for (CimProfile profile : registry.getRegisteredProfiles()) {
            if (profile.isHeaderProfile()) {
                continue; // header profiles are not addressable by version IRI
            }
            for (Node iriNode : profile.getOwlVersionIRIs()) {
                b.addProfile(new VersionIri(iriNode), profile);
            }
        }
        return b.build();
    }

    /** Builder for adding raw profile graphs. */
    public static final class Builder {
        private final Map<VersionIri, ProfileSchema> profiles = new LinkedHashMap<>();

        public Builder addProfile(VersionIri versionIri, Graph graph) {
            profiles.put(versionIri, indexGraph(versionIri, graph));
            return this;
        }

        public Builder addProfile(ProfileSchema schema) {
            profiles.put(schema.versionIri(), schema);
            return this;
        }

        public Builder addProfile(String iri, Set<Node> classes, Set<Node> properties) {
            var v = VersionIri.of(iri);
            profiles.put(v, new ProfileSchema(v, classes, properties));
            return this;
        }

        public Builder addProfile(String iri, Iterable<String> classes, Iterable<String> properties) {
            var v = VersionIri.of(iri);
            var c = new HashSet<Node>();
            var p = new HashSet<Node>();
            for (String s : classes) c.add(NodeFactory.createURI(s));
            for (String s : properties) p.add(NodeFactory.createURI(s));
            profiles.put(v, new ProfileSchema(v, c, p));
            return this;
        }

        public RdfsSchemaIndex build() {
            return new RdfsSchemaIndex(profiles);
        }
    }
}
