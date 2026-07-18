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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Covers the multi-file validation side: several {@code # [endpoint=...]} file directives — or one
 * glob directive like {@code ./rdf/{a,b}.ttl} — load as a <em>union</em> schema, so a cell querying
 * across multiple profiles gets completion/hover/diagnostics for all of them.
 */
public class SchemaManagerMultiFileEndpointTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

  /** Minimal CIM 16 RDF that passes profile detection, plus one real class declaration. */
  private static String schemaRdf(String profileTag, String className) {
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
             xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
             xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
             xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
      <rdf:Description rdf:about="http://entsoe.eu/TestExt%1$s#TestVersion.shortName">
        <rdfs:domain rdf:resource="http://entsoe.eu/TestExt%1$s#TestVersion"/>
        <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">T%1$s</cims:isFixed>
      </rdf:Description>
      <rdf:Description rdf:about="http://entsoe.eu/TestExt%1$s#TestVersion.entsoeURI">
        <rdfs:domain rdf:resource="http://entsoe.eu/TestExt%1$s#TestVersion"/>
        <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/TestProfile%1$s/1</cims:isFixed>
      </rdf:Description>
      <rdfs:Class rdf:about="http://iec.ch/TC57/2013/CIM-schema-cim16#%2$s">
        <rdfs:label>%2$s</rdfs:label>
      </rdfs:Class>
    </rdf:RDF>
    """
        .formatted(profileTag, className);
  }

  /** A valid CIMXML instance model — must be skipped in a union, never loaded as a schema. */
  private static final String CIMXML_MODEL =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#" xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
       <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
         <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
       </md:FullModel>
      </rdf:RDF>
      """;

  @Test
  public void multipleFileDirectivesLoadAsOneUnionSchema() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    Files.writeString(docDir.resolve("a.rdf"), schemaRdf("A", "ClassA"));
    Files.writeString(docDir.resolve("b.rdf"), schemaRdf("B", "ClassB"));

    SchemaManager manager = new SchemaManager();
    try {
      var rsOpt = manager.resolveSchema(List.of("./a.rdf", "./b.rdf"), docDir);

      assertTrue("the union schema must resolve", rsOpt.isPresent());
      ResolvedSchema rs = rsOpt.get();
      assertClassKnown(rs, "ClassA");
      assertClassKnown(rs, "ClassB");

      // Go-to-definition must jump into the file that actually declares the class.
      assertNotNull("local files must yield a DefinitionIndex", rs.definitionIndex());
      var loc = rs.definitionIndex().locationOf(cimClass("ClassB"));
      assertTrue(loc.isPresent());
      assertTrue(loc.get().getUri().endsWith("b.rdf"));
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void globAndBraceDirectivesExpandToMatchingSchemas() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    Files.writeString(docDir.resolve("a.rdf"), schemaRdf("A", "ClassA"));
    Files.writeString(docDir.resolve("b.rdf"), schemaRdf("B", "ClassB"));
    Files.writeString(docDir.resolve("c.rdf"), schemaRdf("C", "ClassC"));

    SchemaManager manager = new SchemaManager();
    try {
      var all = manager.resolveSchema(List.of("./*.rdf"), docDir);
      assertTrue(all.isPresent());
      assertClassKnown(all.get(), "ClassA");
      assertClassKnown(all.get(), "ClassB");
      assertClassKnown(all.get(), "ClassC");

      var some = manager.resolveSchema(List.of("./{a,b}.rdf"), docDir);
      assertTrue(some.isPresent());
      assertClassKnown(some.get(), "ClassA");
      assertClassKnown(some.get(), "ClassB");
      assertTrue(
          "a brace pattern must not load files outside its alternatives",
          some.get().api().schemaIndex().findClass(cimClass("ClassC")).isEmpty());
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void instanceDataInAUnionIsSkippedNotLoadedAsSchema() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    Files.writeString(docDir.resolve("a.rdf"), schemaRdf("A", "ClassA"));
    Files.writeString(docDir.resolve("model.xml"), CIMXML_MODEL);

    SchemaManager manager = new SchemaManager();
    try {
      // The cell runs against schema + model, but validates against the schema file only.
      var rsOpt = manager.resolveSchema(List.of("./model.xml", "./a.rdf"), docDir);
      assertTrue(rsOpt.isPresent());
      assertClassKnown(rsOpt.get(), "ClassA");
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void conflictingDirectivesFallBackToTheWorkspaceSchema() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    Files.writeString(docDir.resolve("a.rdf"), schemaRdf("A", "ClassA"));

    SchemaManager manager = new SchemaManager();
    try {
      // A URL mixed with a file has no single meaning (the client refuses to execute such a
      // cell); with no opencgmes.jsonc near docDir the workspace fallback is empty.
      assertTrue(
          manager.resolveSchema(List.of("https://example.org/query", "./a.rdf"), docDir).isEmpty());
    } finally {
      manager.shutdown();
    }
  }

  private static Node cimClass(String className) {
    return NodeFactory.createURI(CIM16 + className);
  }

  private static void assertClassKnown(ResolvedSchema rs, String className) {
    assertFalse(
        className + " must be known to the union schema",
        rs.api().schemaIndex().findClass(cimClass(className)).isEmpty());
  }
}
