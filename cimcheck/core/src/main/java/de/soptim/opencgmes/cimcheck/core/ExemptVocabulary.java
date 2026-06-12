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

import java.util.List;

/**
 * Vocabulary namespaces whose terms are not validated against the CIM schema index.
 *
 * <p>Two kinds of namespace are handled here:</p>
 * <ul>
 *   <li><b>Open namespaces</b> ({@code xsd}, {@code dcterms}, {@code dc}, {@code skos},
 *       {@code dcat}, and the IEC extension namespaces) are accepted wholesale: any term in
 *       them is silently allowed. These are open-ended annotation / datatype vocabularies where
 *       enforcing a closed term list would produce false positives.</li>
 *   <li><b>Closed namespaces</b> ({@code rdf}, {@code rdfs}, {@code owl}, {@code sh}) are
 *       delegated to {@link StandardVocabulary}: a term is exempt only if it is a genuine,
 *       known term of that vocabulary. An <em>unknown</em> term in a closed namespace
 *       (e.g. {@code rdf:typ}) is <b>not</b> exempt — it flows through to the validator, which
 *       reports it as an unknown vocabulary term.</li>
 * </ul>
 *
 * <p>Used by both the SPARQL algebra visitor and the SHACL shape analyzer to identify terms
 * that should be accepted without a CIM existence check.</p>
 */
public final class ExemptVocabulary {

    /** Open annotation/datatype namespaces accepted wholesale, without a term-level check. */
    public static final List<String> NAMESPACES = List.of(
            "http://www.w3.org/2001/XMLSchema#",
            "http://www.w3.org/ns/dcat#",
            "http://purl.org/dc/terms/",
            "http://purl.org/dc/elements/1.1/",
            "http://www.w3.org/2004/02/skos/core#",
            "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#",
            "http://iec.ch/TC57/NonStandard/UML#"
    );

    private ExemptVocabulary() {}

    /**
     * Returns {@code true} when {@code node} should be accepted without a CIM existence check:
     * either it is in an open namespace, or it is a known term of a closed standard vocabulary.
     * An unknown term in a closed standard namespace returns {@code false} so it can be reported.
     */
    public static boolean isExempt(Node node) {
        if (!node.isURI()) return false;
        if (StandardVocabulary.isKnownTerm(node)) return true;
        String uri = node.getURI();
        for (String ns : NAMESPACES) {
            if (uri.startsWith(ns)) return true;
        }
        return false;
    }
}
