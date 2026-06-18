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

package de.soptim.opencgmes.cimvocabcheck.core;

import de.soptim.opencgmes.cimvocabcheck.core.analysis.ClassReference;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.GraphReference;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.PathChainReference;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.PropertyReference;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.SparqlQueryAnalysis;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.SparqlQueryAnalyzer;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.SparqlUpdateAnalysis;
import de.soptim.opencgmes.cimvocabcheck.core.analysis.TriplePatternReference;
import de.soptim.opencgmes.cimvocabcheck.core.explain.QueryPlanFormatter;
import de.soptim.opencgmes.cimvocabcheck.core.schema.SchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.schema.ValidationScope;
import de.soptim.opencgmes.cimvocabcheck.core.semantic.SemanticChecks;
import de.soptim.opencgmes.cimvocabcheck.core.semantic.SubjectTypeInference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;

/**
 * Static SPARQL validator: combines a {@link SparqlQueryAnalyzer} with a {@link SchemaIndex} to
 * produce a {@link SparqlValidationResult}.
 *
 * <p>The class is the engine behind {@link SparqlValidationApi}; tests and other modules may use it
 * directly to inspect intermediate analyses or to plug in a custom schema index.
 */
public final class SparqlQueryValidator {

  private final SchemaIndex schemaIndex;
  private final boolean checkStandardVocabulary;
  private final SparqlQueryAnalyzer analyzer = new SparqlQueryAnalyzer();

  /** Validator that checks closed standard-vocabulary terms (rdf/rdfs/owl/sh) for typos. */
  public SparqlQueryValidator(SchemaIndex schemaIndex) {
    this(schemaIndex, true);
  }

  /**
   * Creates a validator over {@code schemaIndex}.
   *
   * @param checkStandardVocabulary when {@code false}, unknown terms in the closed standard
   *     vocabularies are silently accepted (the legacy "ignore" behaviour) instead of being
   *     reported as {@link SparqlValidationCode#UNKNOWN_VOCABULARY_TERM}.
   */
  public SparqlQueryValidator(SchemaIndex schemaIndex, boolean checkStandardVocabulary) {
    this.schemaIndex = Objects.requireNonNull(schemaIndex, "schemaIndex");
    this.checkStandardVocabulary = checkStandardVocabulary;
  }

  /** Returns the {@link SchemaIndex} this validator checks against. */
  public SchemaIndex schemaIndex() {
    return schemaIndex;
  }

  private static final Node RDF_TYPE = RDF.type.asNode();

  /** Runs analysis + validation against {@code scope}. */
  public SparqlValidationResult validate(String query, ValidationScope scope) {
    return validate(query, scope, Map.of());
  }

  /**
   * Variant that injects additional subject-type hints before the semantic checks.
   *
   * <p>Each entry {@code variable → {class1, class2, ...}} asserts that variable's type for the
   * purpose of domain/range checking. A hint for a subject that already has an explicit {@code
   * rdf:type} triple in the query is silently ignored — the query-declared type takes precedence.
   *
   * <p>The canonical use case is SHACL: the {@code $this} variable in an embedded SPARQL constraint
   * carries the type declared by the enclosing shape's {@code sh:targetClass}.
   */
  public SparqlValidationResult validate(
      String query, ValidationScope scope, Map<Node, Set<Node>> subjectTypeHints) {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(scope, "scope");
    try {
      Query parsed = analyzer.parse(query);
      return validate(parsed, query, scope, subjectTypeHints);
    } catch (InvalidQueryException e) {
      return new SparqlValidationResult(query, null, List.of(syntaxAnnotation(e)));
    }
  }

  /** Validates an already-parsed query without re-parsing it. */
  public SparqlValidationResult validate(Query query, String originalText, ValidationScope scope) {
    return validate(query, originalText, scope, Map.of());
  }

  /**
   * Validates an already-parsed query without re-parsing it. {@code originalText} is the source
   * string the query was parsed from; it is used only for diagnostic line/column resolution. See
   * {@link #validate(String, ValidationScope, Map)} for the subject-type-hint contract.
   */
  public SparqlValidationResult validate(
      Query query,
      String originalText,
      ValidationScope scope,
      Map<Node, Set<Node>> subjectTypeHints) {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(originalText, "originalText");
    Objects.requireNonNull(scope, "scope");
    SparqlQueryAnalysis a = analyzer.analyze(query);
    String plan = QueryPlanFormatter.format(a.query(), a.algebra());
    List<TriplePatternReference> triples = augmentWithTypeHints(a.triples(), subjectTypeHints);
    var refs =
        new AnalysisRefs(
            a.graphs(),
            a.classes(),
            a.properties(),
            triples,
            a.pathChains(),
            a.dynamicPredicate(),
            a.dynamicClass());
    List<SparqlValidationAnnotation> ann =
        validateReferences(refs, scope, a.query().getPrefixMapping(), originalText);
    return new SparqlValidationResult(originalText, plan, ann);
  }

