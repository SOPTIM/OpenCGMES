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

package de.soptim.opencgmes.cimxmlcli.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Set;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrefixSanitizerTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void keepsIrisIntactInCompactedJsonLd() throws IOException {
    final var dataset = DatasetGraphFactory.create();
    final var graph = NodeFactory.createURI("cgmes:model:EQ");
    final var subject = NodeFactory.createURI("urn:uuid:6f1c2a3e-63a5-4a8e-9d55-4b7f2b8c9e01");
    final var predicate = NodeFactory.createURI("http://iec.ch/TC57/CIM100#IdentifiedObject.name");
    dataset.add(graph, subject, predicate, NodeFactory.createLiteralString("x"));
    // Prefixes named like the schemes in the data would corrupt the IRIs
    dataset.prefixes().add("urn", "http://example.org/urn#");
    dataset.prefixes().add("cgmes", "http://example.org/cgmes#");
    dataset.prefixes().add("cim", "http://iec.ch/TC57/CIM100#");
    final var target = OutputTarget.of(tmp.getRoot().toPath().resolve("x.jsonld"));

    new JsonLdDatasetWriter(true).write(dataset, target);

    final var read = RDFDataMgr.loadDatasetGraph(target.path().toString());
    assertTrue(read.contains(graph, subject, predicate, NodeFactory.createLiteralString("x")));
    assertEquals(Set.of("cim"), dataset.prefixes().getMapping().keySet());
  }

  @Test
  public void reportsTheRemovedPrefixes() {
    final var dataset = DatasetGraphFactory.create();
    final var node = NodeFactory.createURI("urn:x");
    dataset.add(NodeFactory.createURI("cgmes:schema:EQ"), node, node, node);
    dataset.prefixes().add("URN", "http://example.org/");
    dataset.prefixes().add("ex", "http://example.org/ex#");

    assertEquals(Set.of("URN"), PrefixSanitizer.sanitize(dataset));
  }
}
