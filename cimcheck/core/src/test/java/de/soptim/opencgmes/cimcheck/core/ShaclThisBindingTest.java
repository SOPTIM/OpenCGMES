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

package de.soptim.opencgmes.cimcheck.core;

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.core.shacl.EmbeddedSparql;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclValidationResult;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for SHACL {@code $this} subject-type injection.
 *
 * <p>When an embedded SPARQL constraint lives inside a shape with {@code sh:targetClass}, the
 * validator synthesises a {@code ?this rdf:type <targetClass>} pattern so that domain/range checks
 * fire correctly for {@code $this}.
 *
 * <p>The schema used here is deliberately richer (includes domain maps) so that {@link
 * SparqlValidationCode#PROPERTY_NOT_ALLOWED_FOR_CLASS} can fire.
 */
public class ShaclThisBindingTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  // Classes
  private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
  private static final String CLASS_VOLTAGE = CIM + "VoltageLevel";

  // Properties with explicit domains
  private static final String PROP_R = CIM + "ACLineSegment.r"; // domain=ACLineSegment
  private static final String PROP_NOMINAL_V =
      CIM + "VoltageLevel.nominalVoltage"; // domain=VoltageLevel

  private static final String PROFILE_EQ = "http://example.org/profile/EQ/1.0";
  private static final String PROFILE_TP = "http://example.org/profile/TP/1.0";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    // Rich schema with domain info so domain-check errors can fire.
    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                PROFILE_EQ,
                List.of(CLASS_AC_LINE),
                List.of(PROP_R),
                Map.of(PROP_R, List.of(CLASS_AC_LINE)), // domain
                Map.of(), // range
                Map.of()) // subClassOf
            .addProfile(
                PROFILE_TP,
                List.of(CLASS_VOLTAGE),
                List.of(PROP_NOMINAL_V),
                Map.of(PROP_NOMINAL_V, List.of(CLASS_VOLTAGE)),
                Map.of(),
                Map.of())
            .build();
    api = new SparqlValidationApi(index);
  }

  // ---- Target-class resolution in EmbeddedSparql -----------------------------------------

  @Test
  public void targetClassIsResolvedFromEnclosingShape() {
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    List<EmbeddedSparql> queries = api.extractShaclSparql(g);

    // "CorrectDomainShape" has sh:targetClass cim:ACLineSegment
    boolean found =
        queries.stream()
            .anyMatch(q -> q.targetClasses().contains(NodeFactory.createURI(CLASS_AC_LINE)));
    assertTrue("at least one query should carry cim:ACLineSegment as target class", found);
  }

  @Test
  public void queryWithNoTargetShape_hasEmptyTargetClasses() {
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    List<EmbeddedSparql> queries = api.extractShaclSparql(g);

    // "TargetQueryShape" uses sh:target (SPARQLTarget) and has no sh:targetClass.
    // Its SELECT defines ?this — that query should carry no target classes.
    boolean found =
        queries.stream()
            .anyMatch(
                q ->
                    q.rawQuery().contains("?this a cim:ACLineSegment")
                        && q.targetClasses().isEmpty());
    assertTrue("sh:target-based SELECT should have no injected target class", found);
  }

  // ---- Domain check with $this type injection --------------------------------------------

  @Test
  public void correctPropertyForTargetClass_noError() {
    // CorrectDomainShape: sh:targetClass cim:ACLineSegment, $this cim:ACLineSegment.r ?r
    // After injection: ?this typed as ACLineSegment → domain matches → no error.
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    ShaclValidationResult r = api.validateShacl(g);

    boolean spurious =
        r.embeddedResults().stream()
            .filter(er -> er.embedded().rawQuery().contains("ACLineSegment.r"))
            .flatMap(er -> er.result().annotations().stream())
            .anyMatch(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS);
    assertFalse("correct property for target class must not produce a domain error", spurious);
  }

  @Test
  public void wrongPropertyForTargetClass_emitsDomainError() {
    // WrongDomainShape: sh:targetClass cim:ACLineSegment, $this cim:VoltageLevel.nominalVoltage
    // After injection: ?this typed as ACLineSegment → domain of nominalVoltage is VoltageLevel →
    // MISMATCH.
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    ShaclValidationResult r = api.validateShacl(g);

    boolean found =
        r.embeddedResults().stream()
            .filter(
                er ->
                    er.embedded().rawQuery().contains("VoltageLevel.nominalVoltage")
                        && er.embedded()
                            .targetClasses()
                            .contains(NodeFactory.createURI(CLASS_AC_LINE)))
            .flatMap(er -> er.result().annotations().stream())
            .anyMatch(
                a ->
                    a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS
                        && a.term().getURI().equals(PROP_NOMINAL_V));
    assertTrue(
        "wrong-domain property for $this must produce PROPERTY_NOT_ALLOWED_FOR_CLASS", found);
  }

  @Test
  public void explicitTypeInQuery_injectionSkipped_noSpuriousError() {
    // ExplicitTypeShape: sh:targetClass cim:VoltageLevel, but the query has
    //   $this a cim:ACLineSegment . $this cim:ACLineSegment.r ?r .
    // The explicit type wins over the injected one → domain check uses ACLineSegment → no error.
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    ShaclValidationResult r = api.validateShacl(g);

    boolean spurious =
        r.embeddedResults().stream()
            .filter(er -> er.embedded().rawQuery().contains("$this a cim:ACLineSegment"))
            .flatMap(er -> er.result().annotations().stream())
            .anyMatch(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS);
    assertFalse("explicit rdf:type in query must take precedence over injected type", spurious);
  }

  @Test
  public void noTargetClass_domainCheckSkipped_noImpliedTypeInfo() {
    // TargetQueryShape: no sh:targetClass, $this has no explicit type → no injection.
    // The query "SELECT ?this WHERE { ?this a cim:ACLineSegment }" has ?this typed explicitly.
    // But TargetQueryShape has no sh:sparql with domain-mismatch — this test checks the
    // "no-injection" path produces no PROPERTY_NOT_ALLOWED error.
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    ShaclValidationResult r = api.validateShacl(g);

    // TargetQueryShape's SELECT references only ACLineSegment (type assertion only) — no domain
    // mismatch.
    boolean domainError =
        r.embeddedResults().stream()
            .filter(
                er ->
                    er.embedded().targetClasses().isEmpty()
                        && er.embedded().rawQuery().contains("?this a cim:ACLineSegment"))
            .flatMap(er -> er.result().annotations().stream())
            .anyMatch(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS);
    assertFalse("query with no target class should not produce PROPERTY_NOT_ALLOWED", domainError);
  }

  @Test
  public void targetClassViaPropertyShape_isResolved() {
    // ViaPropShape: sh:targetClass is on the NodeShape, the query is inside sh:property ->
    // sh:sparql.
    // The two-hop resolution must propagate sh:targetClass to the inner container.
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    List<EmbeddedSparql> queries = api.extractShaclSparql(g);

    // The ViaPropShape embedded query contains "VoltageLevel.nominalVoltage" and should
    // carry cim:ACLineSegment as its target class (inherited from the NodeShape).
    boolean found =
        queries.stream()
            .anyMatch(
                q ->
                    q.rawQuery().contains("VoltageLevel.nominalVoltage")
                        && q.targetClasses().contains(NodeFactory.createURI(CLASS_AC_LINE))
                        // distinguish from WrongDomainShape (also nominalVoltage + ACLineSegment)
                        && q.rawQuery().contains("$this cim:VoltageLevel.nominalVoltage ?bad"));
    assertTrue("target class must be propagated through sh:property to the embedded query", found);
  }

  @Test
  public void targetClassViaPropertyShape_domainErrorFires() {
    // ViaPropShape uses nominalVoltage (domain=VoltageLevel) on $this (typed as ACLineSegment) →
    // error.
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    ShaclValidationResult r = api.validateShacl(g);

    boolean found =
        r.embeddedResults().stream()
            .filter(
                er ->
                    er.embedded().rawQuery().contains("$this cim:VoltageLevel.nominalVoltage ?bad"))
            .flatMap(er -> er.result().annotations().stream())
            .anyMatch(a -> a.code() == SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS);
    assertTrue("domain error must fire for query embedded inside sh:property", found);
  }

  // ---- QUERY_IMPLIED_TYPE suppression in embedded SHACL queries --------------------------

  @Test
  public void impliedTypeForIntermediateVar_isSuppressed() {
    // IntermediateVarShape: ?v has no explicit rdf:type but appears as subject of
    // cim:VoltageLevel.nominalVoltage (domain=VoltageLevel). Without suppression,
    // QUERY_IMPLIED_TYPE (INFO) would fire for ?v. After the fix it must be absent.
    Graph g = loadShapes("shacl/shapes-this-binding.ttl");
    ShaclValidationResult r = api.validateShacl(g);

    boolean impliedType =
        r.embeddedResults().stream()
            .flatMap(er -> er.result().annotations().stream())
            .anyMatch(a -> a.code() == SparqlValidationCode.QUERY_IMPLIED_TYPE);
    assertFalse(
        "QUERY_IMPLIED_TYPE must be suppressed in SHACL embedded query results", impliedType);
  }

  // ---- helpers ---------------------------------------------------------------------------

  private static Graph loadShapes(String resourcePath) {
    try (InputStream in =
        ShaclThisBindingTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("missing resource: " + resourcePath);
      }
      Graph g = GraphFactory.createDefaultGraph();
      RDFParser.fromString(new String(in.readAllBytes()), Lang.TURTLE).parse(g);
      return g;
    } catch (Exception e) {
      throw new RuntimeException("failed to load " + resourcePath, e);
    }
  }
}
