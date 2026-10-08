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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonPrimitive;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookCommandHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for the {@code cimvocabcheck.schemaInfo} workspace command: resolving the schema files the
 * nearest {@code opencgmes.jsonc} declares for a document.
 */
public class SchemaInfoCommandTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private SparqlWorkspaceService workspace;

  @Before
  public void setUp() {
    SchemaManager schemaManager = new SchemaManager();
    SparqlTextDocumentService documents =
        new SparqlTextDocumentService(schemaManager, new NotebookDefaults());
    workspace =
        new SparqlWorkspaceService(
            schemaManager, documents, new NotebookCommandHandler(), new NotebookDefaults());
  }

  private Object run(Object... args) throws Exception {
    return workspace
        .executeCommand(
            new ExecuteCommandParams(SparqlWorkspaceService.CMD_SCHEMA_INFO, List.of(args)))
        .get();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object result) {
    assertTrue("expected a map result, got: " + result, result instanceof Map);
    return (Map<String, Object>) result;
  }

  @Test
  public void resolvesExplicitSchemasList() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path schema = Files.createFile(root.resolve("EquipmentProfile.rdf"));
    Files.writeString(
        root.resolve("opencgmes.jsonc"),
        "{ \"cimvocabcheck\": { \"schemas\": [\"EquipmentProfile.rdf\"] } }");

    Object result = run(root.resolve("query.rq").toUri().toString());

    Map<String, Object> info = asMap(result);
    assertEquals(root.resolve("opencgmes.jsonc").toString(), info.get("configFile"));
    assertEquals(List.of(schema.toAbsolutePath().toString()), info.get("schemaFiles"));
  }

  @Test
  public void resolvesSchemasDirectory() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path dir = Files.createDirectory(root.resolve("schemas"));
    Path a = Files.createFile(dir.resolve("A.rdf"));
    Path b = Files.createFile(dir.resolve("B.ttl"));
    Files.createFile(dir.resolve("notes.txt")); // not a schema file
    Files.writeString(
        root.resolve("opencgmes.jsonc"),
        "{ \"cimvocabcheck\": { \"schemasDirectory\": \"schemas\" } }");

    Object result = run(root.resolve("sub/query.rq").toUri().toString());

    assertEquals(
        List.of(a.toAbsolutePath().toString(), b.toAbsolutePath().toString()),
        asMap(result).get("schemaFiles"));
  }

  @Test
  public void acceptsJsonRpcArgumentForm() throws Exception {
    Path root = tmp.getRoot().toPath();
    Files.createFile(root.resolve("EquipmentProfile.rdf"));
    Files.writeString(
        root.resolve("opencgmes.jsonc"),
        "{ \"cimvocabcheck\": { \"schemas\": [\"EquipmentProfile.rdf\"] } }");

    Object result = run(new JsonPrimitive(root.resolve("query.rq").toUri().toString()));

    assertEquals(root.resolve("opencgmes.jsonc").toString(), asMap(result).get("configFile"));
  }

  @Test
  public void nullWhenNoConfigApplies() throws Exception {
    assertNull(run(tmp.getRoot().toPath().resolve("query.rq").toUri().toString()));
  }

  @Test
  public void nullWhenConfigDeclaresNoSchemas() throws Exception {
    Path root = tmp.getRoot().toPath();
    Files.writeString(root.resolve("opencgmes.jsonc"), "{ \"cimvocabcheck\": { } }");

    assertNull(run(root.resolve("query.rq").toUri().toString()));
  }

  @Test
  public void nullWhenSchemasDirectoryMissing() throws Exception {
    Path root = tmp.getRoot().toPath();
    Files.writeString(
        root.resolve("opencgmes.jsonc"),
        "{ \"cimvocabcheck\": { \"schemasDirectory\": \"does-not-exist\" } }");

    assertNull(run(root.resolve("query.rq").toUri().toString()));
  }

  @Test
  public void nullOnMissingArguments() throws Exception {
    assertNull(run());
  }
}
