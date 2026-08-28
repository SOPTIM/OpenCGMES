/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimvocabcheck.core.semantic;

import de.soptim.opencgmes.cimvocabcheck.core.SourceLocator;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationSeverity;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.TriplePatternReference;
import de.soptim.opencgmes.cimvocabcheck.core.schema.Multiplicity;
import de.soptim.opencgmes.cimvocabcheck.core.schema.SchemaIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;

/**
 * Checks that compare how a query matches a property against the CIM {@code cims:multiplicity}
 * declared for it.
 *
 * <p>Currently one check: {@link SparqlValidationCode#PROPERTY_MAY_BE_ABSENT} (INFO) — a property
 * whose lower bound is zero is matched in the query's mandatory clause. Because every triple
 * pattern outside {@code OPTIONAL} is an inner join, such a pattern silently removes every solution
 * whose subject simply does not carry that (optional) attribute. That is frequently intentional
 * ("only the units that declare a governor"), so this is a hint, never an error.
 *
 * <h2>Lenience policy</h2>
 *
 * <p>Roughly two thirds of the properties in a CGMES profile have a lower bound of zero, so an
 * unfiltered hint would annotate most lines of most queries. The check therefore fires only where
 * wrapping the pattern in {@code OPTIONAL} would actually be a meaningful edit:
 *
 * <ul>
 *   <li>The triple is matched, not produced — {@code CONSTRUCT}/{@code INSERT} template triples are
 *       skipped.
 *   <li>The triple is in the mandatory root clause. A triple inside {@code OPTIONAL}, a {@code
 *       UNION} branch, {@code MINUS} or {@code EXISTS} already sits in a scope the author chose.
 *   <li>The object is a plain variable. A constant or literal object is a deliberate value filter,
 *       where requiring the property to be present is the whole point.
 *   <li>That object variable is not constrained by a {@code FILTER}, {@code BIND} or {@code VALUES}
 *       — same reasoning: the query already depends on the value existing.
 *   <li>The subject is bound elsewhere in the mandatory clause (or is a constant IRI). If this
 *       pattern is the only thing binding the subject, making it optional would leave nothing to
 *       join against.
 * </ul>
 *
 * <p>As everywhere else in the engine, a schema that declares no multiplicity for the property
 * produces no finding.
 */
public final class MultiplicityChecks {

  static {
    // Jena 6 lazy init NPEs re-entrantly if a vocabulary constant is the first Jena class
    // touched; force a full init before the static Node constants below resolve.
    JenaSystem.init();
  }

  private static final Node RDF_TYPE = RDF.type.asNode();

  /** The mandatory root clause; see {@link TriplePatternReference}. */
  private static final List<Integer> ROOT_SCOPE = List.of(0);

  private MultiplicityChecks() {}

  /**
   * Runs the multiplicity checks over {@code triples}.
   *
   * @param triples the analyzed triple patterns, in source order
   * @param expressionVariables variables the query constrains in a {@code FILTER} / {@code BIND}
   *     expression or a {@code VALUES} block
   * @param schemaIndex the schema to read {@code cims:multiplicity} from
   * @param scopeResolver maps a triple's named-graph context to the profiles in scope for it
   * @param originalText the query source, used only to resolve line/column; may be {@code null}
   * @param prefixes the query's prefix mapping, used only for source location; may be {@code null}
   */
  public static List<SparqlValidationAnnotation> run(
      List<TriplePatternReference> triples,
      Set<Node> expressionVariables,
      SchemaIndex schemaIndex,
      Function<Node, Collection<VersionIri>> scopeResolver,
      String originalText,
      PrefixMapping prefixes) {

    var annotations = new ArrayList<SparqlValidationAnnotation>();
    Map<Node, Integer> mandatoryOccurrences = countMandatoryOccurrences(triples);
    var reported = new HashSet<PropertyOnSubject>();

    for (TriplePatternReference t : triples) {
      if (!isCandidate(t, expressionVariables, mandatoryOccurrences)) {
        continue;
      }
      Node subject = t.triple().getSubject();
      Node property = t.triple().getPredicate();
      Collection<VersionIri> scope = scopeResolver.apply(t.graph());
      Optional<Multiplicity> declared = schemaIndex.multiplicityOf(property, scope);
      if (declared.isEmpty() || declared.get().min() > 0) {
        continue;
      }
      if (!reported.add(new PropertyOnSubject(property, subject))) {
        continue; // the same attribute on the same subject — one hint is enough
      }
      SourceLocator.Location loc =
          SourceLocator.locateWithHint(originalText, property, prefixes, subject);
      annotations.add(
          new SparqlValidationAnnotation(
              SparqlValidationSeverity.INFO,
              loc.line(),
              loc.column(),
              "Property <"
                  + property.getURI()
                  + "> has multiplicity "
                  + declared.get().display()
                  + ", so matching it outside an OPTIONAL block drops every solution where "
                  + subjectLabel(subject)
                  + " has no value for it. Wrap the pattern in an OPTIONAL block to keep them.",
              SparqlValidationCode.PROPERTY_MAY_BE_ABSENT,
              property,
              List.copyOf(scope),
              List.of(),
              t.graph()));
    }
    return annotations;
  }

  /** Whether {@code t} passes every schema-independent precondition of the hint. */
  private static boolean isCandidate(
      TriplePatternReference t,
      Set<Node> expressionVariables,
      Map<Node, Integer> mandatoryOccurrences) {

    if (!t.isPattern() || !ROOT_SCOPE.equals(t.scopeChain())) {
      return false;
    }
    Triple triple = t.triple();
    Node property = triple.getPredicate();
    if (!property.isURI() || RDF_TYPE.equals(property)) {
      return false;
    }
    Node object = triple.getObject();
    if (!object.isVariable() || expressionVariables.contains(object)) {
      return false;
    }
    Node subject = triple.getSubject();
    return subject.isURI() || mandatoryOccurrences.getOrDefault(subject, 0) > 1;
  }

  /**
   * Counts, per term, the mandatory-clause triple patterns that mention it as subject or object. A
   * count above one means the term is bound somewhere other than the pattern under inspection.
   */
  private static Map<Node, Integer> countMandatoryOccurrences(
      List<TriplePatternReference> triples) {
    var counts = new HashMap<Node, Integer>();
    for (TriplePatternReference t : triples) {
      if (!t.isPattern() || !ROOT_SCOPE.equals(t.scopeChain())) {
        continue;
      }
      Node subject = t.triple().getSubject();
      Node object = t.triple().getObject();
      counts.merge(subject, 1, Integer::sum);
      if (!object.equals(subject)) {
        counts.merge(object, 1, Integer::sum);
      }
    }
    return counts;
  }

  private static String subjectLabel(Node s) {
    if (s.isVariable()) {
      return "?" + s.getName();
    }
    if (s.isURI()) {
      return "<" + s.getURI() + ">";
    }
    return s.toString();
  }

  /** De-duplication key: one hint per attribute per subject. */
  private record PropertyOnSubject(Node property, Node subject) {}
}
