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

package de.soptim.opencgmes.sparql.validation.analysis;

import de.soptim.opencgmes.sparql.validation.InvalidQueryException;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.sparql.algebra.Algebra;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Parses a SPARQL query string, compiles it to Jena algebra and walks it with an
 * {@link AlgebraAnalysisVisitor}.
 *
 * <p>The result also includes {@code FROM} / {@code FROM NAMED} declarations as
 * {@link GraphReference} entries, because these are accessible from {@link Query} but not from
 * the algebra Op tree.</p>
 */
public final class SparqlQueryAnalyzer {

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
            } else {
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
                visitor.dynamicClass()
        );
    }

    /** Parses the query, translating Jena's {@link QueryParseException} into our checked one. */
    public Query parse(String queryString) throws InvalidQueryException {
        try {
            return QueryFactory.create(queryString);
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