  /**
   * Returns the original triple list extended with synthetic {@code ?var rdf:type <cls>} patterns
   * for each hint entry whose subject variable has no already-declared type. Returns the original
   * list unchanged when no injection is needed.
   */
  private static List<TriplePatternReference> augmentWithTypeHints(
      List<TriplePatternReference> triples, Map<Node, Set<Node>> hints) {
    if (hints == null || hints.isEmpty()) {
      return triples;
    }
    // Only suppress injection when the variable has a definite type in root scope (0).
    // A type that appears only inside an OPTIONAL or UNION branch is uncertain and must
    // not prevent the target-class hint from being applied to the mandatory root clause.
    Map<Integer, Map<Node, Set<Node>>> scoped = SubjectTypeInference.inferScoped(triples);
    Map<Node, Set<Node>> rootTypes = scoped.getOrDefault(0, Map.of());
    var extra = new ArrayList<TriplePatternReference>();
    for (var entry : hints.entrySet()) {
      if (rootTypes.containsKey(entry.getKey())) {
        continue; // explicit root-scope type wins
      }
      for (Node cls : entry.getValue()) {
        extra.add(new TriplePatternReference(Triple.create(entry.getKey(), RDF_TYPE, cls), null));
      }
    }
    if (extra.isEmpty()) {
      return triples;
    }
    var augmented = new ArrayList<>(triples);
    augmented.addAll(extra);
    return Collections.unmodifiableList(augmented);
  }

  /**
   * Parses and validates a SPARQL Update request (one or more operations separated by {@code ;}).
   * The {@code queryPlan} in the result is {@code null} — update requests have no algebra plan.
   */
  public SparqlValidationResult validateUpdate(String updateText, ValidationScope scope) {
    Objects.requireNonNull(updateText, "updateText");
    Objects.requireNonNull(scope, "scope");
    try {
      SparqlUpdateAnalysis a = analyzer.analyzeUpdate(updateText);
      var refs =
          new AnalysisRefs(
              a.graphs(),
              a.classes(),
              a.properties(),
              a.triples(),
              a.pathChains(),
              a.dynamicPredicate(),
              a.dynamicClass());
      List<SparqlValidationAnnotation> ann =
          validateReferences(refs, scope, a.updateRequest().getPrefixMapping(), updateText);
      return new SparqlValidationResult(updateText, null, ann);
    } catch (InvalidQueryException e) {
      return new SparqlValidationResult(updateText, null, List.of(syntaxAnnotation(e)));
    }
  }

  /** Returns the underlying analysis; throws if the query is unparseable. */
  public SparqlQueryAnalysis analyze(String query) throws InvalidQueryException {
    return analyzer.analyze(query);
  }

  /** Returns the underlying update analysis; throws if the update text is unparseable. */
  public SparqlUpdateAnalysis analyzeUpdate(String updateText) throws InvalidQueryException {
    return analyzer.analyzeUpdate(updateText);
  }

  // ---- internal validation ---------------------------------------------------------------

  /**
   * The analysis-derived references validated by {@link #validateReferences}. Bundles the seven
   * collections/flags that both the SPARQL query and SPARQL Update paths feed into validation.
   */
  private record AnalysisRefs(
      List<GraphReference> graphs,
      List<ClassReference> classes,
      List<PropertyReference> properties,
      List<TriplePatternReference> triples,
      List<PathChainReference> pathChains,
      boolean dynamicPredicate,
      boolean dynamicClass) {}

