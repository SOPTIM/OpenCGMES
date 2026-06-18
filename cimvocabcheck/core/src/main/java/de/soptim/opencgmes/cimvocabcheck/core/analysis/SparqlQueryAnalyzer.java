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

package de.soptim.opencgmes.cimvocabcheck.core.analysis;

import de.soptim.opencgmes.cimvocabcheck.core.InvalidQueryException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.modify.request.Target;
import org.apache.jena.sparql.modify.request.UpdateBinaryOp;
import org.apache.jena.sparql.modify.request.UpdateClear;
import org.apache.jena.sparql.modify.request.UpdateCreate;
import org.apache.jena.sparql.modify.request.UpdateDataDelete;
import org.apache.jena.sparql.modify.request.UpdateDataInsert;
import org.apache.jena.sparql.modify.request.UpdateDeleteWhere;
import org.apache.jena.sparql.modify.request.UpdateDrop;
import org.apache.jena.sparql.modify.request.UpdateLoad;
import org.apache.jena.sparql.modify.request.UpdateModify;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.Template;
import org.apache.jena.update.Update;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;

/**
 * Parses a SPARQL query string, compiles it to Jena algebra and walks it with an {@link
 * AlgebraAnalysisVisitor}.
 *
 * <p>The result also includes {@code FROM} / {@code FROM NAMED} declarations as {@link
 * GraphReference} entries, because these are accessible from {@link Query} but not from the algebra
 * Op tree.
 */
public final class SparqlQueryAnalyzer {

  /**
   * Base URI used when parsing SPARQL queries and updates that contain relative IRIs such as {@code
   * <EQ>} or {@code <TP>}.
   *
   * <p>Without an explicit base, Jena resolves relative IRIs against the JVM working directory,
   * producing {@code file:///path/EQ} URIs that never match user-configured graph names. Using this
   * stable, non-file base keeps relative graph references predictable: {@code <EQ>} always becomes
   * {@code urn:x-cimvocabcheck:base/EQ}, which callers can match by prepending this prefix to a
   * relative config key.
   */
  public static final String RELATIVE_IRI_BASE = "urn:x-cimvocabcheck:base/";

  /** Parses the query and runs the analysis. */
  public SparqlQueryAnalysis analyze(String queryString) throws InvalidQueryException {
    Query query = parse(queryString);
    return analyze(query);
  }

  /** Same as {@link #analyze(String)} for an already-parsed {@link Query}. */
  public SparqlQueryAnalysis analyze(Query query) {
    var op = Algebra.compile(query);
    var visitor = new AlgebraAnalysisVisitor();
    visitor.walk(op);

    // CONSTRUCT queries have a template that Jena's algebra compiler does not include
    // in the Op tree — walk it separately so template terms are validated and tracked.
    if (query.isConstructType()) {
      Template tmpl = query.getConstructTemplate();
      if (tmpl != null) {
        visitor.walkQuads(tmpl.getQuads());
      }
    }

    var graphs = new ArrayList<GraphReference>();
    for (Node g : visitor.graphBlocks()) {
      graphs.add(new GraphReference(g, GraphReference.Source.GRAPH_BLOCK));
    }
    // FROM (default-graph composition) and FROM NAMED come from the Query, not the algebra.
    var seen = new LinkedHashSet<>(visitor.graphBlocks());
    for (String uri : safe(query.getGraphURIs())) {
      Node n = NodeFactory.createURI(uri);
      if (seen.add(n)) {
        graphs.add(new GraphReference(n, GraphReference.Source.FROM));
      }
    }
    for (String uri : safe(query.getNamedGraphURIs())) {
      Node n = NodeFactory.createURI(uri);
      // Always emit FROM_NAMED, even if the graph was also referenced via GRAPH inside the
      // query — the source tag is informative.
      graphs.add(new GraphReference(n, GraphReference.Source.FROM_NAMED));
    }

    return new SparqlQueryAnalysis(
        query,
        op,
        visitor.triples(),
        visitor.classes(),
        visitor.properties(),
        graphs,
        visitor.pathChains(),
        visitor.dynamicPredicate(),
        visitor.dynamicClass());
  }

  // ---- SPARQL Update -----------------------------------------------------------------------

  /**
   * Parses a SPARQL Update request string (possibly several operations separated by {@code ;}) and
   * analyzes all contained operations for classes, properties, and graph references.
   */
  public SparqlUpdateAnalysis analyzeUpdate(String updateString) throws InvalidQueryException {
    UpdateRequest req = parseUpdate(updateString);
    return analyzeUpdate(req);
  }

