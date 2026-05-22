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

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.Op1;
import org.apache.jena.sparql.algebra.op.Op2;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpLeftJoin;
import org.apache.jena.sparql.algebra.op.OpMinus;
import org.apache.jena.sparql.algebra.op.OpN;
import org.apache.jena.sparql.algebra.op.OpPath;
import org.apache.jena.sparql.algebra.op.OpQuadBlock;
import org.apache.jena.sparql.algebra.op.OpQuadPattern;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVisitorBase;
import org.apache.jena.sparql.path.P_Alt;
import org.apache.jena.sparql.path.P_Inverse;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.P_Mod;
import org.apache.jena.sparql.path.P_NegPropSet;
import org.apache.jena.sparql.path.P_OneOrMore1;
import org.apache.jena.sparql.path.P_OneOrMoreN;
import org.apache.jena.sparql.path.P_Path0;
import org.apache.jena.sparql.path.P_ReverseLink;
import org.apache.jena.sparql.path.P_Seq;
import org.apache.jena.sparql.path.P_ZeroOrMore1;
import org.apache.jena.sparql.path.P_ZeroOrMoreN;
import org.apache.jena.sparql.path.P_ZeroOrOne;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.vocabulary.RDF;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a Jena algebra tree and collects {@link TriplePatternReference}, {@link ClassReference},
 * {@link PropertyReference} and {@link GraphReference} entries.
 *
 * <p>Implemented as a manual recursive dispatcher rather than {@code OpWalker} because we need
 * a stack of active {@code GRAPH <g>} contexts and stop recursion into {@code SERVICE} blocks
 * (whose endpoint has its own schema we cannot validate locally).</p>
 *
 * <p>The visitor is not thread-safe; create one per analysis.</p>
 */
public final class AlgebraAnalysisVisitor {

    private static final Node RDF_TYPE = RDF.type.asNode();

    /** Synthetic predicate for path triples — must not be {@code rdf:type} so SubjectTypeInference
     *  doesn't mistake path endpoint URIs for declared types. */
    private static final Node PATH_PRED_PLACEHOLDER =
            NodeFactory.createURI("urn:opencgmes:path-predicate-placeholder");

    private final List<TriplePatternReference> triples = new ArrayList<>();
    private final Set<ClassRefKey> seenClasses = new LinkedHashSet<>();
    private final Set<PropertyRefKey> seenProperties = new LinkedHashSet<>();
    private final Set<Node> seenGraphBlocks = new LinkedHashSet<>();
    private final List<PathChainReference> pathChains = new ArrayList<>();
    private final Deque<Node> graphStack = new ArrayDeque<>();

    private boolean dynamicPredicate;
    private boolean dynamicClass;

    /** Full ancestor chain for triples currently being visited. Root = [0]. */
    private List<Integer> currentScopeChain = List.of(0);
    /** Counter used to mint fresh scope IDs for UNION branches and OPTIONAL bodies. */
    private int nextScopeId = 1;

    public void walk(Op op) {
        analyze(op);
    }

    public List<TriplePatternReference> triples() {
        return triples;
    }

    public List<ClassReference> classes() {
        var out = new ArrayList<ClassReference>(seenClasses.size());
        for (var k : seenClasses) out.add(new ClassReference(k.term, k.graph));
        return out;
    }

    public List<PropertyReference> properties() {
        var out = new ArrayList<PropertyReference>(seenProperties.size());
        for (var k : seenProperties) out.add(new PropertyReference(k.term, k.graph));
        return out;
    }

    public boolean dynamicPredicate() {
        return dynamicPredicate;
    }

    public boolean dynamicClass() {
        return dynamicClass;
    }

    /** Distinct concrete graph URI nodes encountered in {@code GRAPH <g>} blocks / quad patterns. */
    public Set<Node> graphBlocks() {
        return seenGraphBlocks;
    }

    /** Simple forward-only property-path chains (length >= 2). */
    public List<PathChainReference> pathChains() {
        return pathChains;
    }

    private Node currentGraph() {
        return graphStack.peek();
    }

