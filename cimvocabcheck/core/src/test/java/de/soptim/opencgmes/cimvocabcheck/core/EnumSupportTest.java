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

package de.soptim.opencgmes.cimvocabcheck.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests CGMES enumeration support: enumeration members (individuals typed by an enumeration class)
 * are indexed, recognised, and offered, and their misuse as a class/predicate is reported clearly.
 */
public class EnumSupportTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final Node ENUM_CLASS = NodeFactory.createURI(CIM + "WindGenUnitKind");
  private static final Node OFFSHORE = NodeFactory.createURI(CIM + "WindGenUnitKind.offshore");
  private static final Node ONSHORE = NodeFactory.createURI(CIM + "WindGenUnitKind.onshore");

  // CGMES-style RDFS: an enumeration class, two member individuals typed by it, and an object
  // property whose rdfs:range is the enumeration class.
  private static final String SCHEMA =
      "@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
          + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
          + "@prefix cim:  <"
          + CIM
          + "> .\n"
          + "cim:WindGenUnitKind a rdfs:Class .\n"
          + "cim:WindGenUnitKind.offshore a cim:WindGenUnitKind ; rdfs:label \"offshore\" .\n"
          + "cim:WindGenUnitKind.onshore  a cim:WindGenUnitKind ; rdfs:label \"onshore\" .\n"
          + "cim:WindGeneratingUnit a rdfs:Class .\n"
          + "cim:WindGeneratingUnit.windGenUnitType a rdf:Property ;\n"
          + "    rdfs:domain cim:WindGeneratingUnit ; rdfs:range cim:WindGenUnitKind .\n";

  private static final String PREFIXES =
      "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" + "PREFIX cim: <" + CIM + ">\n";

  private RdfsSchemaIndex index;
  private SparqlValidationApi api;

  @Before
  public void setUp() {
    var g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(SCHEMA, Lang.TURTLE).parse(g);
    index = RdfsSchemaIndex.builder().addProfile(VersionIri.of("urn:p"), g).build();
    api = new SparqlValidationApi(index);
  }

  // ---- indexing --------------------------------------------------------------------------

  @Test
  public void enumMembersAreIndexedUnderTheirClass() {
    assertEquals(Set.of(OFFSHORE, ONSHORE), index.enumMembersOf(ENUM_CLASS, null));
    assertTrue(index.enumMemberExists(OFFSHORE, null));
    assertTrue(index.allEnumMembers().contains(ONSHORE));
  }

  @Test
  public void enumMembersAreNotClassesOrProperties() {
    // The enum class itself is a class; its members are neither classes nor properties.
    assertTrue(index.classExists(ENUM_CLASS, null));
    assertFalse(index.classExists(OFFSHORE, null));
    assertFalse(index.propertyExists(OFFSHORE, null));
  }

  // ---- validation ------------------------------------------------------------------------

  @Test
  public void enumValueInObjectPosition_isClean() {
    var r =
        api.validateSparql(
            PREFIXES
                + "SELECT * WHERE { ?u a cim:WindGeneratingUnit ;"
                + " cim:WindGeneratingUnit.windGenUnitType cim:WindGenUnitKind.offshore }");
    assertFalse(
        "enum value used correctly in object position must not be flagged",
        hasCode(r, SparqlValidationCode.UNKNOWN_CLASS)
            || hasCode(r, SparqlValidationCode.UNKNOWN_PROPERTY));
  }

  @Test
  public void enumValueAsType_reportedAsEnumValue() {
    var r = api.validateSparql(PREFIXES + "SELECT * WHERE { ?u a cim:WindGenUnitKind.offshore }");
    var ann =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected UNKNOWN_CLASS for enum-value-as-type"));
    assertTrue(
        "message should explain it is an enumeration value, not a class: " + ann.message(),
        ann.message().contains("enumeration value"));
  }

  @Test
  public void enumValueAsPredicate_reportedAsEnumValue() {
    var r = api.validateSparql(PREFIXES + "SELECT * WHERE { ?u cim:WindGenUnitKind.offshore ?x }");
    var ann =
        r.annotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_PROPERTY)
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("expected UNKNOWN_PROPERTY for enum-value-as-predicate"));
    assertTrue(
        "message should explain it is an enumeration value, not a property: " + ann.message(),
        ann.message().contains("enumeration value"));
  }

  private static boolean hasCode(SparqlValidationResult r, SparqlValidationCode code) {
    return r.annotations().stream().anyMatch(a -> a.code() == code);
  }
}
