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

package de.soptim.opencgmes.cimvocabcheck.lsp.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

/**
 * Serializes Jena query results to the wire formats used by {@link ExecuteResponse}: SPARQL 1.1
 * Query Results JSON for SELECT/ASK, Turtle for CONSTRUCT/DESCRIBE.
 *
 * <p>SELECT and CONSTRUCT/DESCRIBE results are truncated at a caller-supplied row/triple limit.
 * Jena's {@code ResultSetFormatter} has no such limit and no boolean/ASK overload, so results are
 * hand-built here; CONSTRUCT/DESCRIBE consume the streaming {@code execConstructTriples()} / {@code
 * execDescribeTriples()} iterators (not the materializing {@code execConstruct()} / {@code
 * execDescribe()}) so an oversized result is never fully loaded into memory.
 */
final class ResultSerializer {

  /** A literal's datatype is omitted from the JSON output when it is this (RDF 1.1 default). */
  private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

  private ResultSerializer() {}

  /** A serialized result payload together with truncation bookkeeping for {@link ExecStats}. */
  record Serialized(String payload, int count, boolean truncated) {}

  /** Serializes a SELECT result to SPARQL 1.1 Query Results JSON, truncated at {@code maxRows}. */
  static Serialized selectToJson(ResultSet rs, int maxRows) {
    List<String> varNames = rs.getResultVars();

    JsonObject head = new JsonObject();
    JsonArray vars = new JsonArray();
    varNames.forEach(vars::add);
    head.add("vars", vars);

    JsonArray bindings = new JsonArray();
    int count = 0;
    while (rs.hasNext() && count < maxRows) {
      QuerySolution sol = rs.next();
      JsonObject binding = new JsonObject();
      for (String var : varNames) {
        RDFNode node = sol.get(var);
        if (node != null) {
          binding.add(var, termToJson(node));
        }
      }
      bindings.add(binding);
      count++;
    }

    JsonObject results = new JsonObject();
    results.add("bindings", bindings);

    JsonObject root = new JsonObject();
    root.add("head", head);
    root.add("results", results);
    return new Serialized(root.toString(), count, rs.hasNext());
  }

  /** Serializes an ASK result to SPARQL 1.1 Query Results JSON. */
  static String askToJson(boolean result) {
    JsonObject root = new JsonObject();
    root.add("head", new JsonObject());
    root.addProperty("boolean", result);
    return root.toString();
  }

  /**
   * Serializes a CONSTRUCT/DESCRIBE triple stream to Turtle, truncated at {@code maxTriples}. Pulls
   * at most {@code maxTriples} elements from {@code triples} via {@code next()}; the truncation
   * check afterward is a plain {@code hasNext()} peek, so an oversized or streaming result is never
   * fully materialized just to detect truncation.
   */
  static Serialized constructToTurtle(Iterator<Triple> triples, int maxTriples) {
    Model model = ModelFactory.createDefaultModel();
    int count = 0;
    while (triples.hasNext() && count < maxTriples) {
      model.getGraph().add(triples.next());
      count++;
    }
    boolean truncated = triples.hasNext();

    StringWriter sw = new StringWriter();
    RDFDataMgr.write(sw, model, RDFFormat.TURTLE_PRETTY);
    return new Serialized(sw.toString(), count, truncated);
  }

  private static JsonObject termToJson(RDFNode node) {
    JsonObject obj = new JsonObject();
    if (node.isURIResource()) {
      obj.addProperty("type", "uri");
      obj.addProperty("value", node.asResource().getURI());
    } else if (node.isAnon()) {
      obj.addProperty("type", "bnode");
      obj.addProperty("value", node.asResource().getId().getLabelString());
    } else {
      Literal literal = node.asLiteral();
      obj.addProperty("type", "literal");
      obj.addProperty("value", literal.getLexicalForm());
      String lang = literal.getLanguage();
      String datatype = literal.getDatatypeURI();
      if (lang != null && !lang.isEmpty()) {
        obj.addProperty("xml:lang", lang);
      } else if (datatype != null && !XSD_STRING.equals(datatype)) {
        obj.addProperty("datatype", datatype);
      }
    }
    return obj;
  }
}
