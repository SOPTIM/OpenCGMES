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

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates and parses the project config file {@code opencgmes.json}.
 *
 * <p>All CIMcheck settings live under a top-level {@code "cimcheck"} object so {@code opencgmes.json}
 * can host configuration for other OpenCGMES tools alongside it:</p>
 * <pre>{@code
 * {
 *   "cimcheck": {
 *     "strictness": "strict",
 *     "namedGraphs": { "urn:uuid:eq": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"] }
 *   }
 * }
 * }</pre>
 *
 * <p>The file is optional: when none is found (or it omits {@code schemas}/{@code schemasDirectory}),
 * no schema is loaded and documents are validated syntax-only — there is no bundled default schema —
 * unless a document declares a {@code # [endpoint=...]} that supplies the schema.</p>
 *
 * <p>Auto-discovery walks upward from a start directory; explicit loading takes a direct path.</p>
 */
public final class ConfigLoader {

    /** The config file name, looked for in each directory while walking up the tree. */
    public static final String CONFIG_FILENAME = "opencgmes.json";

    /** Top-level key under which all CIMcheck settings live. */
    private static final String SECTION = "cimcheck";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);

    private ConfigLoader() {}

    /**
     * Loads config from an explicit {@code opencgmes.json} path, returning the {@code cimcheck}
     * section. A missing section yields an empty config (no schemas → syntax-only validation).
     *
     * @throws ConfigException if the file cannot be read or parsed
     */
    public static LspConfig load(Path configFile) throws ConfigException {
        try {
            JsonNode root = MAPPER.readTree(configFile.toFile());
            JsonNode section = root == null ? null : root.get(SECTION);
            if (section == null || section.isNull()) {
                return emptyConfig();
            }
            return MAPPER.treeToValue(section, LspConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config " + configFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Walks upward from {@code startDir} looking for {@code opencgmes.json}.
     *
     * @return the parsed {@code cimcheck} section, or empty if no file is found in the hierarchy
     * @throws ConfigException if a file is found but cannot be parsed
     */
    public static Optional<LspConfig> discover(Path startDir) throws ConfigException {
        Optional<Path> file = discoverFile(startDir);
        return file.isPresent() ? Optional.of(load(file.get())) : Optional.empty();
    }

    /**
     * Walks upward from {@code startDir} returning the path of the nearest {@code opencgmes.json},
     * or empty if none exists anywhere in the hierarchy. The file is not parsed.
     */
    public static Optional<Path> discoverFile(Path startDir) {
        if (startDir == null) return Optional.empty();
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(CONFIG_FILENAME);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    private static LspConfig emptyConfig() {
        return new LspConfig(null, null, null, null, null, null);
    }

    /** Thrown when the config file cannot be loaded or parsed. */
    public static final class ConfigException extends Exception {
        public ConfigException(String message, Throwable cause) { super(message, cause); }
        public ConfigException(String message)                   { super(message); }
    }
}
