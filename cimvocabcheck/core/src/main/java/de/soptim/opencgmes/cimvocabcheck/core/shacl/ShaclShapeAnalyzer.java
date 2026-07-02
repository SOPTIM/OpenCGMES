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

package de.soptim.opencgmes.cimvocabcheck.core.shacl;

import de.soptim.opencgmes.cimvocabcheck.core.ExemptVocabulary;
import de.soptim.opencgmes.cimvocabcheck.core.IriFormat;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlQueryValidator;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationSeverity;
import de.soptim.opencgmes.cimvocabcheck.core.StandardVocabulary;
import de.soptim.opencgmes.cimvocabcheck.core.TermResolver;
import de.soptim.opencgmes.cimvocabcheck.core.TermResolver.Classification;
import de.soptim.opencgmes.cimvocabcheck.core.TermResolver.Role;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.schema.SchemaIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.vocabulary.RDF;

/**
 * Static analysis of SHACL shape structure against a CIM schema.
 *
 * <p>Validates the shape-level predicates that reference CIM terms directly — without touching any
 * embedded SPARQL (which is the job of {@link ShaclSparqlExtractor} + {@link
 * SparqlQueryValidator}):
 *
 * <ul>
 *   <li>{@code sh:targetClass} — the focus-node class must exist in the schema.
 *   <li>{@code sh:class} — the required value class must exist in the schema; and must not be used
 *       on a property whose {@code rdfs:range} is a literal datatype.
 *   <li>{@code sh:path} — every URI segment of a property path (simple, sequence, inverse,
 *       alternative, zero/one/more) must be a known CIM property.
 *   <li>{@code sh:nodeKind} — must be compatible with the property's {@code rdfs:range}.
 *   <li>{@code sh:datatype} — must not be used on a property whose {@code rdfs:range} is a class
 *       (object property).
 * </ul>
 *
 * <p>Three kinds of term are deliberately <em>not</em> checked against the CIM schema, to avoid
 * false positives: standard-vocabulary classes (e.g. {@code rdf:List}, {@code rdfs:Resource}) in
 * {@code sh:class}/{@code sh:targetClass} position; {@code sh:path}/{@code sh:class} appearing
 * inside a custom constraint component's {@code sh:parameter} or validator (these declare a
 * parameter, not a CIM data property/class); and terms the shapes document declares itself (any URI
 * subject of an {@code rdf:type} triple). An unknown term in a closed standard namespace is still
 * reported — as a vocabulary typo ({@link SparqlValidationCode#UNKNOWN_VOCABULARY_TERM}).
 *
 * <p>The analyzer is stateless and thread-safe.
 */
public final class ShaclShapeAnalyzer {

  private static final Node RDF_FIRST = RDF.first.asNode();
  private static final Node RDF_REST = RDF.rest.asNode();
  private static final Node RDF_NIL = RDF.nil.asNode();

  private static final List<Node> REPEAT_PATH_PREDICATES =
      List.of(Shacl.ZERO_OR_MORE_PATH, Shacl.ONE_OR_MORE_PATH, Shacl.ZERO_OR_ONE_PATH);

  /**
   * Predicates whose objects are <em>internals</em> of a custom constraint component — parameter
   * declarations and validators. {@code sh:path}/{@code sh:class} appearing on such nodes name a
   * parameter or a parameter's accepted value type, not a CIM data property/class, so they must not
   * be checked against the CIM schema.
   */
  private static final List<Node> COMPONENT_INTERNAL_LINKS =
      List.of(Shacl.PARAMETER, Shacl.VALIDATOR, Shacl.NODE_VALIDATOR, Shacl.PROPERTY_VALIDATOR);

  /** Predicates whose URI object names a CIM property used as a validation target. */
  private static final List<Node> TARGET_PREDICATES =
      List.of(Shacl.TARGET_SUBJECTS_OF, Shacl.TARGET_OBJECTS_OF);

  /** Property-pair constraint predicates whose URI object names another CIM property. */
  private static final List<Node> PROPERTY_PAIR_PREDICATES =
      List.of(Shacl.EQUALS, Shacl.DISJOINT, Shacl.LESS_THAN, Shacl.LESS_THAN_OR_EQUALS);

  private final SchemaIndex schemaIndex;
  private final TermResolver termResolver;
  private final boolean checkStandardVocabulary;

  /** Creates an analyzer over {@code schemaIndex} with standard-vocabulary checking enabled. */
  public ShaclShapeAnalyzer(SchemaIndex schemaIndex) {
    this(schemaIndex, true);
  }

  /**
   * Creates an analyzer over {@code schemaIndex}.
   *
   * @param checkStandardVocabulary when {@code false}, unknown terms in the closed standard
   *     vocabularies (rdf/rdfs/owl/sh) are silently accepted instead of being reported as {@link
   *     SparqlValidationCode#UNKNOWN_VOCABULARY_TERM}.
   */
  public ShaclShapeAnalyzer(SchemaIndex schemaIndex, boolean checkStandardVocabulary) {
    this.schemaIndex = schemaIndex;
    this.termResolver = new TermResolver(schemaIndex);
    this.checkStandardVocabulary = checkStandardVocabulary;
  }

  /**
   * Analyses {@code shapesGraph} and returns one annotation per structural problem found: unknown
   * CIM terms, nodeKind/range mismatches, and cardinality contradictions.
   */
  public List<SparqlValidationAnnotation> analyze(Graph shapesGraph, Collection<VersionIri> scope) {
    var out = new ArrayList<SparqlValidationAnnotation>();

    Set<Node> localDefs = collectLocalDefinitions(shapesGraph);
    Set<Node> componentInternals = collectComponentInternalShapes(shapesGraph);
    Set<Node> deactivated = collectDeactivatedShapes(shapesGraph);

    checkClassReferences(
        shapesGraph,
        Shacl.TARGET_CLASS,
        "sh:targetClass",
        scope,
        localDefs,
        componentInternals,
        deactivated,
        out);
    checkClassReferences(
        shapesGraph,
        Shacl.CLASS,
        "sh:class",
        scope,
        localDefs,
        componentInternals,
        deactivated,
        out);
    checkPropertyShapes(shapesGraph, scope, localDefs, componentInternals, deactivated, out);
    checkInLists(shapesGraph, scope, localDefs, deactivated, out);
    checkHasValue(shapesGraph, scope, localDefs, deactivated, out);
    checkValueRanges(shapesGraph, deactivated, out);
    checkDatatypeVocabulary(shapesGraph, deactivated, out);
    checkPropertyRefPredicates(shapesGraph, TARGET_PREDICATES, scope, localDefs, deactivated, out);
    checkPropertyRefPredicates(
        shapesGraph, PROPERTY_PAIR_PREDICATES, scope, localDefs, deactivated, out);
    checkIgnoredProperties(shapesGraph, scope, localDefs, deactivated, out);
    checkVocabularyTerms(shapesGraph, checkStandardVocabulary, out);

    return List.copyOf(out);
  }

