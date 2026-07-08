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

package de.soptim.opencgmes.cimvocabcheck.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.SortCondition;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementAntiJoin;
import org.apache.jena.sparql.syntax.ElementAssign;
import org.apache.jena.sparql.syntax.ElementBind;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementExists;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementLateral;
import org.apache.jena.sparql.syntax.ElementMinus;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementNotExists;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementSemiJoin;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.ElementUnion;
import org.apache.jena.sparql.syntax.Template;

/**
 * Schema-independent check for variables that are declared but never meaningfully used in a SPARQL
 * query. Two cases are reported, both as {@code WARN}:
 *
 * <ul>
 *   <li>{@link SparqlValidationCode#PROJECTED_VARIABLE_UNBOUND} — a variable in the result surface
 *       (explicit {@code SELECT} list, {@code CONSTRUCT} template, {@code DESCRIBE} list) that does
 *       not appear anywhere in the query body ({@code WHERE} / {@code BIND} / {@code VALUES}). Such
 *       a variable is unbound in every result row — almost always a typo (e.g. {@code ?nmae} vs
 *       {@code ?name}).
 *   <li>{@link SparqlValidationCode#UNUSED_VARIABLE} — a variable bound in a triple pattern, {@code
 *       BIND} or {@code VALUES} that occurs exactly once in its query scope: never projected,
 *       filtered on, ordered/grouped by, or otherwise reused. A typo or a leftover from an edit; a
 *       deliberately unused pattern variable is idiomatically a blank node.
 * </ul>
 *
 * <p>The check works on the parsed syntax tree (not the algebra) so parser-allocated variables —
 * blank-node labels, property-path intermediates — never surface. Deliberate design decisions to
 * keep the check quiet on idiomatic SPARQL:
 *
 * <ul>
 *   <li>{@code SELECT *} / {@code DESCRIBE *} project every pattern variable, and {@code ASK}
 *       bodies are pure existence tests — no {@code UNUSED_VARIABLE} is reported for them.
 *   <li>Occurrences inside {@code EXISTS} / {@code NOT EXISTS} / {@code MINUS} bodies are
 *       existence-test occurrences: they count as <em>uses</em> of outer variables, but a variable
 *       occurring only there is not reported.
 *   <li>Variable predicates ({@code ?s ?p ?o}), {@code GRAPH ?g} / {@code SERVICE ?s} names and
 *       sub-{@code SELECT} projections joined into the outer scope have no blank-node equivalent —
 *       a single occurrence there is not reported.
 *   <li>Each ({@code SELECT}) scope is analyzed independently: a sub-query variable that is not
 *       projected is invisible to — and independent of — the outer scope.
 * </ul>
 *
 * <p>Queries containing syntax forms this walker does not know (e.g. future Jena extensions) are
 * skipped entirely rather than risking a false positive. SPARQL Update requests are out of scope:
 * they have no projection, and an unbound variable in an INSERT/DELETE template is a different
 * class of problem.
 */
public final class UnusedVariableCheck {

  private UnusedVariableCheck() {}

  /** Runs the check on a parsed query; {@code originalText} is used only for source locations. */
  public static List<SparqlValidationAnnotation> check(Query query, String originalText) {
    return check(query, originalText, Set.of());
  }

  /**
   * Variant with an exemption list: variables whose name is in {@code exemptVariables} are never
   * reported. Used for SHACL embedded SPARQL, where {@code $this}, {@code ?value} etc. are
   * pre-bound by the SHACL engine and legitimately projected without appearing in the body.
   */
  public static List<SparqlValidationAnnotation> check(
      Query query, String originalText, Set<String> exemptVariables) {
    var out = new ArrayList<SparqlValidationAnnotation>();
    new Walker(originalText, exemptVariables, out).analyzeQuery(query);
    return out;
  }

