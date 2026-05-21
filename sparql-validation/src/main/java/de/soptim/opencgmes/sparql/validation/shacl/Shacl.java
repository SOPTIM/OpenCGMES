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

package de.soptim.opencgmes.sparql.validation.shacl;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * SHACL vocabulary constants needed by the SPARQL extractor.
 *
 * <p>We deliberately don't pull in {@code jena-shacl} for this — the validator does not
 * <em>execute</em> SHACL, it only reads the few predicates that carry embedded SPARQL.</p>
 */
public final class Shacl {

    private Shacl() {}

    public static final String NS = "http://www.w3.org/ns/shacl#";

    /** Subject is a SHACL container with a SPARQL SELECT query (target, constraint, validator). */
    public static final Node SELECT     = NodeFactory.createURI(NS + "select");
    /** Subject is a SHACL container with a SPARQL ASK query (validator). */
    public static final Node ASK        = NodeFactory.createURI(NS + "ask");
    /** Subject is a SHACL container with a SPARQL CONSTRUCT query (rule). */
    public static final Node CONSTRUCT  = NodeFactory.createURI(NS + "construct");

    /** Subject is a SHACL prefix-declaration set; object carries {@code sh:declare} blank nodes. */
    public static final Node PREFIXES   = NodeFactory.createURI(NS + "prefixes");
    /** {@code ?ontology sh:declare ?bnode} — one prefix definition. */
    public static final Node DECLARE    = NodeFactory.createURI(NS + "declare");
    /** {@code ?bnode sh:prefix "p"} — the short prefix string. */
    public static final Node PREFIX     = NodeFactory.createURI(NS + "prefix");
    /** {@code ?bnode sh:namespace "http://..."^^xsd:anyURI} — the namespace IRI. */
    public static final Node NAMESPACE  = NodeFactory.createURI(NS + "namespace");
}