    private void analyze(Op op) {
        if (op == null) return;
        // Order matters: subclasses before their bases (OpFilter/OpGraph extend Op1).
        switch (op) {
            case OpBGP bgp -> {
                Node g = currentGraph();
                for (Triple t : bgp.getPattern().getList()) {
                    processTriple(t, g);
                }
            }
            case OpQuadPattern qp -> {
                Node g = qp.getGraphNode();
                trackGraphRef(g);
                for (Triple t : qp.getBasicPattern()) {
                    processTriple(t, g);
                }
            }
            case OpQuadBlock qb -> {
                for (var quad : qb.getPattern()) {
                    Node g = quad.getGraph();
                    trackGraphRef(g);
                    processTriple(quad.asTriple(), g);
                }
            }
            case OpPath p -> processTriplePath(p.getTriplePath(), currentGraph());
            case OpFilter f -> {
                analyze(f.getSubOp());
                walkExprs(f.getExprs());
            }
            case OpGraph g -> {
                Node graphNode = g.getNode();
                trackGraphRef(graphNode);
                graphStack.push(graphNode);
                try {
                    analyze(g.getSubOp());
                } finally {
                    graphStack.pop();
                }
            }
            case OpService ignored -> {
                // Do not descend into SERVICE — remote endpoint has its own schema.
            }
            case OpUnion union -> {
                // Each UNION branch is an independent alternative — types from one branch must
                // not bleed into the other. Each branch extends the parent chain with a fresh ID.
                List<Integer> saved = currentScopeChain;
                currentScopeChain = chainWith(saved, nextScopeId++);
                analyze(union.getLeft());
                currentScopeChain = chainWith(saved, nextScopeId++);
                analyze(union.getRight());
                currentScopeChain = saved;
            }
            case OpLeftJoin leftJoin -> {
                // Required part stays in the current chain; the optional body appends a fresh ID
                // so its type assertions don't poison domain checks in the required part.
                // The body inherits the required part's chain as its prefix, so required-part
                // types DO propagate into the optional body.
                analyze(leftJoin.getLeft());
                List<Integer> saved = currentScopeChain;
                currentScopeChain = chainWith(saved, nextScopeId++);
                analyze(leftJoin.getRight());
                currentScopeChain = saved;
            }
            case OpMinus minus -> {
                // The left side is in the current scope; the MINUS right side is a negative
                // pattern — types declared there must NOT affect domain checks in the left side.
                analyze(minus.getLeft());
                analyzeIsolated(minus.getRight());
            }
            case Op1 op1 -> analyze(op1.getSubOp());
            case Op2 op2 -> {
                analyze(op2.getLeft());
                analyze(op2.getRight());
            }
            case OpN opN -> {
                for (Op child : opN.getElements()) analyze(child);
            }
            default -> {
                // Leaf or uninteresting ops: OpTable, OpNull, OpDatasetNames, OpLabel, OpTriple, ...
            }
        }
    }

    /**
     * Walks an iterable of {@link Quad}s — used for INSERT/DELETE templates and
     * {@code DELETE WHERE} patterns in SPARQL Update analysis.
     *
     * <p>Quads whose graph node is the Jena default-graph sentinel are treated as having no
     * named-graph context (graph = {@code null}).</p>
     */
    public void walkQuads(Iterable<Quad> quads) {
        for (Quad q : quads) {
            Node g = q.getGraph();
            Node effectiveGraph = (g != null && g.isURI() && !Quad.isDefaultGraph(g)) ? g : null;
            if (effectiveGraph != null) trackGraphRef(effectiveGraph);
            processTriple(q.asTriple(), effectiveGraph);
        }
    }

    void trackGraphRef(Node g) {
        if (g != null && g.isURI()) {
            seenGraphBlocks.add(g);
        }
    }

    void processTriple(Triple t, Node graph) {
        triples.add(new TriplePatternReference(t, graph, currentScopeChain));
        Node p = t.getPredicate();
        if (p.isURI()) {
            if (RDF_TYPE.equals(p)) {
                Node o = t.getObject();
                if (o.isURI()) {
                    seenClasses.add(new ClassRefKey(o, graph));
                } else if (o.isVariable()) {
                    dynamicClass = true;
                }
            } else {
                seenProperties.add(new PropertyRefKey(p, graph));
            }
        } else if (p.isVariable()) {
            dynamicPredicate = true;
        }
    }

