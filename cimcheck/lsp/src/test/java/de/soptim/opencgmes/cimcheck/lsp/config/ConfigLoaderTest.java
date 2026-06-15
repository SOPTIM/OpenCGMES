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

package de.soptim.opencgmes.cimcheck.lsp.config;

import de.soptim.opencgmes.cimcheck.core.ConfigTemplate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies {@code opencgmes.json} discovery, the {@code cimcheck} section, and comment tolerance. */
public class ConfigLoaderTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void generatedTemplateParsesWithNoSchemasConfigured() throws Exception {
        Path file = write(tmp.getRoot().toPath(), ConfigTemplate.defaultJson());
        LspConfig cfg = ConfigLoader.load(file);
        // The scaffold leaves schemas commented out -> empty -> syntax-only (no bundled default).
        assertTrue("schemas should be empty", cfg.schemas().isEmpty());
        assertNull("schemasDirectory should be unset", cfg.schemasDirectory());
        assertEquals("default", cfg.strictness());
    }

    @Test
    public void extractsCimcheckSectionAndToleratesComments() throws Exception {
        String json = """
                {
                  // a leading comment
                  "cimcheck": {
                    "strictness": "strict",
                    "namedGraphs": { "urn:uuid:eq": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"] },
                  }
                }
                """;
        Path file = write(tmp.getRoot().toPath(), json);
        LspConfig cfg = ConfigLoader.load(file);
        assertEquals("strict", cfg.strictness());
        assertTrue(cfg.namedGraphs().containsKey("urn:uuid:eq"));
    }

    @Test
    public void missingSectionYieldsEmptyConfig() throws Exception {
        Path file = write(tmp.getRoot().toPath(), "{ \"otherTool\": { \"x\": 1 } }");
        LspConfig cfg = ConfigLoader.load(file);
        assertTrue(cfg.schemas().isEmpty());
        assertNull(cfg.schemasDirectory());
    }

    @Test
    public void discoverWalksUpToNearestConfig() throws Exception {
        Path root = tmp.getRoot().toPath();
        write(root, "{ \"cimcheck\": { \"strictness\": \"pedantic\" } }");
        Path deep = Files.createDirectories(root.resolve("a/b/c"));
        Optional<Path> found = ConfigLoader.discoverFile(deep);
        assertTrue(found.isPresent());
        assertEquals(root.resolve("opencgmes.json").toRealPath(), found.get().toRealPath());
    }

    private static Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("opencgmes.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