  /** Core validation logic shared by both SPARQL query and SPARQL Update paths. */
  private List<SparqlValidationAnnotation> validateReferences(
      AnalysisRefs refs, ValidationScope scope, PrefixMapping prefixes, String original) {

    List<GraphReference> graphs = refs.graphs();
    boolean dynamicPredicate = refs.dynamicPredicate();
    boolean dynamicClass = refs.dynamicClass();

    var annotations = new ArrayList<SparqlValidationAnnotation>();

    // 1. Graphs used but not configured (NamedGraphProfileScope only).
    if (scope instanceof ValidationScope.NamedGraphProfileScope ngs) {
      var configured = ngs.namedGraphsToProfiles().keySet();
      var seen = new LinkedHashSet<Node>();
      for (GraphReference gr : graphs) {
        if (!seen.add(gr.graph())) {
          continue;
        }
        if (!configured.contains(gr.graph())) {
          annotations.add(
              new SparqlValidationAnnotation(
                  SparqlValidationSeverity.WARN,
                  null,
                  null,
                  "Graph <"
                      + gr.graph().getURI()
                      + "> is used but no "
                      + "schema profiles were configured for it.",
                  SparqlValidationCode.GRAPH_NOT_CONFIGURED,
                  gr.graph(),
                  List.of(),
                  List.of(),
                  gr.graph()));
        }
      }
    }

    // 2. Dynamic predicates/classes — informational warnings.
    if (dynamicPredicate) {
      annotations.add(
          new SparqlValidationAnnotation(
              SparqlValidationSeverity.WARN,
              null,
              null,
              "Query contains triple(s) with a variable predicate; "
                  + "static property validation is skipped for those.",
              SparqlValidationCode.UNSUPPORTED_DYNAMIC_PROPERTY,
              null,
              scopeProfiles(scope, null),
              List.of(),
              null));
    }
    if (dynamicClass) {
      annotations.add(
          new SparqlValidationAnnotation(
              SparqlValidationSeverity.WARN,
              null,
              null,
              "Query contains triple(s) with a variable rdf:type object; "
                  + "static class validation is skipped for those.",
              SparqlValidationCode.UNSUPPORTED_DYNAMIC_PROPERTY,
              null,
              scopeProfiles(scope, null),
              List.of(),
              null));
    }

    // 3. Classes.
    for (ClassReference c : refs.classes()) {
      if (StandardVocabulary.isClosedNamespace(c.classNode())) {
        addVocabularyAnnotation(annotations, c.classNode(), c.graph(), original, prefixes);
        continue;
      }
      Collection<VersionIri> selected = scopeProfiles(scope, c.graph());
      if (schemaIndex.classExists(c.classNode(), selected)) {
        continue;
      }
      List<VersionIri> elsewhere = schemaIndex.findClass(c.classNode());
      var elsewhereOutOfScope = subtract(elsewhere, selected);
      annotations.add(
          buildAnnotation(
              SparqlValidationSeverity.ERROR,
              SparqlValidationCode.UNKNOWN_CLASS,
              c.classNode(),
              c.graph(),
              selected,
              elsewhereOutOfScope,
              original,
              prefixes,
              formatMissingTermMessage(
                  "Class", c.classNode(), c.graph(), selected, elsewhereOutOfScope)));
    }

    // 4. Properties.
    for (PropertyReference p : refs.properties()) {
      if (StandardVocabulary.isClosedNamespace(p.propertyNode())) {
        addVocabularyAnnotation(annotations, p.propertyNode(), p.graph(), original, prefixes);
        continue;
      }
      Collection<VersionIri> selected = scopeProfiles(scope, p.graph());
      if (schemaIndex.propertyExists(p.propertyNode(), selected)) {
        continue;
      }
      List<VersionIri> elsewhere = schemaIndex.findProperty(p.propertyNode());
      var elsewhereOutOfScope = subtract(elsewhere, selected);
      annotations.add(
          buildAnnotation(
              SparqlValidationSeverity.ERROR,
              SparqlValidationCode.UNKNOWN_PROPERTY,
              p.propertyNode(),
              p.graph(),
              selected,
              elsewhereOutOfScope,
              original,
              prefixes,
              formatMissingTermMessage(
                  "Property", p.propertyNode(), p.graph(), selected, elsewhereOutOfScope)));
    }

    // 5. Semantic checks: domain/range/datatype/path-chain.
    annotations.addAll(
        SemanticChecks.run(
            refs.triples(),
            refs.pathChains(),
            schemaIndex,
            g -> scopeProfiles(scope, g),
            original,
            prefixes));

    return annotations;
  }

  // ---- scope resolution ------------------------------------------------------------------

