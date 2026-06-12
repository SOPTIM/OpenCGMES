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

import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Closed standard-vocabulary term sets ({@code rdf}, {@code rdfs}, {@code owl}, {@code sh}),
 * loaded once from the canonical W3C vocabulary files bundled under {@code /vocab}.
 *
 * <p>Unlike {@link ExemptVocabulary} — which accepts <em>any</em> term in an open annotation
 * namespace ({@code xsd}, {@code dcterms}, {@code skos}, …) without inspection — this class
 * knows the exact set of terms each closed vocabulary defines. That lets the validator catch
 * typos such as {@code rdf:typ} or {@code sh:minCountt} while still accepting every genuine
 * term ({@code rdf:type}, {@code owl:Class}, {@code sh:minCount}).</p>
 *
 * <p>The term sets are derived authoritatively from the official vocabulary files rather than
 * hand-curated, so they cannot drift out of sync with the standards. Every in-namespace IRI that
 * appears anywhere in a vocabulary file is treated as a valid term; this maximises recall so a
 * valid-but-uncommon term is never falsely reported.</p>
 */
public final class StandardVocabulary {

    /** Closed-vocabulary namespace → short display name used in diagnostics. */
    private static final Map<String, String> CLOSED_NAMESPACES;
    static {
        var m = new LinkedHashMap<String, String>();
        m.put("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "RDF");
        m.put("http://www.w3.org/2000/01/rdf-schema#",       "RDFS");
        m.put("http://www.w3.org/2002/07/owl#",              "OWL");
        m.put("http://www.w3.org/ns/shacl#",                 "SHACL");
        CLOSED_NAMESPACES = Map.copyOf(m);
    }

    /** Bundled vocabulary files (classpath resources) parsed to derive the term sets. */
    private static final String[] RESOURCES = {
            "/vocab/rdf.ttl", "/vocab/rdfs.ttl", "/vocab/owl.ttl", "/vocab/shacl.ttl"
    };

    /** Every IRI that appears in a closed-vocabulary namespace across all bundled files. */
    private static final Set<String> KNOWN_TERMS = loadKnownTerms();

    private StandardVocabulary() {}

    /**
     * Returns the closed-vocabulary namespace {@code uri} belongs to, or {@code null} if it is
     * not in any closed standard namespace (it may still be in an open {@link ExemptVocabulary}
     * namespace or be a domain/CIM term).
     */
    public static String closedNamespaceOf(String uri) {
        if (uri == null) return null;
        for (String ns : CLOSED_NAMESPACES.keySet()) {
            if (uri.startsWith(ns)) return ns;
        }
        return null;
    }

    /** @return {@code true} iff {@code node} is a URI in a closed standard vocabulary namespace. */
    public static boolean isClosedNamespace(Node node) {
        return node.isURI() && closedNamespaceOf(node.getURI()) != null;
    }

    /** @return {@code true} iff {@code node} is a known, valid term of a closed standard vocabulary. */
    public static boolean isKnownTerm(Node node) {
        return node.isURI() && KNOWN_TERMS.contains(node.getURI());
    }

    /**
     * Short, human-readable name of the closed vocabulary {@code uri} belongs to
     * (e.g. {@code "RDF"}, {@code "SHACL"}), or {@code "standard"} if it is not in a closed
     * namespace (should not happen for callers that gate on {@link #closedNamespaceOf}).
     */
    public static String vocabularyName(String uri) {
        String ns = closedNamespaceOf(uri);
        return ns == null ? "standard" : CLOSED_NAMESPACES.get(ns);
    }

    private static Set<String> loadKnownTerms() {
        var terms = new HashSet<String>();
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
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load vocabulary resource: " + resource, e);
            }
        }
        return Set.copyOf(terms);
    }

    private static void collect(Set<String> terms, RDFNode node) {
        if (node != null && node.isURIResource()) {
            String uri = node.asResource().getURI();
            if (closedNamespaceOf(uri) != null) terms.add(uri);
        }
    }
}