  /**
   * Collects IRIs that the shapes document <em>defines itself</em> — every URI subject of an {@code
   * rdf:type} triple. CIM terms referenced by shapes ({@code sh:targetClass}/{@code sh:path}
   * values) appear in object position, not as typed subjects, so a genuine CIM typo is still
   * reported; but a helper class or property declared in the same file (e.g. a custom {@code
   * :myProperty a rdf:Property}) is treated as known rather than flagged as missing from CIM.
   */
  private static Set<Node> collectLocalDefinitions(Graph g) {
    var defs = new HashSet<Node>();
    var it = g.find(Node.ANY, RDF.type.asNode(), Node.ANY);
    try {
      while (it.hasNext()) {
        Node s = it.next().getSubject();
        if (s.isURI()) {
          defs.add(s);
        }
      }
    } finally {
      closeQuietly(it);
    }
    return defs;
  }

  /**
   * Collects the nodes that are objects of {@link #COMPONENT_INTERNAL_LINKS} — the parameter and
   * validator nodes of custom constraint components. {@code sh:path}/{@code sh:class} on these
   * nodes are component internals, not CIM data-graph references, and are excluded from CIM checks.
   */
  private static Set<Node> collectComponentInternalShapes(Graph g) {
    var out = new HashSet<Node>();
    for (Node link : COMPONENT_INTERNAL_LINKS) {
      forEachObject(g, link, out::add);
    }
    return out;
  }

  /**
   * Runs <em>only</em> the schema-independent vocabulary-typo check on {@code shapesGraph}.
   *
   * <p>Unlike {@link #analyze(Graph, Collection)}, this needs no schema and no profile scope —
   * {@code sh:}/{@code rdf:}/{@code rdfs:}/{@code owl:} terms are validated against the bundled W3C
   * vocabularies alone. It is the right check for the syntax-only fallback (e.g. when an endpoint
   * schema is unreachable), so a typo such as {@code sh:taaargetClass} is still reported rather
   * than silently ignored.
   */
  public static List<SparqlValidationAnnotation> checkVocabularyOnly(Graph shapesGraph) {
    var out = new ArrayList<SparqlValidationAnnotation>();
    checkVocabularyTerms(shapesGraph, true, out);
    return List.copyOf(out);
  }

