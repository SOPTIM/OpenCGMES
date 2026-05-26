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
 * Standard W3C/IETF/IEC vocabulary namespaces whose terms are never validated against the CIM
 * schema index. Terms from these namespaces are correct by definition and do not appear as
 * properties or classes in CIM profile RDFS files.
 *
 * <p>Used by both the SPARQL algebra visitor and the SHACL shape analyzer to identify
 * terms that should be silently accepted without an existence check.</p>
 */
public final class ExemptVocabulary {

    public static final List<String> NAMESPACES = List.of(
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#",
            "http://www.w3.org/2002/07/owl#",
            "http://www.w3.org/2001/XMLSchema#",
            "http://www.w3.org/ns/shacl#",
            "http://www.w3.org/ns/dcat#",
            "http://purl.org/dc/terms/",
            "http://purl.org/dc/elements/1.1/",
            "http://www.w3.org/2004/02/skos/core#",
            "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#",
            "http://iec.ch/TC57/NonStandard/UML#"
    );

    private ExemptVocabulary() {}

    /** Returns {@code true} when {@code node} is a URI in one of the exempt namespaces. */
    public static boolean isExempt(Node node) {
        if (!node.isURI()) return false;
        String uri = node.getURI();
        for (String ns : NAMESPACES) {
            if (uri.startsWith(ns)) return true;
        }
        return false;
    }
}
