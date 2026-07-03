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
import org.apache.jena.graph.NodeFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Reproduces and covers the fix for go-to-definition on a local-file {@code # [endpoint=...]}
 * directive: unlike a remote SPARQL endpoint (which has no source file and is instead handled by
 * {@link EndpointDefinitionPeek}), a local endpoint file is a real, on-disk schema — {@link
 * SchemaManager#resolveSchema} must build a {@link DefinitionIndex} for it so {@code
 * textDocument/definition} can jump straight into that file, exactly like the workspace-schema path
 * already does.
 */
public class SchemaManagerEndpointDefinitionTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

  /** Minimal CIM 16 RDF that passes profile detection, plus one real class declaration. */
  private static final String SCHEMA_RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.shortName">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">TST</cims:isFixed>
        </rdf:Description>
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.entsoeURI">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/TestProfile/1</cims:isFixed>
        </rdf:Description>
        <rdfs:Class rdf:about="http://iec.ch/TC57/2013/CIM-schema-cim16#TestClass">
          <rdfs:label>TestClass</rdfs:label>
        </rdfs:Class>
      </rdf:RDF>
      """;

  @Test
  public void localFileEndpoint_resolvesDefinitionIndex_forGoToDefinition() throws Exception {
    Path docDir = tmp.getRoot().toPath();
    Files.writeString(docDir.resolve("schema.rdf"), SCHEMA_RDF);

    SchemaManager manager = new SchemaManager();
    try {
      // Relative endpoint path, resolved against the notebook document's own directory — exactly
      // how "# [endpoint=./schema.rdf]" is resolved for a real notebook cell.
      var rsOpt = manager.resolveSchema("./schema.rdf", docDir);

      assertTrue("local endpoint schema must resolve", rsOpt.isPresent());
      ResolvedSchema rs = rsOpt.get();
      assertNotNull(
          "a local-file endpoint has a real source file, so a DefinitionIndex must be built"
              + " (this is what go-to-definition needs)",
          rs.definitionIndex());

      var testClass = NodeFactory.createURI(CIM16 + "TestClass");
      assertFalse(
          "TestClass must be known to the loaded schema",
          rs.api().schemaIndex().findClass(testClass).isEmpty());

      var loc = rs.definitionIndex().locationOf(testClass);
      assertTrue("go-to-definition must resolve a location for TestClass", loc.isPresent());
      assertTrue(
          "the location must point into the actual schema file, not a synthesized peek",
          loc.get().getUri().endsWith("schema.rdf"));
    } finally {
      manager.shutdown();
    }
  }
}
