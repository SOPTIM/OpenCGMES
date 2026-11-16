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

package de.soptim.opencgmes.cimreader.core;

import de.soptim.opencgmes.cimreader.core.source.ModelSource;
import de.soptim.opencgmes.cimreader.core.source.XmlSource;
import de.soptim.opencgmes.cimxml.CimHeaderVocabulary;
import de.soptim.opencgmes.cimxml.graph.CimModelHeader;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDF;


public final class TestModelBuilder {

  private static final String NS_CIM = "http://iec.ch/TC57/CIM100#";

  private final String modelIri;
  private final boolean difference;
  private boolean withModel = true;
  private String version = "1";
  private String modelingAuthoritySet = "http://example.com/mas";
  private String scenarioTime = "2026-01-01T00:00:00Z";
  private final List<String> profiles =
      new ArrayList<>(List.of("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"));
  private final List<String> supersedes = new ArrayList<>();
  private final List<String> dependentOn = new ArrayList<>();
  private final List<Element> body = new ArrayList<>();
  private final List<Element> forward = new ArrayList<>();
  private final List<Element> reverse = new ArrayList<>();

  private TestModelBuilder(String modelIri, boolean difference) {
    this.modelIri = modelIri;
    this.difference = difference;
  }

  public static TestModelBuilder fullModel(String modelIri) {
    return new TestModelBuilder(modelIri, false);
  }

  public static TestModelBuilder differenceModel(String modelIri) {
    return new TestModelBuilder(modelIri, true);
  }

  // ---------- Header ----------

  public TestModelBuilder version(String value) {
    version = value;
    return this;
  }

  public TestModelBuilder scenarioTime(String value) {
    scenarioTime = value;
    return this;
  }

  public TestModelBuilder modelingAuthoritySet(String value) {
    modelingAuthoritySet = value;
    return this;
  }

  public TestModelBuilder profile(String... values) {
    profiles.addAll(List.of(values));
    return this;
  }

  public TestModelBuilder supersedes(String... modelIris) {
    supersedes.addAll(List.of(modelIris));
    return this;
  }

  public TestModelBuilder dependentOn(String... modelIris) {
    dependentOn.addAll(List.of(modelIris));
    return this;
  }

  public TestModelBuilder withoutModel() {
    withModel = false;
    return this;
  }

  public TestModelBuilder withoutVersion() {
    return version(null);
  }

  public TestModelBuilder withoutScenarioTime() {
    return scenarioTime(null);
  }

  public TestModelBuilder withoutModelingAuthoritySet() {
    return modelingAuthoritySet(null);
  }

  public TestModelBuilder withoutProfiles() {
    profiles.clear();
    return this;
  }

  // ---------- Body ----------

  public TestModelBuilder element(String id, String cimClass, String... propertyValuePairs) {
    requireType(false);
    body.add(Element.of(id, cimClass, propertyValuePairs));
    return this;
  }

  public TestModelBuilder add(String id, String cimClass, String... propertyValuePairs) {
    requireType(true);
    forward.add(Element.of(id, cimClass, propertyValuePairs));
    return this;
  }

  public TestModelBuilder remove(String id, String cimClass, String... propertyValuePairs) {
    requireType(true);
    reverse.add(Element.of(id, cimClass, propertyValuePairs));
    return this;
  }

  // ---------- Outputs ----------

  public String toXml() {
    if (!withModel) {
      throw new IllegalStateException("withoutModel() is only supported by toHeader()");
    }
    var sb = new StringBuilder()
        .append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        .append("<rdf:RDF xmlns:rdf=\"").append(RDF.getURI())
        .append("\" xmlns:md=\"").append(CimHeaderVocabulary.NS_MD)
        .append("\" xmlns:dm=\"").append(CimHeaderVocabulary.NS_DM)
        .append("\" xmlns:cim=\"").append(NS_CIM).append("\">\n");
    var tag = difference ? "dm:DifferenceModel" : "md:FullModel";
    sb.append("  <").append(tag).append(" rdf:about=\"").append(escape(modelIri)).append("\">\n");
    literal(sb, "md:Model.version", version);
    literal(sb, "md:Model.modelingAuthoritySet", modelingAuthoritySet);
    literal(sb, "md:Model.scenarioTime", scenarioTime);
    profiles.forEach(p -> literal(sb, "md:Model.profile", p));
    supersedes.forEach(s -> resource(sb, "md:Model.Supersedes", s));
    dependentOn.forEach(d -> resource(sb, "md:Model.DependentOn", d));
    if (difference) {
      statements(sb, "dm:forwardDifferences", forward);
      statements(sb, "dm:reverseDifferences", reverse);
    }
    sb.append("  </").append(tag).append(">\n");
    body.forEach(e -> e.appendTo(sb, "rdf:ID=\"" + escape(e.id())
        + "\"", "  "));
    return sb.append("</rdf:RDF>\n").toString();
  }

