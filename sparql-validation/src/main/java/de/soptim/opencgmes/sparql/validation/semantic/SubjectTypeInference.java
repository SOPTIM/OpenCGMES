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

package de.soptim.opencgmes.sparql.validation.semantic;

import de.soptim.opencgmes.sparql.validation.analysis.TriplePatternReference;
import org.apache.jena.graph.Node;
import org.apache.jena.vocabulary.RDF;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a map of <em>explicitly declared</em> {@code rdf:type}s per subject inside a query.
 *
 * <p>The key is either a SPARQL variable {@link Node} or a constant URI {@link Node} subject.
 * The value is the set of class URIs the query asserts that subject to be.</p>
 *
 * <p>Only triples whose predicate is {@code rdf:type} and whose object is a URI are used.
 * Variable type assertions like {@code ?s a ?cls} are ignored.</p>
 */
public final class SubjectTypeInference {

    private static final Node RDF_TYPE = RDF.type.asNode();

    private SubjectTypeInference() {}

    /** @return immutable map subject → set of declared class URIs (never {@code null}). */
    public static Map<Node, Set<Node>> infer(List<TriplePatternReference> triples) {
        var out = new LinkedHashMap<Node, Set<Node>>();
        for (TriplePatternReference t : triples) {
            Node p = t.triple().getPredicate();
            if (!RDF_TYPE.equals(p)) continue;
            Node o = t.triple().getObject();
            if (!o.isURI()) continue;
            out.computeIfAbsent(t.triple().getSubject(), k -> new LinkedHashSet<>()).add(o);
        }
        return Map.copyOf(out);
    }
}