  /** How a variable occurrence participates in the query. */
  private enum Kind {
    /** Subject or object of a triple / property-path pattern — blank-node replaceable. */
    PATTERN,
    /** Predicate of a triple pattern — not blank-node replaceable, exempt from UNUSED_VARIABLE. */
    PATTERN_PREDICATE,
    /** Target of {@code BIND} / {@code LET} / {@code UNFOLD}. */
    BIND,
    /** Declared by a {@code VALUES} block. */
    VALUES,
    /** {@code GRAPH ?g} / {@code SERVICE ?s} name — idiomatic wildcard, exempt. */
    GRAPH_NAME,
    /** Projected out of a sub-{@code SELECT} into this scope — exempt. */
    SUBQUERY_PROJECTION,
    /** Consumed: expression, projection, solution modifier, template. */
    USAGE
  }

  /** Occurrence statistics for one variable within one query scope. */
  private static final class Occurrences {
    int total;
    Kind firstKind;
    boolean firstExistential;
  }

  /** Per-{@code SELECT}-scope state; sub-queries get a fresh scope. */
  private static final class Scope {
    final Map<Var, Occurrences> vars = new LinkedHashMap<>();

    /** Variables occurring anywhere in the WHERE tree (patterns, expressions, EXISTS bodies). */
    final Set<Var> bodyMentioned = new LinkedHashSet<>();

    /** Variables defined by an {@code (expr AS ?v)} in the projection or GROUP BY. */
    final Set<Var> exprDefined = new LinkedHashSet<>();

    /** Set when an unknown syntax form is encountered — suppresses all findings. */
    boolean unsupported;
  }

  private static final class Walker {
    private final String text;
    private final Set<String> exempt;
    private final List<SparqlValidationAnnotation> out;

    Walker(String text, Set<String> exempt, List<SparqlValidationAnnotation> out) {
      this.text = text;
      this.exempt = exempt;
      this.out = out;
    }

    void analyzeQuery(Query query) {
      var scope = new Scope();
      walkElement(query.getQueryPattern(), scope, false);

      // Solution modifiers: uses, but not part of the query body for the unbound check.
      VarExprList groupBy = query.getGroupBy();
      if (groupBy != null) {
        for (Var v : groupBy.getVars()) {
          Expr e = groupBy.getExpr(v);
          if (e == null) {
            record(scope, v, Kind.USAGE, false, false);
          } else {
            scope.exprDefined.add(v);
            walkExpr(e, scope, false, false);
          }
        }
      }
      for (Expr e : query.getHavingExprs()) {
        walkExpr(e, scope, false, false);
      }
      if (query.getOrderBy() != null) {
        for (SortCondition sc : query.getOrderBy()) {
          walkExpr(sc.getExpression(), scope, false, false);
        }
      }
      // A trailing query-level VALUES binds like the body.
      if (query.hasValues()) {
        for (Var v : query.getValuesVariables()) {
          record(scope, v, Kind.VALUES, false, true);
        }
      }

      // Result surface: explicitly projected / template / described variables, with the phrase
      // used in the PROJECTED_VARIABLE_UNBOUND message.
      var resultSurface = new LinkedHashMap<Var, String>();
      boolean star = query.isQueryResultStar();
      if (query.isSelectType() && !star) {
        VarExprList project = query.getProject();
        for (Var v : project.getVars()) {
          Expr e = project.getExpr(v);
          if (e == null) {
            resultSurface.put(v, "projected");
            record(scope, v, Kind.USAGE, false, false);
          } else {
            scope.exprDefined.add(v);
            walkExpr(e, scope, false, false);
          }
        }
      } else if (query.isConstructType()) {
        Template template = query.getConstructTemplate();
        if (template != null) {
          for (Quad quad : template.getQuads()) {
            recordTemplateNode(quad.getGraph(), scope, resultSurface);
            recordTemplateNode(quad.getSubject(), scope, resultSurface);
            recordTemplateNode(quad.getPredicate(), scope, resultSurface);
            recordTemplateNode(quad.getObject(), scope, resultSurface);
          }
        }
      } else if (query.isDescribeType() && !star) {
        for (Var v : query.getProjectVars()) {
          resultSurface.put(v, "described");
          record(scope, v, Kind.USAGE, false, false);
        }
      }

      if (scope.unsupported) {
        return;
      }

      // Case 1: in the result surface but nowhere in the query body.
      for (var entry : resultSurface.entrySet()) {
        Var v = entry.getKey();
        if (exempt.contains(v.getName())
            || scope.bodyMentioned.contains(v)
            || scope.exprDefined.contains(v)) {
          continue;
        }
        out.add(
            annotation(
                SparqlValidationCode.PROJECTED_VARIABLE_UNBOUND,
                v,
                "Variable ?"
                    + v.getName()
                    + " is "
                    + entry.getValue()
                    + " but never bound: it does not appear anywhere in the query body"
                    + " (WHERE / BIND / VALUES)."));
      }

      // Case 2: bound exactly once and never reused. Skipped when every pattern variable is
      // implicitly part of the result (SELECT * / DESCRIBE *) or the whole body is an existence
      // test (ASK).
      if (star || query.isAskType()) {
        return;
      }
      for (var entry : scope.vars.entrySet()) {
        Var v = entry.getKey();
        Occurrences o = entry.getValue();
        if (exempt.contains(v.getName()) || o.total != 1 || o.firstExistential) {
          continue;
        }
        String boundBy =
            switch (o.firstKind) {
              case PATTERN -> "in a triple pattern";
              case BIND -> "by BIND";
              case VALUES -> "by VALUES";
              default -> null;
            };
        if (boundBy == null) {
          continue;
        }
        out.add(
            annotation(
                SparqlValidationCode.UNUSED_VARIABLE,
                v,
                "Variable ?"
                    + v.getName()
                    + " is bound "
                    + boundBy
                    + " but never used anywhere else in the query — a typo or a leftover?"));
      }
    }