    private void processTriplePath(TriplePath tp, Node graph) {
        if (tp.isTriple()) {
            processTriple(tp.asTriple(), graph);
            return;
        }
        triples.add(new TriplePatternReference(
                Triple.create(tp.getSubject(),
                        tp.getPredicate() == null ? PATH_PRED_PLACEHOLDER : tp.getPredicate(),
                        tp.getObject()), graph, currentScopeChain));
        collectPathUris(tp.getPath(), graph);

        // Also attempt to extract a simple forward chain (e.g. p1/p2/p3) for path-chain checks.
        var chain = new ArrayList<Node>();
        if (collectSimpleSeq(tp.getPath(), chain) && chain.size() >= 2) {
            pathChains.add(new PathChainReference(chain, graph));
        }
    }

    /** True iff {@code path} is a tree of {@code P_Seq} with {@code P_Link} URI leaves only. */
    private static boolean collectSimpleSeq(Path path, List<Node> out) {
        if (path instanceof P_Seq seq) {
            return collectSimpleSeq(seq.getLeft(), out) && collectSimpleSeq(seq.getRight(), out);
        }
        if (path instanceof P_Link link) {
            Node n = link.getNode();
            if (n == null || !n.isURI()) return false;
            out.add(n);
            return true;
        }
        return false;
    }

    private void collectPathUris(Path path, Node graph) {
        if (path == null) return;
        switch (path) {
            case P_Link link -> {
                Node n = link.getNode();
                if (n != null && n.isURI()) {
                    seenProperties.add(new PropertyRefKey(n, graph));
                }
            }
            case P_ReverseLink rl -> {
                Node n = rl.getNode();
                if (n != null && n.isURI()) {
                    seenProperties.add(new PropertyRefKey(n, graph));
                }
            }
            case P_Inverse inv -> collectPathUris(inv.getSubPath(), graph);
            case P_Mod mod -> collectPathUris(mod.getSubPath(), graph);
            case P_ZeroOrMore1 z -> collectPathUris(z.getSubPath(), graph);
            case P_ZeroOrMoreN z -> collectPathUris(z.getSubPath(), graph);
            case P_OneOrMore1 o -> collectPathUris(o.getSubPath(), graph);
            case P_OneOrMoreN o -> collectPathUris(o.getSubPath(), graph);
            case P_ZeroOrOne z -> collectPathUris(z.getSubPath(), graph);
            case P_Alt alt -> {
                collectPathUris(alt.getLeft(), graph);
                collectPathUris(alt.getRight(), graph);
            }
            case P_Seq seq -> {
                collectPathUris(seq.getLeft(), graph);
                collectPathUris(seq.getRight(), graph);
            }
            case P_NegPropSet nps -> {
                for (P_Path0 leaf : nps.getNodes()) {
                    Node n = leaf.getNode();
                    if (n != null && n.isURI()) {
                        seenProperties.add(new PropertyRefKey(n, graph));
                    }
                }
            }
            default -> { /* unknown path subclass — skip */ }
        }
    }

    private void walkExprs(ExprList exprs) {
        if (exprs == null) return;
        var visitor = new ExprVisitorBase() {
            @Override
            public void visit(ExprFunctionOp funcOp) {
                // EXISTS / NOT EXISTS — the embedded pattern is a negative (or existential) scope.
                // Types declared inside must not influence domain checks outside this pattern.
                analyzeIsolated(funcOp.getGraphPattern());
            }
        };
        for (Expr e : exprs) {
            Walker.walk(e, visitor);
        }
    }

    /**
     * Analyze {@code op} in a fresh child scope isolated from the current scope's type
     * declarations. Unknown terms are still validated (the scope chain extends rather than
     * replaces), but any {@code rdf:type} assertions inside will not be visible to triples
     * outside this sub-tree.
     */
    private void analyzeIsolated(Op op) {
        List<Integer> saved = currentScopeChain;
        currentScopeChain = chainWith(saved, nextScopeId++);
        try {
            analyze(op);
        } finally {
            currentScopeChain = saved;
        }
    }

    /** Returns a new immutable list equal to {@code parent} with {@code id} appended. */
    private static List<Integer> chainWith(List<Integer> parent, int id) {
        var copy = new ArrayList<Integer>(parent.size() + 1);
        copy.addAll(parent);
        copy.add(id);
        return List.copyOf(copy);
    }

    private record ClassRefKey(Node term, Node graph) {}

    private record PropertyRefKey(Node term, Node graph) {}
}
