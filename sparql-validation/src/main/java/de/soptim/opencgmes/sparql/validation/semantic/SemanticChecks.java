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

import de.soptim.opencgmes.sparql.validation.SparqlValidationAnnotation;
import de.soptim.opencgmes.sparql.validation.SparqlValidationCode;
import de.soptim.opencgmes.sparql.validation.SparqlValidationSeverity;
import de.soptim.opencgmes.sparql.validation.VersionIri;
import de.soptim.opencgmes.sparql.validation.analysis.PathChainReference;
import de.soptim.opencgmes.sparql.validation.analysis.SparqlQueryAnalysis;
import de.soptim.opencgmes.sparql.validation.analysis.TriplePatternReference;
import de.soptim.opencgmes.sparql.validation.schema.SchemaIndex;
import org.apache.jena.graph.Node;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 3 — domain/range / datatype / chain checks that run <em>after</em> the Phase 1
 * existence checks. Each check is independent and emits its own annotations; nothing here
 * decides validation outcome by itself.
 *
 * <h2>What gets reported</h2>
 * <ul>
 *   <li><b>{@link SparqlValidationCode#PROPERTY_NOT_ALLOWED_FOR_CLASS} (ERROR)</b> —
 *       a triple uses a property whose {@code rdfs:domain} doesn't match the subject's
 *       declared class (modulo {@code rdfs:subClassOf}).</li>
 *   <li><b>{@link SparqlValidationCode#QUERY_IMPLIED_TYPE} (INFO)</b> —
 *       a variable subject has no explicit {@code rdf:type} but its lone property usage
 *       implies exactly one domain class.</li>
 *   <li><b>{@link SparqlValidationCode#DATATYPE_MISMATCH} (WARN)</b> —
 *       a literal object's datatype is incompatible with the property's {@code rdfs:range}.</li>
 *   <li><b>{@link SparqlValidationCode#PROPERTY_NOT_ALLOWED_FOR_CLASS} (ERROR)</b> on
 *       path chains — adjacent segments of a {@code p1/p2/…} chain whose range/domain
 *       sets are disjoint under subclass relaxation.</li>
 * </ul>
 *
 * <h2>Lenience policy</h2>
 * Checks bail out (no annotation) whenever the schema is silent — empty domain set, empty
 * range set, no inferred subject type. Better silent than wrong on incomplete profiles.
 */
public final class SemanticChecks {

    private static final Node RDF_TYPE = RDF.type.asNode();
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDF_LANG_STRING = RDF.langString.getURI();
    private static final String XSD_STRING = XSD_NS + "string";

    private SemanticChecks() {}

    public static List<SparqlValidationAnnotation> run(
            SparqlQueryAnalysis analysis,
            SchemaIndex schemaIndex,
            Function<Node, Collection<VersionIri>> scopeResolver) {
        return run(analysis, schemaIndex, scopeResolver, null, null);
    }

    /**
     * Phase 3 checks with source-location hints. The optional {@code originalQuery} and
     * {@code prefixes} are forwarded to {@link de.soptim.opencgmes.sparql.validation
     * .SourceLocator} so emitted annotations carry {@code line}/{@code column} when the
     * offending term can be found in the original text.
     */
    public static List<SparqlValidationAnnotation> run(
            SparqlQueryAnalysis analysis,
            SchemaIndex schemaIndex,
            Function<Node, Collection<VersionIri>> scopeResolver,
            String originalQuery,
            PrefixMapping prefixes) {

        var ctx = new Ctx(schemaIndex, scopeResolver, originalQuery, prefixes);
        var annotations = new ArrayList<SparqlValidationAnnotation>();
        Map<Node, Set<Node>> subjectTypes = SubjectTypeInference.infer(analysis.triples());

        for (TriplePatternReference t : analysis.triples()) {
            Node s = t.triple().getSubject();
            Node p = t.triple().getPredicate();
            Node o = t.triple().getObject();

            if (!p.isURI() || RDF_TYPE.equals(p)) continue;

            Collection<VersionIri> scope = scopeResolver.apply(t.graph());
            Set<Node> domains = schemaIndex.domainsOf(p, scope);
            Set<Node> ranges  = schemaIndex.rangesOf(p, scope);

            checkDomain(annotations, ctx, scope, subjectTypes, s, p, t.graph(), domains);
            checkLiteralRange(annotations, ctx, scope, p, o, t.graph(), ranges);
        }

        // Property path chain checks: for each adjacent (p1, p2) pair, verify
        // range(p1) ∩ domain(p2) is non-empty under subclass relaxation.
        for (PathChainReference chain : analysis.pathChains()) {
            Collection<VersionIri> scope = scopeResolver.apply(chain.graph());
            List<Node> seg = chain.segments();
            for (int i = 0; i < seg.size() - 1; i++) {
                Node p1 = seg.get(i);
                Node p2 = seg.get(i + 1);
                Set<Node> r1 = schemaIndex.rangesOf(p1, scope);
                Set<Node> d2 = schemaIndex.domainsOf(p2, scope);
                if (r1.isEmpty() || d2.isEmpty()) continue;
                if (!anySubclassMatch(r1, d2, schemaIndex, scope)) {
                    var loc = ctx.locate(p2);
                    annotations.add(new SparqlValidationAnnotation(
                            SparqlValidationSeverity.ERROR,
                            loc.line(), loc.column(),
                            "Property path chain incompatible: range of <" + p1.getURI()
                                    + "> " + setOfUris(r1)
                                    + " does not match domain of <" + p2.getURI()
                                    + "> " + setOfUris(d2) + ".",
                            SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS,
                            p2,
                            List.copyOf(scope),
                            List.of(),
                            chain.graph()));
                }
            }
        }

        return annotations;
    }

    /**
     * Bundle of inputs that don't change per-annotation. Avoids passing the same 4 args around.
     */
    private record Ctx(
            SchemaIndex schemaIndex,
            Function<Node, Collection<VersionIri>> scopeResolver,
            String originalQuery,
            PrefixMapping prefixes
    ) {
        de.soptim.opencgmes.sparql.validation.SourceLocator.Location locate(Node term) {
            return de.soptim.opencgmes.sparql.validation.SourceLocator.locate(
                    originalQuery, term, prefixes);
        }
    }

    // ---- Domain check + implied-type INFO --------------------------------------------------

    private static void checkDomain(
            List<SparqlValidationAnnotation> out,
            Ctx ctx,
            Collection<VersionIri> scope,
            Map<Node, Set<Node>> subjectTypes,
            Node subject, Node property, Node graph,
            Set<Node> domains) {

        if (domains.isEmpty()) return;

        Set<Node> declared = subjectTypes.getOrDefault(subject, Set.of());
        if (declared.isEmpty()) {
            // No explicit type → can't fault, but we can hint when the domain is unambiguous.
            if (domains.size() == 1) {
                Node only = domains.iterator().next();
                var loc = ctx.locate(property);
                out.add(new SparqlValidationAnnotation(
                        SparqlValidationSeverity.INFO,
                        loc.line(), loc.column(),
                        subjectLabel(subject) + " has no explicit rdf:type, "
                                + "but its use of <" + property.getURI() + "> implies <"
                                + only.getURI() + ">.",
                        SparqlValidationCode.QUERY_IMPLIED_TYPE,
                        only,
                        List.copyOf(scope),
                        List.of(),
                        graph));
            }
            return;
        }

        for (Node t : declared) {
            for (Node d : domains) {
                if (ctx.schemaIndex().isSubClassOf(t, d, scope)) return; // any match → fine
            }
        }
        // No declared type is a subclass of any domain → emit error.
        var loc = ctx.locate(property);
        out.add(new SparqlValidationAnnotation(
                SparqlValidationSeverity.ERROR,
                loc.line(), loc.column(),
                "Property <" + property.getURI() + "> is not allowed on "
                        + subjectLabel(subject) + " typed as " + setOfUris(declared)
                        + "; expected one of " + setOfUris(domains) + ".",
                SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS,
                property,
                List.copyOf(scope),
                List.of(),
                graph));
    }

    // ---- Range / datatype check ------------------------------------------------------------

    private static void checkLiteralRange(
            List<SparqlValidationAnnotation> out,
            Ctx ctx,
            Collection<VersionIri> scope,
            Node property, Node object, Node graph,
            Set<Node> ranges) {

        if (!object.isLiteral() || ranges.isEmpty()) return;

        // Only constrain when the range *includes a datatype*. Class-only ranges → IRI reference
        // expected, but we don't fault literal-vs-IRI here (Phase 3 stays lenient on the
        // "object exists in data" question).
        var rangeDatatypes = new LinkedHashSet<String>();
        for (Node r : ranges) {
            if (!r.isURI()) continue;
            String iri = r.getURI();
            if (isDatatypeIri(iri)) rangeDatatypes.add(iri);
        }
        if (rangeDatatypes.isEmpty()) return;

        String actual = object.getLiteralDatatypeURI();
        if (actual == null) actual = XSD_STRING; // SPARQL plain literal ≈ xsd:string

        for (String r : rangeDatatypes) {
            if (datatypesCompatible(actual, r)) return;
        }
        var loc = ctx.locate(property);
        out.add(new SparqlValidationAnnotation(
                SparqlValidationSeverity.WARN,
                loc.line(), loc.column(),
                "Literal " + literalLabel(object) + " for <" + property.getURI()
                        + "> has datatype <" + actual + "> but its rdfs:range is "
                        + quoteIris(rangeDatatypes) + ".",
                SparqlValidationCode.DATATYPE_MISMATCH,
                property,
                List.copyOf(scope),
                List.of(),
                graph));
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private static boolean anySubclassMatch(
            Set<Node> ranges, Set<Node> domains, SchemaIndex idx, Collection<VersionIri> scope) {
        for (Node r : ranges) {
            for (Node d : domains) {
                if (r.equals(d)) return true;
                if (idx.isSubClassOf(r, d, scope)) return true;
                // Also accept the reverse direction (Animal range × Person domain ≈ possibly ok).
                if (idx.isSubClassOf(d, r, scope)) return true;
            }
        }
        return false;
    }

    private static boolean isDatatypeIri(String iri) {
        return iri.startsWith(XSD_NS) || RDF_LANG_STRING.equals(iri);
    }

    /**
     * Cheap compatibility table — exact match, plus the "all numerics ≈ numerics" and
     * "all strings ≈ strings" buckets. Sub-byte precision distinctions are deliberately
     * ignored; that's a Phase 4 problem if anyone ever wants it.
     */
    private static boolean datatypesCompatible(String a, String b) {
        if (a.equals(b)) return true;
        if (isStringy(a) && isStringy(b)) return true;
        return isNumeric(a) && isNumeric(b);
    }

    private static boolean isStringy(String iri) {
        return iri.equals(XSD_STRING)
                || iri.equals(RDF_LANG_STRING)
                || iri.equals(XSD_NS + "normalizedString")
                || iri.equals(XSD_NS + "token");
    }

    private static boolean isNumeric(String iri) {
        if (!iri.startsWith(XSD_NS)) return false;
        String local = iri.substring(XSD_NS.length());
        return switch (local) {
            case "byte", "short", "int", "integer", "long",
                 "unsignedByte", "unsignedShort", "unsignedInt", "unsignedLong",
                 "negativeInteger", "nonNegativeInteger", "nonPositiveInteger", "positiveInteger",
                 "decimal", "float", "double" -> true;
            default -> false;
        };
    }

    private static String subjectLabel(Node s) {
        if (s.isVariable()) return "Variable ?" + s.getName();
        if (s.isURI()) return "Constant <" + s.getURI() + ">";
        return "Subject " + s;
    }

    private static String literalLabel(Node lit) {
        return "\"" + lit.getLiteralLexicalForm() + "\"";
    }

    private static String setOfUris(Collection<Node> nodes) {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (Node n : nodes) {
            if (!first) sb.append(", ");
            sb.append('<').append(n.isURI() ? n.getURI() : n.toString()).append('>');
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String quoteIris(Collection<String> uris) {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (String u : uris) {
            if (!first) sb.append(", ");
            sb.append('<').append(u).append('>');
            first = false;
        }
        return sb.append(']').toString();
    }
}