    // ---- element traversal -----------------------------------------------------------------

    private void walkElement(Element el, Scope s, boolean existential) {
      switch (el) {
        case null -> {}
        case ElementGroup g -> g.getElements().forEach(e -> walkElement(e, s, existential));
        case ElementTriplesBlock tb ->
            tb.getPattern().getList().forEach(t -> recordTriple(t, s, existential));
        case ElementPathBlock pb ->
            pb.getPattern().getList().forEach(tp -> recordTriplePath(tp, s, existential));
        case ElementFilter f -> walkExpr(f.getExpr(), s, existential, true);
        case ElementBind b -> {
          walkExpr(b.getExpr(), s, existential, true);
          record(s, b.getVar(), Kind.BIND, existential, true);
        }
        case ElementAssign a -> {
          walkExpr(a.getExpr(), s, existential, true);
          record(s, a.getVar(), Kind.BIND, existential, true);
        }
        case ElementData d ->
            d.getVars().forEach(v -> record(s, v, Kind.VALUES, existential, true));
        case ElementUnion u -> u.getElements().forEach(e -> walkElement(e, s, existential));
        case ElementOptional o -> walkElement(o.getOptionalElement(), s, existential);
        case ElementLateral l -> walkElement(l.getLateralElement(), s, existential);
        case ElementNamedGraph g -> {
          recordGraphName(g.getGraphNameNode(), s, existential);
          walkElement(g.getElement(), s, existential);
        }
        case ElementService sv -> {
          recordGraphName(sv.getServiceNode(), s, existential);
          walkElement(sv.getElement(), s, existential);
        }
        case ElementMinus m -> walkElement(m.getMinusElement(), s, true);
        case ElementExists e -> walkElement(e.getElement(), s, true);
        case ElementNotExists e -> walkElement(e.getElement(), s, true);
        case ElementSemiJoin j -> walkElement(j.getSubElement(), s, true);
        case ElementAntiJoin j -> walkElement(j.getSubElement(), s, true);
        case ElementSubQuery sq -> {
          analyzeQuery(sq.getQuery());
          for (Var v : projectedVars(sq.getQuery(), s)) {
            record(s, v, Kind.SUBQUERY_PROJECTION, existential, true);
          }
        }
        default -> s.unsupported = true;
      }
    }

