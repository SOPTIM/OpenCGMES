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

package de.soptim.opencgmes.cimcheck.core.shacl;

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

    // ---- Shape-structure predicates --------------------------------------------------------

    /** {@code sh:sparql} — links a shape to a {@code sh:SPARQLConstraint} node. */
    public static final Node SPARQL      = NodeFactory.createURI(NS + "sparql");
    /** {@code sh:target} — links a shape to a {@code sh:SPARQLTarget} node. */
    public static final Node TARGET      = NodeFactory.createURI(NS + "target");
    /** {@code sh:validator} — links a constraint to a {@code sh:SPARQLAskValidator}. */
    public static final Node VALIDATOR   = NodeFactory.createURI(NS + "validator");
    /** {@code sh:rule} — links a shape to a {@code sh:SPARQLRule} node. */
    public static final Node RULE        = NodeFactory.createURI(NS + "rule");

    /** {@code sh:targetClass} — the class whose instances are focus nodes. */
    public static final Node TARGET_CLASS = NodeFactory.createURI(NS + "targetClass");
    /** {@code sh:property} — links a node shape to a property shape. */
    public static final Node PROPERTY     = NodeFactory.createURI(NS + "property");
    /** {@code sh:path} — the property path of a property shape. */
    public static final Node PATH         = NodeFactory.createURI(NS + "path");
    /** {@code sh:class} — the required class of the value nodes. */
    public static final Node CLASS        = NodeFactory.createURI(NS + "class");
    /** {@code sh:minCount} — minimum cardinality of the value nodes. */
    public static final Node MIN_COUNT    = NodeFactory.createURI(NS + "minCount");
    /** {@code sh:maxCount} — maximum cardinality of the value nodes. */
    public static final Node MAX_COUNT    = NodeFactory.createURI(NS + "maxCount");
    /** {@code sh:nodeKind} — expected RDF node kind. */
    public static final Node NODE_KIND    = NodeFactory.createURI(NS + "nodeKind");

    // ---- sh:nodeKind values ----------------------------------------------------------------

    /** Node kind: IRI nodes only. */
    public static final Node IRI                   = NodeFactory.createURI(NS + "IRI");
    /** Node kind: blank nodes only. */
    public static final Node BLANK_NODE            = NodeFactory.createURI(NS + "BlankNode");
    /** Node kind: literal nodes only. */
    public static final Node LITERAL               = NodeFactory.createURI(NS + "Literal");
    /** Node kind: blank nodes or IRI nodes. */
    public static final Node BLANK_NODE_OR_IRI     = NodeFactory.createURI(NS + "BlankNodeOrIRI");
    /** Node kind: blank nodes or literal nodes. */
    public static final Node BLANK_NODE_OR_LITERAL = NodeFactory.createURI(NS + "BlankNodeOrLiteral");
    /** Node kind: IRI nodes or literal nodes. */
    public static final Node IRI_OR_LITERAL        = NodeFactory.createURI(NS + "IRIOrLiteral");

    // ---- Property-path operators -----------------------------------------------------------

    /** {@code sh:inversePath} — inverse property path (object → subject). */
    public static final Node INVERSE_PATH      = NodeFactory.createURI(NS + "inversePath");
    /** {@code sh:alternativePath} — union path; value is an RDF list of alternatives. */
    public static final Node ALTERNATIVE_PATH  = NodeFactory.createURI(NS + "alternativePath");
    /** {@code sh:zeroOrMorePath} — {@code *} repetition. */
    public static final Node ZERO_OR_MORE_PATH = NodeFactory.createURI(NS + "zeroOrMorePath");
    /** {@code sh:oneOrMorePath} — {@code +} repetition. */
    public static final Node ONE_OR_MORE_PATH  = NodeFactory.createURI(NS + "oneOrMorePath");
    /** {@code sh:zeroOrOnePath} — {@code ?} repetition. */
    public static final Node ZERO_OR_ONE_PATH  = NodeFactory.createURI(NS + "zeroOrOnePath");
}
