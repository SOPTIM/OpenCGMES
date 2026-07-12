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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exercises {@link NotebookConfigLoader}: the lenient JSONC parsing of the {@code "cimnotebook"}
 * section (comments, trailing commas, the {@code "default"} keyword-named flag), git-style
 * nearest-config discovery, and the deliberately forgiving failure modes.
 */
public class NotebookConfigLoaderTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String CONFIG_JSONC =
      """
      {
        // validator settings live in a sibling section and are irrelevant here
        "cimvocabcheck": { "strictness": "strict" },
        "cimnotebook": {
          "queryTimeoutSeconds": 5,
          "maxRows": 100,
          "connections": [
            {
              "name": "local-fuseki",
              "url": "http://localhost:3030/cgmes/query",
              "updateUrl": "http://localhost:3030/cgmes/update",
              "authType": "basic",
              "default": true,
            },
            { "name": "prod", "url": "https://sparql.example.org/query" },
          ],
        },
      }
      """;

  @Test
  public void readsConnectionsTimeoutsAndTheDefaultFlag() throws IOException {
    Path dir = tmp.getRoot().toPath();
    Files.writeString(dir.resolve("opencgmes.jsonc"), CONFIG_JSONC);

    NotebookConfigLoader.Located located = NotebookConfigLoader.forDirectory(dir);

    assertEquals(dir.resolve("opencgmes.jsonc"), located.configPath());
    NotebookConfig config = located.config();
    assertEquals(Integer.valueOf(5), config.queryTimeoutSeconds());
    assertEquals(Integer.valueOf(100), config.maxRows());
    assertEquals(2, config.connections().size());

    NotebookConnection fuseki = config.byName("local-fuseki");
    assertEquals("http://localhost:3030/cgmes/query", fuseki.url());
    assertEquals("http://localhost:3030/cgmes/update", fuseki.updateUrl());
    assertNull(fuseki.shaclUrl());
    assertEquals("basic", fuseki.authType());
    assertTrue(fuseki.isDefault());
    assertEquals(fuseki, config.defaultConnection());

    NotebookConnection prod = config.byName("prod");
    assertTrue(!prod.isDefault());
    assertNull(config.byName("nope"));
  }

  @Test
  public void discoveryWalksUpToTheNearestConfig() throws IOException {
    Path root = tmp.getRoot().toPath();
    Files.writeString(root.resolve("opencgmes.jsonc"), CONFIG_JSONC);
    Path nested = Files.createDirectories(root.resolve("reports/2026"));

    NotebookConfigLoader.Located located = NotebookConfigLoader.forDirectory(nested);

    assertEquals(root.resolve("opencgmes.jsonc"), located.configPath());
    assertEquals(2, located.config().connections().size());
  }

  @Test
  public void missingSectionYieldsTheEmptyConfig() throws IOException {
    Path dir = tmp.getRoot().toPath();
    Files.writeString(dir.resolve("opencgmes.jsonc"), "{ \"cimvocabcheck\": {} }");

    NotebookConfigLoader.Located located = NotebookConfigLoader.forDirectory(dir);

    assertEquals(dir.resolve("opencgmes.jsonc"), located.configPath());
    assertTrue(located.config().connections().isEmpty());
    assertNull(located.config().queryTimeoutSeconds());
    assertNull(located.config().defaultConnection());
  }

  @Test
  public void missingConfigFileYieldsNone() {
    NotebookConfigLoader.Located located =
        NotebookConfigLoader.forDirectory(tmp.getRoot().toPath());

    assertNull(located.configPath());
    assertTrue(located.config().connections().isEmpty());
  }

  @Test
  public void unparseableFileIsForgivinglyTreatedAsEmpty() throws IOException {
    Path dir = tmp.getRoot().toPath();
    Files.writeString(dir.resolve("opencgmes.jsonc"), "{ this is not json !!");

    NotebookConfigLoader.Located located = NotebookConfigLoader.forDirectory(dir);

    assertTrue(located.config().connections().isEmpty());
  }

  /**
   * The parse is cached (it sits on the completion path), so an edit must still be seen. The mtime
   * is set explicitly: two writes within the filesystem's timestamp granularity would otherwise be
   * indistinguishable, which is a test-timing artefact rather than the behaviour under test.
   */
  @Test
  public void anEditedConfigIsReParsedRatherThanServedFromTheCache() throws IOException {
    Path dir = tmp.getRoot().toPath();
    Path configFile = dir.resolve("opencgmes.jsonc");
    Files.writeString(configFile, CONFIG_JSONC);

    assertEquals(2, NotebookConfigLoader.forDirectory(dir).config().connections().size());

    Files.writeString(
        configFile,
        """
        {
          "cimnotebook": {
            "connections": [{ "name": "only-one", "url": "http://localhost:3030/ds/query" }]
          }
        }
        """);
    Files.setLastModifiedTime(configFile, FileTime.fromMillis(System.currentTimeMillis() + 1_000));

    NotebookConfig reloaded = NotebookConfigLoader.forDirectory(dir).config();
    assertEquals(1, reloaded.connections().size());
    assertEquals("only-one", reloaded.connections().get(0).name());
  }

  @Test
  public void notebookUriResolvesViaTheNotebookDirectory() throws IOException {
    Path dir = tmp.getRoot().toPath();
    Files.writeString(dir.resolve("opencgmes.jsonc"), CONFIG_JSONC);
    String notebookUri = dir.resolve("demo.cimnb.md").toUri().toString();

    assertEquals(2, NotebookConfigLoader.forNotebook(notebookUri).config().connections().size());
    assertTrue(NotebookConfigLoader.forNotebook(null).config().connections().isEmpty());
    assertTrue(
        NotebookConfigLoader.forNotebook("untitled:Untitled-1").config().connections().isEmpty());
  }
}