  /** Same as {@link #analyzeUpdate(String)} for an already-parsed {@link UpdateRequest}. */
  public SparqlUpdateAnalysis analyzeUpdate(UpdateRequest req) {
    var visitor = new AlgebraAnalysisVisitor();
    var graphRefs = new ArrayList<GraphReference>();

    for (Update update : req) {
      switch (update) {
        case UpdateDataInsert ins -> visitor.walkQuads(ins.getQuads());

        case UpdateDataDelete del -> visitor.walkQuads(del.getQuads());

        case UpdateDeleteWhere del -> visitor.walkQuads(del.getQuads());

        case UpdateModify mod -> {
          Node withIri = mod.getWithIRI();
          if (withIri != null) {
            graphRefs.add(new GraphReference(withIri, GraphReference.Source.UPDATE_WITH));
          }
          for (Node u : mod.getUsing()) {
            if (u != null && u.isURI()) {
              graphRefs.add(new GraphReference(u, GraphReference.Source.FROM));
            }
          }
          for (Node u : mod.getUsingNamed()) {
            if (u != null && u.isURI()) {
              graphRefs.add(new GraphReference(u, GraphReference.Source.FROM_NAMED));
            }
          }
          // WITH <g> makes <g> the implicit graph for patterns not inside an explicit
          // GRAPH block — pass it as the default-graph context for the WHERE and templates.
          Element where = mod.getWherePattern();
          if (where != null) {
            Op whereOp = Algebra.compile(where);
            if (withIri != null) {
              visitor.walkInGraph(whereOp, withIri);
            } else {
              visitor.walk(whereOp);
            }
          }
          if (mod.hasInsertClause()) {
            visitor.walkQuads(mod.getInsertQuads(), withIri);
          }
          if (mod.hasDeleteClause()) {
            visitor.walkQuads(mod.getDeleteQuads(), withIri);
          }
        }

        case UpdateCreate create -> {
          Node g = create.getGraph();
          if (g != null && g.isURI()) {
            graphRefs.add(new GraphReference(g, GraphReference.Source.UPDATE_MANAGEMENT));
          }
        }

        case UpdateClear clear -> {
          if (clear.isOneGraph()) {
            Node g = clear.getGraph();
            if (g != null && g.isURI()) {
              graphRefs.add(new GraphReference(g, GraphReference.Source.UPDATE_MANAGEMENT));
            }
          }
        }

        case UpdateDrop drop -> {
          if (drop.isOneGraph()) {
            Node g = drop.getGraph();
            if (g != null && g.isURI()) {
              graphRefs.add(new GraphReference(g, GraphReference.Source.UPDATE_MANAGEMENT));
            }
          }
        }

        case UpdateLoad load -> {
          // LOAD <src> INTO <dest> — record the destination graph if named.
          Node g = load.getDest();
          if (g != null && g.isURI()) {
            graphRefs.add(new GraphReference(g, GraphReference.Source.UPDATE_MANAGEMENT));
          }
        }

        case UpdateBinaryOp bin -> {
          // COPY, MOVE, ADD — record graph IRIs from src and dest targets.
          Target src = bin.getSrc();
          if (src.isOneNamedGraph()) {
            Node g = src.getGraph();
            if (g != null && g.isURI()) {
              graphRefs.add(new GraphReference(g, GraphReference.Source.UPDATE_MANAGEMENT));
            }
          }
          Target dst = bin.getDest();
          if (dst.isOneNamedGraph()) {
            Node g = dst.getGraph();
            if (g != null && g.isURI()) {
              graphRefs.add(new GraphReference(g, GraphReference.Source.UPDATE_MANAGEMENT));
            }
          }
        }

        default -> {
          /* no schema-relevant terms or graph IRIs */
        }
      }
    }

    // Merge GRAPH-block refs collected by the visitor (from WHERE clauses / templates).
    for (Node g : visitor.graphBlocks()) {
      if (graphRefs.stream()
          .noneMatch(r -> r.graph().equals(g) && r.source() == GraphReference.Source.GRAPH_BLOCK)) {
        graphRefs.add(new GraphReference(g, GraphReference.Source.GRAPH_BLOCK));
      }
    }

    return new SparqlUpdateAnalysis(
        req,
        visitor.triples(),
        visitor.classes(),
        visitor.properties(),
        graphRefs,
        visitor.pathChains(),
        visitor.dynamicPredicate(),
        visitor.dynamicClass());
  }

  /** Parses a SPARQL Update string, translating parse errors into our checked exception. */
  public UpdateRequest parseUpdate(String updateString) throws InvalidQueryException {
    try {
      return UpdateFactory.create(updateString, RELATIVE_IRI_BASE);
    } catch (QueryParseException e) {
      throw new InvalidQueryException(
          e.getMessage(), e, safeLine(e.getLine()), safeCol(e.getColumn()));
    } catch (QueryException e) {
      throw new InvalidQueryException(e.getMessage(), e);
    }
  }

  // ---- SPARQL Query ------------------------------------------------------------------------

  /** Parses the query, translating Jena's {@link QueryParseException} into our checked one. */
  public Query parse(String queryString) throws InvalidQueryException {
    try {
      return QueryFactory.create(queryString, RELATIVE_IRI_BASE);
    } catch (QueryParseException e) {
      throw new InvalidQueryException(
          e.getMessage(), e, safeLine(e.getLine()), safeCol(e.getColumn()));
    } catch (QueryException e) {
      throw new InvalidQueryException(e.getMessage(), e);
    }
  }

  private static Integer safeLine(int line) {
    return line > 0 ? line : null;
  }

  private static Integer safeCol(int col) {
    return col > 0 ? col : null;
  }

  private static List<String> safe(List<String> in) {
    return in == null ? List.of() : in;
  }
}