    /** Variables a sub-query makes visible to the enclosing scope. */
    private static List<Var> projectedVars(Query subQuery, Scope s) {
      try {
        return subQuery.getProjectVars();
      } catch (RuntimeException e) {
        s.unsupported = true;
        return List.of();
      }
    }

    private void recordTriple(Triple t, Scope s, boolean existential) {
      recordPatternNode(t.getSubject(), s, Kind.PATTERN, existential);
      recordPatternNode(t.getPredicate(), s, Kind.PATTERN_PREDICATE, existential);
      recordPatternNode(t.getObject(), s, Kind.PATTERN, existential);
    }

    private void recordTriplePath(TriplePath tp, Scope s, boolean existential) {
      if (tp.isTriple()) {
        recordTriple(tp.asTriple(), s, existential);
        return;
      }
      recordPatternNode(tp.getSubject(), s, Kind.PATTERN, existential);
      recordPatternNode(tp.getObject(), s, Kind.PATTERN, existential);
    }

    private void recordPatternNode(Node n, Scope s, Kind kind, boolean existential) {
      if (n == null) {
        return;
      }
      if (n.isTripleTerm()) { // RDF-star quoted triple
        recordTriple(n.getTriple(), s, existential);
      } else if (n.isVariable()) {
        record(s, Var.alloc(n.getName()), kind, existential, true);
      }
    }

    private void recordGraphName(Node n, Scope s, boolean existential) {
      if (n != null && n.isVariable()) {
        record(s, Var.alloc(n.getName()), Kind.GRAPH_NAME, existential, true);
      }
    }

    private void recordTemplateNode(Node n, Scope s, Map<Var, String> resultSurface) {
      if (n == null) {
        return;
      }
      if (n.isTripleTerm()) {
        Triple t = n.getTriple();
        recordTemplateNode(t.getSubject(), s, resultSurface);
        recordTemplateNode(t.getPredicate(), s, resultSurface);
        recordTemplateNode(t.getObject(), s, resultSurface);
      } else if (n.isVariable()) {
        Var v = Var.alloc(n.getName());
        if (Var.isNamedVarName(v.getName())) {
          resultSurface.putIfAbsent(v, "used in the CONSTRUCT template");
        }
        record(s, v, Kind.USAGE, false, false);
      }
    }

    // ---- expression traversal ----------------------------------------------------------------

    private void walkExpr(Expr e, Scope s, boolean existential, boolean inBody) {
      switch (e) {
        case null -> {}
        case ExprVar v -> record(s, v.asVar(), Kind.USAGE, existential, inBody);
        case ExprAggregator agg -> {
          ExprList args = agg.getAggregator().getExprList();
          if (args != null) {
            args.forEach(a -> walkExpr(a, s, existential, inBody));
          }
        }
        case ExprFunctionOp op -> {
          // EXISTS / NOT EXISTS: an existence test over the query body.
          Element pattern = op.getElement();
          if (pattern == null) {
            s.unsupported = true;
          } else {
            walkElement(pattern, s, true);
          }
        }
        case ExprFunction f -> f.getArgs().forEach(a -> walkExpr(a, s, existential, inBody));
        case NodeValue ignored -> {}
        default -> s.unsupported = true;
      }
    }

    // ---- bookkeeping ---------------------------------------------------------------------------

    private void record(Scope s, Var v, Kind kind, boolean existential, boolean inBody) {
      if (!Var.isNamedVarName(v.getName())) {
        return; // parser-allocated variable (blank-node label, path intermediate)
      }
      Occurrences o = s.vars.computeIfAbsent(v, k -> new Occurrences());
      if (o.total == 0) {
        o.firstKind = kind;
        o.firstExistential = existential;
      }
      o.total++;
      if (inBody) {
        s.bodyMentioned.add(v);
      }
    }

    private SparqlValidationAnnotation annotation(
        SparqlValidationCode code, Var v, String message) {
      var loc = SourceLocator.locateVariable(text, v.getName());
      return new SparqlValidationAnnotation(
          SparqlValidationSeverity.WARN,
          loc.line(),
          loc.column(),
          message,
          code,
          v,
          List.of(),
          List.of(),
          null);
    }
  }
}
