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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.Test;

public class ResultSerializerTest {

  private static final String EX = "http://example.org/";

  @Test
  public void selectToJsonSerializesEachTermKind() {
    Model model = ModelFactory.createDefaultModel();
    Resource s = model.createResource(EX + "s1");
    Property p = model.createProperty(EX + "p");
    s.addProperty(p, model.createResource(EX + "o1"));
    s.addProperty(p, model.createResource());
    s.addProperty(p, "plain");
    s.addProperty(p, model.createLiteral("hello", "en"));
    s.addProperty(p, model.createTypedLiteral("42", XSDDatatype.XSDinteger));

    ResultSerializer.Serialized serialized;
    try (QueryExecution qe =
        QueryExecutionFactory.create(
            "SELECT ?o WHERE { <" + EX + "s1> <" + EX + "p> ?o }", model)) {
      serialized = ResultSerializer.selectToJson(qe.execSelect(), 100);
    }

    assertEquals(5, serialized.count());
    assertFalse(serialized.truncated());

    Set<String> descriptors = new HashSet<>();
    for (JsonObject binding : bindings(serialized.payload())) {
      JsonObject o = binding.getAsJsonObject("o");
      String type = o.get("type").getAsString();
      String value = o.get("value").getAsString();
      String suffix =
          o.has("xml:lang")
              ? ";lang=" + o.get("xml:lang").getAsString()
              : o.has("datatype") ? ";datatype=" + o.get("datatype").getAsString() : "";
      descriptors.add(type + ":" + value + suffix);
    }

    assertEquals(5, descriptors.size());
    assertTrue(descriptors.contains("uri:" + EX + "o1"));
    assertTrue(descriptors.contains("literal:plain"));
    assertTrue(descriptors.contains("literal:hello;lang=en"));
    assertTrue(
        descriptors.contains("literal:42;datatype=http://www.w3.org/2001/XMLSchema#integer"));
    assertTrue(
        "expected exactly one bnode term",
        descriptors.stream().anyMatch(d -> d.startsWith("bnode:")));
  }

  @Test
  public void selectToJsonOmitsDatatypeForPlainXsdString() {
    Model model = ModelFactory.createDefaultModel();
    Resource s = model.createResource(EX + "s1");
    s.addProperty(
        model.createProperty(EX + "p"),
        model.createTypedLiteral("plain-typed", XSDDatatype.XSDstring));

    ResultSerializer.Serialized serialized;
    try (QueryExecution qe =
        QueryExecutionFactory.create(
            "SELECT ?o WHERE { <" + EX + "s1> <" + EX + "p> ?o }", model)) {
      serialized = ResultSerializer.selectToJson(qe.execSelect(), 100);
    }

    JsonObject o = bindings(serialized.payload()).get(0).getAsJsonObject("o");
    assertEquals("literal", o.get("type").getAsString());
    assertFalse(
        "xsd:string is the RDF 1.1 default and should not be emitted as an explicit datatype",
        o.has("datatype"));
  }

  @Test
  public void selectToJsonTruncatesAtMaxRows() {
    Model model = ModelFactory.createDefaultModel();
    Resource s = model.createResource(EX + "s1");
    Property p = model.createProperty(EX + "p");
    for (int i = 0; i < 5; i++) {
      s.addProperty(p, model.createTypedLiteral(i));
    }

    ResultSerializer.Serialized serialized;
    try (QueryExecution qe =
        QueryExecutionFactory.create(
            "SELECT ?o WHERE { <" + EX + "s1> <" + EX + "p> ?o }", model)) {
      serialized = ResultSerializer.selectToJson(qe.execSelect(), 3);
    }

    assertEquals(3, serialized.count());
    assertTrue(serialized.truncated());
    assertEquals(3, bindings(serialized.payload()).size());
  }

  @Test
  public void selectToJsonNotTruncatedWhenResultFitsExactlyAtMaxRows() {
    Model model = ModelFactory.createDefaultModel();
    Resource s = model.createResource(EX + "s1");
    s.addProperty(model.createProperty(EX + "p"), model.createTypedLiteral(1));

    ResultSerializer.Serialized serialized;
    try (QueryExecution qe =
        QueryExecutionFactory.create(
            "SELECT ?o WHERE { <" + EX + "s1> <" + EX + "p> ?o }", model)) {
      serialized = ResultSerializer.selectToJson(qe.execSelect(), 1);
    }

    assertEquals(1, serialized.count());
    assertFalse(serialized.truncated());
  }

  @Test
  public void askToJsonSerializesTrueAndFalse() {
    JsonObject trueResult =
        JsonParser.parseString(ResultSerializer.askToJson(true)).getAsJsonObject();
    assertTrue(trueResult.get("boolean").getAsBoolean());

    JsonObject falseResult =
        JsonParser.parseString(ResultSerializer.askToJson(false)).getAsJsonObject();
    assertFalse(falseResult.get("boolean").getAsBoolean());
  }

  @Test
  public void constructToTurtleSerializesTriplesAsTurtle() {
    List<Triple> triples =
        List.of(
            Triple.create(uri("s1"), uri("p"), uri("o1")),
            Triple.create(uri("s1"), uri("p"), uri("o2")));

    ResultSerializer.Serialized serialized =
        ResultSerializer.constructToTurtle(triples.iterator(), 100);

    assertEquals(2, serialized.count());
    assertFalse(serialized.truncated());
    // Parse the Turtle back to confirm it round-trips rather than asserting exact formatting.
    Model roundTripped = ModelFactory.createDefaultModel();
    roundTripped.read(new java.io.StringReader(serialized.payload()), null, "TURTLE");
    assertEquals(2, roundTripped.size());
  }

  @Test
  public void constructToTurtleTruncatesAtMaxTriplesWithoutConsumingWholeIterator() {
    List<Triple> triples = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      triples.add(Triple.create(uri("s1"), uri("p"), uri("o" + i)));
    }
    // A spy iterator that counts how many elements are actually pulled via next(), to confirm
    // the truncation cap stops before draining the whole (potentially oversized/streaming)
    // source — checking truncation via a plain hasNext() call does not itself pull an element.
    CountingIterator<Triple> counting = new CountingIterator<>(triples.iterator());

    ResultSerializer.Serialized serialized = ResultSerializer.constructToTurtle(counting, 2);

    assertEquals(2, serialized.count());
    assertTrue(serialized.truncated());
    assertEquals(2, counting.pulled);
  }

  private static Node uri(String localName) {
    return NodeFactory.createURI(EX + localName);
  }

  private static List<JsonObject> bindings(String resultsJson) {
    JsonObject root = JsonParser.parseString(resultsJson).getAsJsonObject();
    JsonArray bindings = root.getAsJsonObject("results").getAsJsonArray("bindings");
    List<JsonObject> out = new ArrayList<>();
    for (JsonElement e : bindings) {
      out.add(e.getAsJsonObject());
    }
    return out;
  }

  /** Wraps an iterator to count how many elements {@link #next()} actually returns. */
  private static final class CountingIterator<T> implements Iterator<T> {
    private final Iterator<T> delegate;
    private int pulled;

    CountingIterator(Iterator<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public T next() {
      T value = delegate.next();
      pulled++;
      return value;
    }
  }
}
