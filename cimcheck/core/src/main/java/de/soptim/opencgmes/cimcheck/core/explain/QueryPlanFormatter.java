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

package de.soptim.opencgmes.cimcheck.core.explain;

import org.apache.jena.atlas.io.IndentedLineBuffer;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpAsQuery;
import org.apache.jena.sparql.serializer.SerializationContext;

/**
 * Formats a Jena {@link Op} algebra tree as an SSE-style plan string, similar in spirit to {@code
 * arq.query --explain}.
 *
 * <p>Implementation note: we use Jena's built-in SSE serialization ({@link
 * Op#output(org.apache.jena.atlas.io.IndentedWriter, SerializationContext)}) so the output is
 * deterministic across runs and stable across Jena minor versions.
 */
public final class QueryPlanFormatter {

  private QueryPlanFormatter() {}

  /** Format the algebra for an already-compiled query. */
  public static String format(Query query, Op op) {
    var buf = new IndentedLineBuffer();
    SerializationContext sc =
        new SerializationContext(query == null ? null : query.getPrefixMapping());
    op.output(buf, sc);
    return buf.asString().stripTrailing();
  }

  /** Convenience: compile and format in one go. */
  public static String format(Query query) {
    return format(query, Algebra.compile(query));
  }

  /**
   * Compile the query, run Jena's static optimizer over the algebra and format the result.
   *
   * <p>The optimized form is what makes a plan instructive: it shows filter placement ({@code
   * (filter ...)} pushed down into the relevant sub-op) and BGP/join reordering, mirroring what
   * {@code arq.qparse --print=opt} prints.
   */
  public static String formatOptimized(Query query) {
    return format(query, Algebra.optimize(Algebra.compile(query)));
  }

  /** Round-trip an Op back to a SPARQL string — handy for debugging. */
  public static String toSparql(Op op) {
    Query q = OpAsQuery.asQuery(op);
    return q.serialize();
  }
}