  /**
   * Profiles in scope for a term encountered inside (or outside) a given graph context.
   *
   * <p>For {@link ValidationScope.NamedGraphProfileScope}: a non-null graph picks the profile list
   * mapped to that graph; a null graph falls back to the union of all configured profiles.
   */
  Collection<VersionIri> scopeProfiles(ValidationScope scope, Node graph) {
    return switch (scope) {
      case ValidationScope.AllProfilesScope ignored -> schemaIndex.getAllProfiles();
      case ValidationScope.ProfileListScope l -> l.profiles();
      case ValidationScope.NamedGraphProfileScope ngs -> {
        if (graph != null && graph.isURI()) {
          var hit = ngs.namedGraphsToProfiles().get(graph);
          if (hit != null) {
            yield hit;
          }
          // URI graph used but not configured — no schema scope; nothing exists.
          yield List.of();
        }
        // Default-graph context or variable/blank graph: union of all configured profiles.
        var union = new LinkedHashSet<VersionIri>();
        for (var v : ngs.namedGraphsToProfiles().values()) {
          union.addAll(v);
        }
        yield List.copyOf(union);
      }
    };
  }

  /**
   * Emits an {@link SparqlValidationCode#UNKNOWN_VOCABULARY_TERM} annotation for an unknown term in
   * a closed standard vocabulary (rdf/rdfs/owl/sh). No-op when standard-vocabulary checking is
   * disabled. The term is known to be invalid here: genuine vocabulary terms are filtered out at
   * analysis time by {@link ExemptVocabulary}, so only typos reach this point.
   */
  private void addVocabularyAnnotation(
      List<SparqlValidationAnnotation> annotations,
      Node term,
      Node graph,
      String original,
      PrefixMapping prefixes) {
    if (!checkStandardVocabulary) {
      return;
    }
    String vocab = StandardVocabulary.vocabularyName(term.getURI());
    annotations.add(
        buildAnnotation(
            SparqlValidationSeverity.ERROR,
            SparqlValidationCode.UNKNOWN_VOCABULARY_TERM,
            term,
            graph,
            List.of(),
            List.of(),
            original,
            prefixes,
            "<" + term.getURI() + "> is not a term in the " + vocab + " vocabulary."));
  }

  // ---- message rendering -----------------------------------------------------------------

  /**
   * Formats a "&lt;term&gt; does not exist" message for a missing class or property.
   *
   * @param kind {@code "Class"} or {@code "Property"} — the leading noun of the message
   */
  private static String formatMissingTermMessage(
      String kind,
      Node term,
      Node graph,
      Collection<VersionIri> selected,
      Collection<VersionIri> elsewhere) {
    var msg =
        new StringBuilder(kind).append(" <").append(term.getURI()).append("> does not exist in ");
    appendScopeLabel(msg, graph, selected);
    msg.append('.');
    if (!elsewhere.isEmpty()) {
      msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
      IriFormat.appendIris(msg, elsewhere);
      msg.append('.');
    }
    return msg.toString();
  }

  private static void appendScopeLabel(
      StringBuilder msg, Node graph, Collection<VersionIri> selected) {
    if (graph != null) {
      msg.append("graph ").append(graph.isURI() ? "<" + graph.getURI() + ">" : graph).append(" / ");
    }
    if (selected.isEmpty()) {
      msg.append("selected schema/profile scope (empty)");
    } else {
      msg.append("selected profile").append(selected.size() == 1 ? " " : "s ");
      IriFormat.appendIris(msg, selected);
    }
  }

  private static SparqlValidationAnnotation buildAnnotation(
      SparqlValidationSeverity severity,
      SparqlValidationCode code,
      Node term,
      Node graph,
      Collection<VersionIri> selected,
      Collection<VersionIri> elsewhere,
      String original,
      PrefixMapping prefixes,
      String message) {
    var loc = SourceLocator.locate(original, term, prefixes);
    return new SparqlValidationAnnotation(
        severity, loc.line(), loc.column(), message, code, term, selected, elsewhere, graph);
  }

  private static List<VersionIri> subtract(List<VersionIri> a, Collection<VersionIri> b) {
    if (a.isEmpty()) {
      return List.of();
    }
    if (b == null || b.isEmpty()) {
      return List.copyOf(a);
    }
    var out = new ArrayList<VersionIri>(a.size());
    for (VersionIri v : a) {
      if (!b.contains(v)) {
        out.add(v);
      }
    }
    return Collections.unmodifiableList(out);
  }

  private static SparqlValidationAnnotation syntaxAnnotation(InvalidQueryException e) {
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        e.line(),
        e.column(),
        e.getMessage(),
        SparqlValidationCode.SYNTAX_ERROR,
        null,
        List.of(),
        List.of(),
        null);
  }
}
