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

package de.soptim.opencgmes.cimcheck.core;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

/**
 * Closed standard-vocabulary term sets ({@code rdf}, {@code rdfs}, {@code owl}, {@code sh}), loaded
 * once from the canonical W3C vocabulary files bundled under {@code /vocab}.
 *
 * <p>Unlike {@link ExemptVocabulary} — which accepts <em>any</em> term in an open annotation
 * namespace ({@code xsd}, {@code dcterms}, {@code skos}, …) without inspection — this class knows
 * the exact set of terms each closed vocabulary defines. That lets the validator catch typos such
 * as {@code rdf:typ} or {@code sh:minCountt} while still accepting every genuine term ({@code
 * rdf:type}, {@code owl:Class}, {@code sh:minCount}).
 *
 * <p>The term sets are derived authoritatively from the official vocabulary files rather than
 * hand-curated, so they cannot drift out of sync with the standards. Every in-namespace IRI that
 * appears anywhere in a vocabulary file is treated as a valid term; this maximises recall so a
 * valid-but-uncommon term is never falsely reported.
 */
public final class StandardVocabulary {

  /** Closed-vocabulary namespace → short display name used in diagnostics. */
  private static final Map<String, String> CLOSED_NAMESPACES;

  static {
    var m = new LinkedHashMap<String, String>();
    m.put("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "RDF");
    m.put("http://www.w3.org/2000/01/rdf-schema#", "RDFS");
    m.put("http://www.w3.org/2002/07/owl#", "OWL");
    m.put("http://www.w3.org/ns/shacl#", "SHACL");
    CLOSED_NAMESPACES = Map.copyOf(m);
  }

  /** Bundled vocabulary files (classpath resources) parsed to derive the term sets. */
  private static final String[] RESOURCES = {
    "/vocab/rdf.ttl", "/vocab/rdfs.ttl", "/vocab/owl.ttl", "/vocab/shacl.ttl"
  };

  /** {@code rdf:type} objects that mark a subject as a class, for completion categorisation. */
  private static final Set<String> CLASS_TYPES =
      Set.of("http://www.w3.org/2000/01/rdf-schema#Class", "http://www.w3.org/2002/07/owl#Class");

  /** {@code rdf:type} objects that mark a subject as a property, for completion categorisation. */
  private static final Set<String> PROPERTY_TYPES =
      Set.of(
          "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property",
          "http://www.w3.org/2002/07/owl#ObjectProperty",
          "http://www.w3.org/2002/07/owl#DatatypeProperty",
          "http://www.w3.org/2002/07/owl#AnnotationProperty",
          "http://www.w3.org/2002/07/owl#OntologyProperty",
          "http://www.w3.org/2002/07/owl#FunctionalProperty",
          "http://www.w3.org/2002/07/owl#InverseFunctionalProperty",
          "http://www.w3.org/2002/07/owl#TransitiveProperty",
          "http://www.w3.org/2002/07/owl#SymmetricProperty",
          "http://www.w3.org/2002/07/owl#AsymmetricProperty",
          "http://www.w3.org/2002/07/owl#ReflexiveProperty",
          "http://www.w3.org/2002/07/owl#IrreflexiveProperty");

  /** All loaded term sets, computed once from the bundled vocabulary files. */
  private static final Loaded LOADED = load();

  /** Every IRI that appears in a closed-vocabulary namespace across all bundled files. */
  private static final Set<String> KNOWN_TERMS = LOADED.terms;

  /** Closed-vocabulary terms typed as classes — completion candidates in class position. */
  private static final Set<Node> CLASS_NODES = LOADED.classes;

  /** Closed-vocabulary terms typed as properties — completion candidates in predicate position. */
  private static final Set<Node> PROPERTY_NODES = LOADED.properties;

  private StandardVocabulary() {}

  /** Returns closed-vocabulary terms declared as classes (e.g. {@code sh:NodeShape}). */
  public static Set<Node> classNodes() {
    return CLASS_NODES;
  }

  /** Returns closed-vocabulary terms declared as properties (e.g. {@code sh:path}). */
  public static Set<Node> propertyNodes() {
    return PROPERTY_NODES;
  }

  /** Returns whether {@code namespace} is exactly one of the closed standard namespaces. */
  public static boolean isClosedNamespaceUri(String namespace) {
    return namespace != null && CLOSED_NAMESPACES.containsKey(namespace);
  }

  /**
   * Returns the closed-vocabulary namespace {@code uri} belongs to, or {@code null} if it is not in
   * any closed standard namespace (it may still be in an open {@link ExemptVocabulary} namespace or
   * be a domain/CIM term).
   */
  public static String closedNamespaceOf(String uri) {
    if (uri == null) {
      return null;
    }
    for (String ns : CLOSED_NAMESPACES.keySet()) {
      if (uri.startsWith(ns)) {
        return ns;
      }
    }
    return null;
  }

  /** Returns whether {@code node} is a URI in a closed standard vocabulary namespace. */
  public static boolean isClosedNamespace(Node node) {
    return node.isURI() && closedNamespaceOf(node.getURI()) != null;
  }

  /** Returns whether {@code node} is a known, valid term of a closed standard vocabulary. */
  public static boolean isKnownTerm(Node node) {
    return node.isURI() && KNOWN_TERMS.contains(node.getURI());
  }

  /**
   * Short, human-readable name of the closed vocabulary {@code uri} belongs to (e.g. {@code "RDF"},
   * {@code "SHACL"}), or {@code "standard"} if it is not in a closed namespace (should not happen
   * for callers that gate on {@link #closedNamespaceOf}).
   */
  public static String vocabularyName(String uri) {
    String ns = closedNamespaceOf(uri);
    return ns == null ? "standard" : CLOSED_NAMESPACES.get(ns);
  }

  /** The three derived term sets, returned together from a single parse of the bundled files. */
  private record Loaded(Set<String> terms, Set<Node> classes, Set<Node> properties) {}

  private static Loaded load() {
    var terms = new HashSet<String>();
    var classes = new HashSet<Node>();
    var properties = new HashSet<Node>();
    for (String resource : RESOURCES) {
      try (InputStream in = StandardVocabulary.class.getResourceAsStream(resource)) {
        if (in == null) {
          throw new IllegalStateException("Missing bundled vocabulary resource: " + resource);
        }
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, in, Lang.TURTLE);
        StmtIterator it = model.listStatements();
        while (it.hasNext()) {
          Statement s = it.nextStatement();
          collect(terms, s.getSubject());
          collect(terms, s.getPredicate());
          collect(terms, s.getObject());
          classify(classes, properties, s);
        }
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException("Failed to load vocabulary resource: " + resource, e);
      }
    }
    return new Loaded(Set.copyOf(terms), Set.copyOf(classes), Set.copyOf(properties));
  }

  private static void collect(Set<String> terms, RDFNode node) {
    if (node != null && node.isURIResource()) {
      String uri = node.asResource().getURI();
      if (closedNamespaceOf(uri) != null) {
        terms.add(uri);
      }
    }
  }

  /**
   * Categorises a closed-namespace subject as a class or property from its {@code rdf:type}, for
   * completion. Terms that are neither (e.g. the {@code sh:IRI} node-kind individual) are not added
   * to either set, so they are simply not offered as completions.
   */
  private static void classify(Set<Node> classes, Set<Node> properties, Statement s) {
    if (!RDF.type.asNode().equals(s.getPredicate().asNode())) {
      return;
    }
    if (!s.getSubject().isURIResource() || !s.getObject().isURIResource()) {
      return;
    }
    String subjectUri = s.getSubject().getURI();
    if (closedNamespaceOf(subjectUri) == null) {
      return;
    }
    String typeUri = s.getObject().asResource().getURI();
    if (CLASS_TYPES.contains(typeUri)) {
      classes.add(NodeFactory.createURI(subjectUri));
    } else if (PROPERTY_TYPES.contains(typeUri)) {
      properties.add(NodeFactory.createURI(subjectUri));
    }
  }
}