  public XmlSource toXmlSource() {
    return new InMemoryXmlSource(modelIri, toXml().getBytes(StandardCharsets.UTF_8));
  }

  public ModelSource toModelSource() {
    var source = toXmlSource();
    try (var in = source.open()) {
      return new ModelSource(new CimXmlParser().parseCimModelHeader(in), source);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public CimModelHeader toHeader() {
    Graph graph = GraphFactory.createDefaultGraph();
    graph.getPrefixMapping()
        .setNsPrefix("cim", NS_CIM)
        .setNsPrefix("md", CimHeaderVocabulary.NS_MD)
        .setNsPrefix("dm", CimHeaderVocabulary.NS_DM)
        .setNsPrefix("rdf", RDF.getURI());
    var model = NodeFactory.createURI(modelIri);
    if (withModel) {
      graph.add(Triple.create(model, RDF.type.asNode(), difference
          ? CimHeaderVocabulary.TYPE_DIFFERENCE_MODEL : CimHeaderVocabulary.TYPE_FULL_MODEL));
    }
    addLiteral(graph, model, CimHeaderVocabulary.PREDICATE_VERSION, version);
    addLiteral(graph, model, CimHeaderVocabulary.PREDICATE_MODELING_AUTHORITY_SET,
        modelingAuthoritySet);
    addLiteral(graph, model, CimHeaderVocabulary.PREDICATE_SCENARIO_TIME, scenarioTime);
    profiles.forEach(p -> addLiteral(graph, model, CimHeaderVocabulary.PREDICATE_PROFILE, p));
    supersedes.forEach(s -> graph.add(Triple.create(model,
        CimHeaderVocabulary.PREDICATE_SUPERSEDES, NodeFactory.createURI(s))));
    dependentOn.forEach(d -> graph.add(Triple.create(model,
        CimHeaderVocabulary.PREDICATE_DEPENDENT_ON, NodeFactory.createURI(d))));
    return CimModelHeader.wrap(graph);
  }

  public Path writeTo(Path directory) throws IOException {
    var file = directory.resolve(modelIri.replaceAll("[^A-Za-z0-9._-]", "_")
        + ".xml");
    return Files.writeString(file, toXml(), StandardCharsets.UTF_8);
  }

  // ---------- Internals ----------

  private void requireType(boolean expectDifference) {
    if (difference != expectDifference) {
      throw new IllegalStateException(expectDifference
          ? "add/remove are only allowed for difference models"
          : "element is only allowed for full models");
    }
  }

  private static void literal(StringBuilder sb, String tag, String value) {
    if (value != null) {
      sb.append("    <").append(tag).append('>').append(escape(value))
          .append("</").append(tag).append(">\n");
    }
  }

  private static void resource(StringBuilder sb, String tag, String iri) {
    sb.append("    <").append(tag).append(" rdf:resource=\"").append(escape(iri)).append("\"/>\n");
  }

  private static void statements(StringBuilder sb, String tag, List<Element> elements) {
    sb.append("    <").append(tag).append(" rdf:parseType=\"Statements\">\n");
    elements.forEach(e -> e.appendTo(sb, "rdf:about=\"#"
        + escape(e.id()) + "\"", "      "));
    sb.append("    </").append(tag).append(">\n");
  }

  private static void addLiteral(Graph graph, Node subject, Node predicate, String value) {
    if (value != null) {
      graph.add(Triple.create(subject, predicate, NodeFactory.createLiteralString(value)));
    }
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;");
  }

  private record Element(String id, String cimClass, Map<String, String> properties) {

    static Element of(String id, String cimClass, String... propertyValuePairs) {
      if (propertyValuePairs.length % 2 != 0) {
        throw new IllegalArgumentException("Expected property/value pairs");
      }
      var properties = new LinkedHashMap<String, String>();
      for (int i = 0; i < propertyValuePairs.length; i += 2) {
        properties.put(propertyValuePairs[i], propertyValuePairs[i + 1]);
      }
      return new Element(id, cimClass, properties);
    }

    void appendTo(StringBuilder sb, String idAttribute, String indent) {
      var tag = cimClass == null ? "rdf:Description" : "cim:" + cimClass;
      sb.append(indent).append('<').append(tag).append(' ').append(idAttribute).append(">\n");
      properties.forEach((p, v) -> sb.append(indent).append("  <cim:")
          .append(p).append('>')
          .append(escape(v)).append("</cim:").append(p).append(">\n"));
      sb.append(indent).append("</").append(tag).append(">\n");
    }
  }

  public record InMemoryXmlSource(String name, byte[] content) implements XmlSource {

    @Override
    public InputStream open() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public String toString() {
      return name;
    }
  }
}