  /**
   * Flags typos in closed standard vocabularies ({@code rdf}/{@code rdfs}/{@code owl}/{@code sh})
   * used anywhere in the shapes graph — in subject, predicate or object position — e.g. {@code
   * sh:minCountt}, {@code sh:NodeShap}, {@code sh:nodeKind sh:IRII}, {@code sh:severity
   * sh:Violatio}. Each distinct unknown term is reported once. Known terms, curated header
   * extensions ({@code rdf:Statements.subject}), open namespaces (xsd, dcterms, …) and CIM terms
   * are left alone; the latter are validated by the targeted shape checks above.
   *
   * <p>Terms occupying {@code sh:targetClass}/{@code sh:class} object position or a {@code sh:path}
   * leaf are already validated (and, when they are typos, reported) by the targeted checks, so they
   * are skipped here to avoid double reporting. Schema-independent.
   */
  private static void checkVocabularyTerms(
      Graph g, boolean checkStandardVocabulary, List<SparqlValidationAnnotation> out) {
    if (!checkStandardVocabulary) {
      return;
    }
    Set<Node> targeted = collectTargetedTerms(g);
    var seen = new HashSet<Node>();
    var it = g.find(Node.ANY, Node.ANY, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        checkVocabularyTerm(t.getSubject(), "Subject", targeted, seen, out);
        checkVocabularyTerm(t.getPredicate(), "Predicate", targeted, seen, out);
        checkVocabularyTerm(t.getObject(), "Object", targeted, seen, out);
      }
    } finally {
      closeQuietly(it);
    }
  }

  /**
   * Collects the URI terms already validated by the targeted shape checks — {@code
   * sh:targetClass}/{@code sh:class} objects and {@code sh:path} leaves — so the graph-wide
   * vocabulary scan does not report them a second time.
   */
  private static Set<Node> collectTargetedTerms(Graph g) {
    var covered = new HashSet<Node>();
    Consumer<Node> addUri =
        n -> {
          if (n.isURI()) {
            covered.add(n);
          }
        };
    forEachObject(g, Shacl.TARGET_CLASS, addUri);
    forEachObject(g, Shacl.CLASS, addUri);
    var pathLeaves = new ArrayList<Node>();
    forEachObject(g, Shacl.PATH, pathNode -> extractPropertyUris(g, pathNode, pathLeaves));
    covered.addAll(pathLeaves);
    return covered;
  }

  /**
   * Reports {@code term} as an unknown vocabulary term if it is an unknown closed-namespace URI not
   * already covered by a targeted check, once.
   */
  private static void checkVocabularyTerm(
      Node term,
      String context,
      Set<Node> targeted,
      Set<Node> seen,
      List<SparqlValidationAnnotation> out) {
    if (!term.isURI() || targeted.contains(term)) {
      return;
    }
    if (TermResolver.vocabularyClassification(term) == Classification.VOCAB_TYPO
        && seen.add(term)) {
      addVocabularyAnnotation(term, context, out);
    }
  }

  // ---- Shape-structure dependency extraction (no scope / no validation) -----------------

  /**
   * Returns all class URI nodes referenced in shape-structural positions ({@code sh:targetClass},
   * {@code sh:class}) — regardless of whether they exist in any profile. Use this for dependency
   * tracking without validation.
   */
  public Set<Node> extractClassDependencies(Graph shapesGraph) {
    var out = new LinkedHashSet<Node>();
    forEachObject(
        shapesGraph,
        Shacl.TARGET_CLASS,
        n -> {
          if (n.isURI()) {
            out.add(n);
          }
        });
    forEachObject(
        shapesGraph,
        Shacl.CLASS,
        n -> {
          if (n.isURI()) {
            out.add(n);
          }
        });
    return out;
  }

  /**
   * Returns all property URI nodes referenced in {@code sh:path} expressions — regardless of
   * whether they exist in any profile. Use this for dependency tracking without validation.
   */
  public Set<Node> extractPropertyDependencies(Graph shapesGraph) {
    var out = new LinkedHashSet<Node>();
    forEachObject(
        shapesGraph,
        Shacl.PATH,
        pathNode -> {
          var props = new ArrayList<Node>();
          extractPropertyUris(shapesGraph, pathNode, props);
          props.stream().filter(Predicate.not(ExemptVocabulary::isExempt)).forEach(out::add);
        });
    return out;
  }

  // ---- sh:targetClass / sh:class ---------------------------------------------------------

  private void checkClassReferences(
      Graph g,
      Node predicate,
      String predicateLabel,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      Set<Node> componentInternals,
      Set<Node> deactivated,
      List<SparqlValidationAnnotation> out) {

    var it = g.find(Node.ANY, predicate, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        Node cls = t.getObject();
        if (!cls.isURI()) {
          continue;
        }
        // A deactivated shape and its constraints are ignored.
        if (deactivated.contains(t.getSubject())) {
          continue;
        }
        // Inside a constraint-component parameter/validator, sh:class names the accepted value
        // type of a parameter, not a CIM focus/value class — don't check it against CIM.
        if (componentInternals.contains(t.getSubject())) {
          continue;
        }
        Classification kind = termResolver.classify(cls, Role.CLASS, scope, localDefs);
        // Known standard/open/header term, a local definition, or a genuine CIM class: accept.
        if (TermResolver.isAccepted(kind, Role.CLASS)) {
          continue;
        }
        // Unknown term in a closed standard namespace (e.g. sh:Fooo, rdf:Lst): a vocabulary typo,
        // not a missing CIM class — report it as such rather than as UNKNOWN_CLASS.
        if (kind == Classification.VOCAB_TYPO) {
          addVocabularyAnnotation(cls, "Shape " + predicateLabel, out);
          continue;
        }
        out.add(classAnnotation(cls, predicateLabel, scope, schemaIndex.findClass(cls)));
      }
    } finally {
      closeQuietly(it);
    }
  }

  // ---- per-property-shape checks (sh:path, sh:nodeKind, sh:minCount/sh:maxCount) ----------

  /**
   * Combined loop over every property shape (subject of {@code sh:path}). Runs three checks per
   * shape: property existence, nodeKind/range compatibility, cardinality.
   */
  private void checkPropertyShapes(
      Graph g,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      Set<Node> componentInternals,
      Set<Node> deactivated,
      List<SparqlValidationAnnotation> out) {

    var it = g.find(Node.ANY, Shacl.PATH, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        Node shape = t.getSubject();
        Node pathNode = t.getObject();

        // A deactivated shape and its constraints are ignored.
        if (deactivated.contains(shape)) {
          continue;
        }

        // On a constraint-component parameter/validator, sh:path declares a parameter name rather
        // than a CIM data-property path: skip both the property-existence and the range checks.
        boolean internal = componentInternals.contains(shape);

        // 1. Unknown property in path
        if (!internal) {
          checkPathPropertyExistence(g, pathNode, scope, localDefs, out);
        }

        // 2. Range-compatibility checks (only for simple single-URI paths)
        if (!internal && pathNode.isURI()) {
          checkNodeKind(g, shape, pathNode, scope, out);
          checkDatatypeVsRange(g, shape, pathNode, scope, out);
          checkClassVsRange(g, shape, pathNode, scope, out);
        }

        // 3. sh:minCount / sh:maxCount contradiction (meaningful for parameters too)
        checkCardinality(g, shape, pathNode, out);
      }
    } finally {
      closeQuietly(it);
    }
  }

  private void checkNodeKind(
      Graph g,
      Node shape,
      Node prop,
      Collection<VersionIri> scope,
      List<SparqlValidationAnnotation> out) {

    Node nodeKindNode = singleObject(g, shape, Shacl.NODE_KIND);
    if (nodeKindNode == null || !nodeKindNode.isURI()) {
      return;
    }

    Set<Node> ranges = schemaIndex.rangesOf(prop, scope);
    if (ranges.isEmpty()) {
      return; // schema is silent — permissive
    }
    boolean allDatatypes = ranges.stream().allMatch(r -> r.isURI() && isDatatypeRange(r.getURI()));
    boolean allClasses = ranges.stream().allMatch(r -> r.isURI() && !isDatatypeRange(r.getURI()));
    if (!allDatatypes && !allClasses) {
      return; // mixed — skip
    }
    String nk = nodeKindNode.getURI();
    boolean requiresNonLiteral =
        Shacl.IRI.getURI().equals(nk)
            || Shacl.BLANK_NODE.getURI().equals(nk)
            || Shacl.BLANK_NODE_OR_IRI.getURI().equals(nk);
    boolean requiresLiteral = Shacl.LITERAL.getURI().equals(nk);

    if (allDatatypes && requiresNonLiteral) {
      out.add(
          nodeKindAnnotation(
              prop, nodeKindNode, "a literal (datatype property)", "a non-literal", scope));
    } else if (allClasses && requiresLiteral) {
      out.add(
          nodeKindAnnotation(
              prop, nodeKindNode, "an IRI or blank node (object property)", "a literal", scope));
    }
  }

  private static void checkCardinality(
      Graph g, Node shape, Node pathNode, List<SparqlValidationAnnotation> out) {

    Node minNode = singleObject(g, shape, Shacl.MIN_COUNT);
    Node maxNode = singleObject(g, shape, Shacl.MAX_COUNT);
    if (minNode == null || maxNode == null) {
      return;
    }

    OptionalInt min = parseLiteralInt(minNode);
    OptionalInt max = parseLiteralInt(maxNode);
    if (min.isEmpty() || max.isEmpty()) {
      return;
    }

    if (min.getAsInt() > max.getAsInt()) {
      Node term = pathNode.isURI() ? pathNode : null;
      out.add(cardinalityAnnotation(min.getAsInt(), max.getAsInt(), term));
    }
  }

  private void checkDatatypeVsRange(
      Graph g,
      Node shape,
      Node prop,
      Collection<VersionIri> scope,
      List<SparqlValidationAnnotation> out) {

    Node datatypeNode = singleObject(g, shape, Shacl.DATATYPE);
    if (datatypeNode == null || !datatypeNode.isURI()) {
      return;
    }

    Set<Node> ranges = schemaIndex.rangesOf(prop, scope);
    if (ranges.isEmpty()) {
      return;
    }

    boolean allClasses = ranges.stream().allMatch(r -> r.isURI() && !isDatatypeRange(r.getURI()));
    if (!allClasses) {
      return; // mixed or already a datatype range — permissive
    }
    out.add(datatypeIncompatibleAnnotation(prop, datatypeNode, scope));
  }

  private void checkClassVsRange(
      Graph g,
      Node shape,
      Node prop,
      Collection<VersionIri> scope,
      List<SparqlValidationAnnotation> out) {

    Node classNode = singleObject(g, shape, Shacl.CLASS);
    if (classNode == null || !classNode.isURI()) {
      return;
    }

    Set<Node> ranges = schemaIndex.rangesOf(prop, scope);
    if (ranges.isEmpty()) {
      return;
    }

    boolean allDatatypes = ranges.stream().allMatch(r -> r.isURI() && isDatatypeRange(r.getURI()));
    if (!allDatatypes) {
      return; // mixed or class range — permissive
    }
    out.add(classIncompatibleAnnotation(prop, classNode, scope));
  }

  private static OptionalInt parseLiteralInt(Node n) {
    if (!n.isLiteral()) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(n.getLiteralLexicalForm()));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  private static boolean isDatatypeRange(String iri) {
    return iri.startsWith("http://www.w3.org/2001/XMLSchema#")
        || iri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString");
  }

  /**
   * Recursively walks a SHACL property path, dispatching to one of two callbacks: {@code onUri} for
   * each leaf URI node, and {@code onAlternativeGroup} once per {@code sh:alternativePath} group
   * with all leaf URIs pre-collected.
   *
   * <p>Handles all SHACL path forms: simple URI, sequence (RDF list), inverse, alternative, and
   * repetition ({@code sh:zeroOrMorePath} etc.).
   */
  private static void walkPath(
      Graph g, Node path, Consumer<Node> onUri, Consumer<List<Node>> onAlternativeGroup) {
    if (path.isURI()) {
      onUri.accept(path);
      return;
    }
    if (!path.isBlank()) {
      return;
    }

    Node firstEl = singleObject(g, path, RDF_FIRST);
    if (firstEl != null) {
      walkList(g, path, el -> walkPath(g, el, onUri, onAlternativeGroup));
      return;
    }
    Node inv = singleObject(g, path, Shacl.INVERSE_PATH);
    if (inv != null) {
      walkPath(g, inv, onUri, onAlternativeGroup);
      return;
    }

    Node alt = singleObject(g, path, Shacl.ALTERNATIVE_PATH);
    if (alt != null) {
      var group = new ArrayList<Node>();
      walkList(g, alt, el -> walkPath(g, el, group::add, group::addAll));
      onAlternativeGroup.accept(group);
      return;
    }
    for (Node pred : REPEAT_PATH_PREDICATES) {
      Node inner = singleObject(g, path, pred);
      if (inner != null) {
        walkPath(g, inner, onUri, onAlternativeGroup);
        return;
      }
    }
  }

  /**
   * Recursively walks a SHACL property path and emits {@code UNKNOWN_PROPERTY} annotations for any
   * URI segment not present in the schema.
   *
   * <p>For {@code sh:alternativePath}, the alternatives are treated as a group: an unknown
   * alternative is suppressed when at least one sibling in the same group is a known property
   * <em>with the same local name</em>. This handles the multi-namespace cross-version compatibility
   * pattern (e.g. {@code cim:SvStatus.ConductingEquipment |
   * <http://iec.ch/TC57/CIM100#SvStatus.ConductingEquipment> |
   * <https://cim.ucaiug.io/ns#SvStatus.ConductingEquipment>}) without silencing genuine typos whose
   * local names differ from every known alternative.
   */
  private void checkPathPropertyExistence(
      Graph g,
      Node path,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      List<SparqlValidationAnnotation> out) {
    walkPath(
        g,
        path,
        uri -> {
          // sh:path values name data properties (CIM properties, or curated header extensions such
          // as the CIM-552 header's rdf:Statements.subject). Accept known standard/open/header
          // terms, local definitions and genuine CIM properties; report a closed-namespace typo
          // (e.g. sh:path rdf:typ) as a vocabulary term; report an unknown CIM term as missing.
          Classification kind = termResolver.classify(uri, Role.PROPERTY, scope, localDefs);
          if (TermResolver.isAccepted(kind, Role.PROPERTY)) {
            return;
          }
          if (kind == Classification.VOCAB_TYPO) {
            addVocabularyAnnotation(uri, "Shape sh:path", out);
            return;
          }
          out.add(propertyAnnotation(uri, scope, schemaIndex.findProperty(uri)));
        },
        group -> checkAlternativeGroup(group, scope, localDefs, out));
  }

  /**
   * Checks existence for all alternatives collected from an {@code sh:alternativePath} group.
   *
   * <p>An unknown alternative is <em>suppressed</em> when the group contains at least one known
   * property sharing the same local name — the standard cross-version compatibility pattern where
   * the same property appears under multiple CIM namespace URIs. An unknown alternative whose local
   * name does not match any known sibling is still flagged (it is most likely a typo).
   */
  private void checkAlternativeGroup(
      List<Node> allProps,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      List<SparqlValidationAnnotation> out) {
    var known = new ArrayList<Node>();
    var unknown = new ArrayList<Node>();
    for (Node prop : allProps) {
      // See checkPathPropertyExistence: sh:path values are data properties, not vocabulary.
      Classification kind = termResolver.classify(prop, Role.PROPERTY, scope, localDefs);
      switch (kind) {
        case CIM_PROPERTY -> known.add(prop);
        case KNOWN_STANDARD, OPEN_NAMESPACE, HEADER_EXTENSION, LOCAL_DEF -> {
          // accepted without a CIM existence check
        }
        case VOCAB_TYPO -> addVocabularyAnnotation(prop, "Shape sh:path", out);
        default -> unknown.add(prop); // CIM_CLASS, ENUM_MEMBER, UNKNOWN
      }
    }
    for (Node prop : unknown) {
      String localName = localName(prop.getURI());
      boolean hasKnownSibling =
          known.stream().anyMatch(k -> localName(k.getURI()).equals(localName));
      if (!hasKnownSibling) {
        out.add(propertyAnnotation(prop, scope, schemaIndex.findProperty(prop)));
      }
    }
  }

  /** Returns the local name of a URI (the part after the last {@code #} or {@code /}). */
  private static String localName(String uri) {
    int sep = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
    return sep >= 0 ? uri.substring(sep + 1) : uri;
  }

  /** Recursively collects all URI property nodes out of a SHACL property path expression. */
  private static void extractPropertyUris(Graph g, Node path, List<Node> out) {
    walkPath(g, path, out::add, out::addAll);
  }

  /** Walks an RDF list, calling {@code consumer} for each {@code rdf:first} value. */
  private static void walkList(Graph g, Node list, Consumer<Node> consumer) {
    Node cur = list;
    var visited = new HashSet<Node>();
    while (cur != null && cur.isBlank() && visited.add(cur)) {
      Node first = singleObject(g, cur, RDF_FIRST);
      if (first != null) {
        consumer.accept(first);
      }
      Node rest = singleObject(g, cur, RDF_REST);
      if (rest == null || RDF_NIL.equals(rest)) {
        break;
      }
      cur = rest.isBlank() ? rest : null;
    }
  }

  // ---- sh:in / sh:hasValue value checks --------------------------------------------------

  /**
   * Checks the URI members of every {@code sh:in} value list. When the shape's {@code sh:path}
   * property has a known enumeration range, members that are not values of that enumeration are
   * reported as {@link SparqlValidationCode#INVALID_ENUM_VALUE}. Otherwise a member that is a URI
   * unknown to every CIM index (class, property, enumeration member) is reported as a missing term.
   * Literal members, standard/open/header terms and locally declared terms are left alone;
   * closed-namespace typos are handled by {@link #checkVocabularyTerms}.
   */
  private void checkInLists(
      Graph g,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      Set<Node> deactivated,
      List<SparqlValidationAnnotation> out) {

    var it = g.find(Node.ANY, Shacl.IN, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        Node shape = t.getSubject();
        if (deactivated.contains(shape)) {
          continue;
        }
        Node prop = simplePathProperty(g, shape);
        Set<Node> ranges = prop == null ? Set.of() : schemaIndex.rangesOf(prop, scope);
        Set<Node> enumMembers = enumMembers(ranges, scope);
        var members = new ArrayList<Node>();
        walkList(g, t.getObject(), members::add);
        for (Node m : members) {
          checkInMember(m, enumMembers, ranges, scope, localDefs, out);
        }
      }
    } finally {
      closeQuietly(it);
    }
  }

  private void checkInMember(
      Node m,
      Set<Node> enumMembers,
      Set<Node> ranges,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      List<SparqlValidationAnnotation> out) {
    if (!m.isURI() || isVocabularyOrLocal(m, localDefs)) {
      return;
    }
    if (enumMembers != null) {
      if (!enumMembers.contains(m)) {
        out.add(enumValueAnnotation(m, ranges, scope, "sh:in"));
      }
      return;
    }
    if (!existsAsCimTerm(m, scope)) {
      out.add(inUnknownTermAnnotation(m, scope));
    }
  }

  /**
   * Checks {@code sh:hasValue}: when the shape's {@code sh:path} property has a known enumeration
   * range and the required value is a URI that is not a member of that enumeration, it is reported
   * as {@link SparqlValidationCode#INVALID_ENUM_VALUE}.
   */
  private void checkHasValue(
      Graph g,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      Set<Node> deactivated,
      List<SparqlValidationAnnotation> out) {

    var it = g.find(Node.ANY, Shacl.HAS_VALUE, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        Node shape = t.getSubject();
        Node value = t.getObject();
        if (deactivated.contains(shape)
            || !value.isURI()
            || isVocabularyOrLocal(value, localDefs)) {
          continue;
        }
        Node prop = simplePathProperty(g, shape);
        Set<Node> ranges = prop == null ? Set.of() : schemaIndex.rangesOf(prop, scope);
        Set<Node> enumMembers = enumMembers(ranges, scope);
        if (enumMembers != null && !enumMembers.contains(value)) {
          out.add(enumValueAnnotation(value, ranges, scope, "sh:hasValue"));
        }
      }
    } finally {
      closeQuietly(it);
    }
  }

  // ---- sh:datatype vocabulary, target/property-pair, ignoredProperties, value ranges ------

  /**
   * Flags an {@code sh:datatype} whose value is in the XSD namespace but is not a known XSD 1.1
   * datatype — e.g. {@code sh:datatype xsd:strng}. The XSD namespace is otherwise accepted
   * wholesale by {@link ExemptVocabulary}, so this is the only place such a typo is caught.
   */
  private void checkDatatypeVocabulary(
      Graph g, Set<Node> deactivated, List<SparqlValidationAnnotation> out) {
    if (!checkStandardVocabulary) {
      return;
    }
    var it = g.find(Node.ANY, Shacl.DATATYPE, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        Node dt = t.getObject();
        if (deactivated.contains(t.getSubject())) {
          continue;
        }
        if (StandardVocabulary.isXsdNamespace(dt) && !StandardVocabulary.isKnownXsdDatatype(dt)) {
          out.add(xsdDatatypeAnnotation(dt));
        }
      }
    } finally {
      closeQuietly(it);
    }
  }

  /**
   * Checks predicates whose URI object names a CIM property — {@code sh:targetSubjectsOf}/{@code
   * sh:targetObjectsOf} and the property-pair constraints {@code sh:equals}/{@code sh:disjoint}/
   * {@code sh:lessThan}/{@code sh:lessThanOrEquals}. A URI that is not a known property (nor a
   * standard/open/header/local term) is reported as {@link SparqlValidationCode#UNKNOWN_PROPERTY}.
   */
  private void checkPropertyRefPredicates(
      Graph g,
      List<Node> predicates,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      Set<Node> deactivated,
      List<SparqlValidationAnnotation> out) {
    for (Node predicate : predicates) {
      String label = "sh:" + localName(predicate.getURI());
      var it = g.find(Node.ANY, predicate, Node.ANY);
      try {
        while (it.hasNext()) {
          Triple t = it.next();
          Node obj = t.getObject();
          if (!obj.isURI() || deactivated.contains(t.getSubject())) {
            continue;
          }
          if (isUnknownProperty(obj, scope, localDefs)) {
            out.add(propertyRefAnnotation(obj, label, scope, schemaIndex.findProperty(obj)));
          }
        }
      } finally {
        closeQuietly(it);
      }
    }
  }

  /**
   * Checks the {@code sh:ignoredProperties} RDF list: every URI must be a known property or a
   * standard/open/header/local term (the common {@code ( rdf:type )} case is accepted). Unknown
   * terms are reported as {@link SparqlValidationCode#UNKNOWN_PROPERTY}.
   */
  private void checkIgnoredProperties(
      Graph g,
      Collection<VersionIri> scope,
      Set<Node> localDefs,
      Set<Node> deactivated,
      List<SparqlValidationAnnotation> out) {
    var it = g.find(Node.ANY, Shacl.IGNORED_PROPERTIES, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        if (deactivated.contains(t.getSubject())) {
          continue;
        }
        var members = new ArrayList<Node>();
        walkList(g, t.getObject(), members::add);
        for (Node m : members) {
          if (m.isURI() && isUnknownProperty(m, scope, localDefs)) {
            out.add(
                propertyRefAnnotation(
                    m, "sh:ignoredProperties", scope, schemaIndex.findProperty(m)));
          }
        }
      }
    } finally {
      closeQuietly(it);
    }
  }

  /**
   * Flags a self-contradictory value range — a lower bound ({@code sh:minInclusive}/{@code
   * sh:minExclusive}) strictly greater than an upper bound ({@code sh:maxInclusive}/{@code
   * sh:maxExclusive}) on the same shape, which no value can satisfy. Only numeric bounds are
   * compared; non-numeric bounds are skipped.
   */
  private void checkValueRanges(
      Graph g, Set<Node> deactivated, List<SparqlValidationAnnotation> out) {
    var shapes = new LinkedHashSet<Node>();
    for (Node p :
        List.of(
            Shacl.MIN_INCLUSIVE, Shacl.MIN_EXCLUSIVE, Shacl.MAX_INCLUSIVE, Shacl.MAX_EXCLUSIVE)) {
      forEachSubject(g, p, shapes::add);
    }
    for (Node shape : shapes) {
      if (!deactivated.contains(shape)) {
        checkValueRange(g, shape, out);
      }
    }
  }

  private void checkValueRange(Graph g, Node shape, List<SparqlValidationAnnotation> out) {
    OptionalDouble minInc = literalDouble(singleObject(g, shape, Shacl.MIN_INCLUSIVE));
    OptionalDouble minExc = literalDouble(singleObject(g, shape, Shacl.MIN_EXCLUSIVE));
    OptionalDouble maxInc = literalDouble(singleObject(g, shape, Shacl.MAX_INCLUSIVE));
    OptionalDouble maxExc = literalDouble(singleObject(g, shape, Shacl.MAX_EXCLUSIVE));
    Node term = simplePathProperty(g, shape);

    if (contradicts(minInc, maxInc)) {
      out.add(valueRangeAnnotation("sh:minInclusive", minInc, "sh:maxInclusive", maxInc, term));
    } else if (contradicts(minInc, maxExc)) {
      out.add(valueRangeAnnotation("sh:minInclusive", minInc, "sh:maxExclusive", maxExc, term));
    } else if (contradicts(minExc, maxInc)) {
      out.add(valueRangeAnnotation("sh:minExclusive", minExc, "sh:maxInclusive", maxInc, term));
    } else if (contradicts(minExc, maxExc)) {
      out.add(valueRangeAnnotation("sh:minExclusive", minExc, "sh:maxExclusive", maxExc, term));
    }
  }

  private static boolean contradicts(OptionalDouble lower, OptionalDouble upper) {
    return lower.isPresent() && upper.isPresent() && lower.getAsDouble() > upper.getAsDouble();
  }

  // ---- shared helpers for the checks above ------------------------------------------------

  /**
   * Returns the {@code sh:path} value of {@code shape} when it is a single URI, else {@code null}.
   */
  private static Node simplePathProperty(Graph g, Node shape) {
    Node p = singleObject(g, shape, Shacl.PATH);
    return p != null && p.isURI() ? p : null;
  }

  /**
   * Returns the union of enumeration members if <em>every</em> range in {@code ranges} is an
   * enumeration with known members in scope; otherwise {@code null} (be permissive — the property
   * is not a known enumeration-typed one).
   */
  private Set<Node> enumMembers(Set<Node> ranges, Collection<VersionIri> scope) {
    if (ranges.isEmpty()) {
      return null;
    }
    var members = new LinkedHashSet<Node>();
    for (Node r : ranges) {
      Set<Node> m = schemaIndex.enumMembersOf(r, scope);
      if (m.isEmpty()) {
        return null; // non-enumeration range — permissive
      }
      members.addAll(m);
    }
    return members;
  }

  /** Whether {@code m} exists as a class, property or enumeration member in the CIM schema. */
  private boolean existsAsCimTerm(Node m, Collection<VersionIri> scope) {
    return schemaIndex.classExists(m, scope)
        || schemaIndex.propertyExists(m, scope)
        || schemaIndex.enumMemberExists(m, null);
  }

  /**
   * Whether {@code term} is a standard/open/header vocabulary term, a closed-namespace typo
   * (handled elsewhere), or a locally declared term — i.e. not something to check against the CIM
   * schema in a value position.
   */
  private static boolean isVocabularyOrLocal(Node term, Set<Node> localDefs) {
    return TermResolver.vocabularyClassification(term) != null || localDefs.contains(term);
  }

  /**
   * Whether {@code obj} should be reported as an unknown property in a property-reference position:
   * a URI that is not accepted as a property, standard/open/header/local term, and not a closed
   * namespace typo (those are reported by {@link #checkVocabularyTerms}).
   */
  private boolean isUnknownProperty(Node obj, Collection<VersionIri> scope, Set<Node> localDefs) {
    Classification kind = termResolver.classify(obj, Role.PROPERTY, scope, localDefs);
    return !TermResolver.isAccepted(kind, Role.PROPERTY) && kind != Classification.VOCAB_TYPO;
  }

  private static OptionalDouble literalDouble(Node n) {
    if (n == null || !n.isLiteral()) {
      return OptionalDouble.empty();
    }
    try {
      return OptionalDouble.of(Double.parseDouble(n.getLiteralLexicalForm()));
    } catch (NumberFormatException e) {
      return OptionalDouble.empty();
    }
  }

  private static void forEachSubject(Graph g, Node predicate, Consumer<Node> consumer) {
    var it = g.find(Node.ANY, predicate, Node.ANY);
    try {
      while (it.hasNext()) {
        consumer.accept(it.next().getSubject());
      }
    } finally {
      closeQuietly(it);
    }
  }

  /** Collects shape nodes deactivated with {@code sh:deactivated true}. */
  private static Set<Node> collectDeactivatedShapes(Graph g) {
    var out = new HashSet<Node>();
    var it = g.find(Node.ANY, Shacl.DEACTIVATED, Node.ANY);
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        Node o = t.getObject();
        if (o.isLiteral() && "true".equalsIgnoreCase(o.getLiteralLexicalForm().trim())) {
          out.add(t.getSubject());
        }
      }
    } finally {
      closeQuietly(it);
    }
    return out;
  }

  // ---- annotation builders ---------------------------------------------------------------

  private static SparqlValidationAnnotation classAnnotation(
      Node cls, String predicateLabel, Collection<VersionIri> scope, List<VersionIri> elsewhere) {

    var msg =
        new StringBuilder("Shape ")
            .append(predicateLabel)
            .append(": class <")
            .append(cls.getURI())
            .append("> does not exist in ");
    appendScopeLabel(msg, scope);
    msg.append('.');
    if (!elsewhere.isEmpty()) {
      msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
      IriFormat.appendIris(msg, elsewhere);
      msg.append('.');
    }
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.UNKNOWN_CLASS,
        cls,
        List.copyOf(scope),
        List.copyOf(elsewhere),
        null);
  }

  /**
   * Emits an {@link SparqlValidationCode#UNKNOWN_VOCABULARY_TERM} annotation for an unknown term in
   * a closed standard vocabulary. {@code context} is a short human label for where the term was
   * used (e.g. {@code "Shape sh:path"}, {@code "Predicate"}). No-op when standard-vocabulary
   * checking is disabled.
   */
  private static void addVocabularyAnnotation(
      Node term, String context, List<SparqlValidationAnnotation> out) {
    String vocab = StandardVocabulary.vocabularyName(term.getURI());
    out.add(
        new SparqlValidationAnnotation(
            SparqlValidationSeverity.ERROR,
            null,
            null,
            context + ": <" + term.getURI() + "> is not a term in the " + vocab + " vocabulary.",
            SparqlValidationCode.UNKNOWN_VOCABULARY_TERM,
            term,
            List.of(),
            List.of(),
            null));
  }

  private static SparqlValidationAnnotation propertyAnnotation(
      Node prop, Collection<VersionIri> scope, List<VersionIri> elsewhere) {

    var msg =
        new StringBuilder("Shape sh:path: property <")
            .append(prop.getURI())
            .append("> does not exist in ");
    appendScopeLabel(msg, scope);
    msg.append('.');
    if (!elsewhere.isEmpty()) {
      msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
      IriFormat.appendIris(msg, elsewhere);
      msg.append('.');
    }
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.UNKNOWN_PROPERTY,
        prop,
        List.copyOf(scope),
        List.copyOf(elsewhere),
        null);
  }

  private static SparqlValidationAnnotation nodeKindAnnotation(
      Node prop,
      Node nodeKindNode,
      String actualKind,
      String declaredKind,
      Collection<VersionIri> scope) {

    var msg =
        new StringBuilder("sh:nodeKind <")
            .append(IriFormat.shortIri(nodeKindNode.getURI()))
            .append("> declares value must be ")
            .append(declaredKind)
            .append(", but rdfs:range of <")
            .append(prop.getURI())
            .append("> is ")
            .append(actualKind)
            .append(" in ");
    appendScopeLabel(msg, scope);
    msg.append('.');
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.WARN,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE,
        prop,
        List.copyOf(scope),
        List.of(),
        null);
  }

  private static SparqlValidationAnnotation cardinalityAnnotation(int min, int max, Node term) {

    String msg =
        "sh:minCount "
            + min
            + " exceeds sh:maxCount "
            + max
            + ": property shape can never be satisfied.";
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg,
        SparqlValidationCode.INVALID_CARDINALITY,
        term,
        List.of(),
        List.of(),
        null);
  }

  private static SparqlValidationAnnotation datatypeIncompatibleAnnotation(
      Node prop, Node datatypeNode, Collection<VersionIri> scope) {

    var msg =
        new StringBuilder("sh:datatype <")
            .append(IriFormat.shortIri(datatypeNode.getURI()))
            .append("> expects literal values, but rdfs:range of <")
            .append(prop.getURI())
            .append("> is a class (object property) in ");
    appendScopeLabel(msg, scope);
    msg.append('.');
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.WARN,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.DATATYPE_INCOMPATIBLE_WITH_RANGE,
        prop,
        List.copyOf(scope),
        List.of(),
        null);
  }

  private static SparqlValidationAnnotation classIncompatibleAnnotation(
      Node prop, Node classNode, Collection<VersionIri> scope) {

    var msg =
        new StringBuilder("sh:class <")
            .append(IriFormat.shortIri(classNode.getURI()))
            .append("> expects IRI values, but rdfs:range of <")
            .append(prop.getURI())
            .append("> is a literal datatype (datatype property) in ");
    appendScopeLabel(msg, scope);
    msg.append('.');
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.WARN,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.CLASS_INCOMPATIBLE_WITH_RANGE,
        prop,
        List.copyOf(scope),
        List.of(),
        null);
  }

  private static SparqlValidationAnnotation enumValueAnnotation(
      Node value, Collection<Node> ranges, Collection<VersionIri> scope, String context) {
    var msg =
        new StringBuilder(context)
            .append(": <")
            .append(value.getURI())
            .append("> is not a value of enumeration ")
            .append(formatUris(ranges))
            .append('.');
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.INVALID_ENUM_VALUE,
        value,
        List.copyOf(scope),
        List.of(),
        null);
  }

  private static SparqlValidationAnnotation inUnknownTermAnnotation(
      Node term, Collection<VersionIri> scope) {
    var msg =
        new StringBuilder("Shape sh:in: <")
            .append(term.getURI())
            .append("> does not exist as a class, property or enumeration member in ");
    appendScopeLabel(msg, scope);
    msg.append('.');
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.UNKNOWN_CLASS,
        term,
        List.copyOf(scope),
        List.of(),
        null);
  }

  private static SparqlValidationAnnotation propertyRefAnnotation(
      Node prop, String predicateLabel, Collection<VersionIri> scope, List<VersionIri> elsewhere) {
    var msg =
        new StringBuilder("Shape ")
            .append(predicateLabel)
            .append(": property <")
            .append(prop.getURI())
            .append("> does not exist in ");
    appendScopeLabel(msg, scope);
    msg.append('.');
    if (!elsewhere.isEmpty()) {
      msg.append(" Exists in profile").append(elsewhere.size() == 1 ? " " : "s ");
      IriFormat.appendIris(msg, elsewhere);
      msg.append('.');
    }
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg.toString(),
        SparqlValidationCode.UNKNOWN_PROPERTY,
        prop,
        List.copyOf(scope),
        List.copyOf(elsewhere),
        null);
  }

  private static SparqlValidationAnnotation xsdDatatypeAnnotation(Node datatype) {
    String msg =
        "sh:datatype <"
            + IriFormat.shortIri(datatype.getURI())
            + "> is not a term in the XSD vocabulary.";
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg,
        SparqlValidationCode.UNKNOWN_VOCABULARY_TERM,
        datatype,
        List.of(),
        List.of(),
        null);
  }

  private static SparqlValidationAnnotation valueRangeAnnotation(
      String lowerLabel, OptionalDouble lower, String upperLabel, OptionalDouble upper, Node term) {
    String msg =
        lowerLabel
            + " "
            + formatBound(lower.getAsDouble())
            + " exceeds "
            + upperLabel
            + " "
            + formatBound(upper.getAsDouble())
            + ": property shape can never be satisfied.";
    return new SparqlValidationAnnotation(
        SparqlValidationSeverity.ERROR,
        null,
        null,
        msg,
        SparqlValidationCode.INVALID_VALUE_RANGE,
        term,
        List.of(),
        List.of(),
        null);
  }

  /** Formats a numeric bound without a trailing {@code .0} for whole numbers. */
  private static String formatBound(double d) {
    return d == Math.floor(d) && !Double.isInfinite(d)
        ? Long.toString((long) d)
        : Double.toString(d);
  }

  /** Joins a set of URI nodes as {@code <a>, <b>} for diagnostics. */
  private static String formatUris(Collection<Node> nodes) {
    var sb = new StringBuilder();
    boolean first = true;
    for (Node n : nodes) {
      if (!first) {
        sb.append(", ");
      }
      sb.append('<').append(n.isURI() ? n.getURI() : n.toString()).append('>');
      first = false;
    }
    return sb.toString();
  }

  private static void appendScopeLabel(StringBuilder msg, Collection<VersionIri> scope) {
    if (scope.isEmpty()) {
      msg.append("selected schema/profile scope (empty)");
    } else {
      msg.append("selected profile").append(scope.size() == 1 ? " " : "s ");
      IriFormat.appendIris(msg, scope);
    }
  }

  // ---- graph helpers ---------------------------------------------------------------------

  /** Calls {@code consumer} for every object of {@code (ANY, predicate, ?o)} in the graph. */
  private static void forEachObject(Graph g, Node predicate, Consumer<Node> consumer) {
    var it = g.find(Node.ANY, predicate, Node.ANY);
    try {
      while (it.hasNext()) {
        consumer.accept(it.next().getObject());
      }
    } finally {
      closeQuietly(it);
    }
  }

  /** Returns the single object of {@code (subject, predicate, ?o)}, or {@code null}. */
  private static Node singleObject(Graph g, Node subject, Node predicate) {
    var it = g.find(subject, predicate, Node.ANY);
    try {
      return it.hasNext() ? it.next().getObject() : null;
    } finally {
      closeQuietly(it);
    }
  }

  /** Closes a Jena iterator if it is {@link AutoCloseable}, swallowing any close failure. */
  private static void closeQuietly(Object it) {
    if (it instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // Intentionally ignored.
      }
    }
  }
}
