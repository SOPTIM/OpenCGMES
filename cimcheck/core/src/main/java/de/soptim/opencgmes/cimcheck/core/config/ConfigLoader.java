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

package de.soptim.opencgmes.cimcheck.core.config;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates and parses the project config file {@code opencgmes.json}, shared by the CLI and LSP
 * modules so the discovery rules and parsing behaviour cannot drift apart.
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
 * <p>Auto-discovery walks the directory tree upward from a start directory looking for
 * {@code opencgmes.json}; an explicit path can also be provided. The file is optional — when none is
 * found (or it declares no {@code schemas}/{@code schemasDirectory}), validation is syntax-only;
 * there is no bundled default schema. Java-style comments and trailing commas are tolerated.</p>
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
     * Loads the {@code cimcheck} section from an explicit {@code opencgmes.json} path. A missing
     * section yields an empty config (no schemas → syntax-only validation).
     *
     * @throws ConfigException if the file cannot be read or parsed
     */
    public static CimcheckConfig load(Path configFile) throws ConfigException {
        try {
            JsonNode root = MAPPER.readTree(configFile.toFile());
            JsonNode section = root == null ? null : root.get(SECTION);
            if (section == null || section.isNull()) {
                return CimcheckConfig.empty();
            }
            return MAPPER.treeToValue(section, CimcheckConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file " + configFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Walks upward from {@code startDir} looking for {@code opencgmes.json}.
     *
     * @return the parsed {@code cimcheck} section, or empty if no file was found in the hierarchy
     * @throws ConfigException if a config file is found but cannot be parsed
     */
    public static Optional<CimcheckConfig> discover(Path startDir) throws ConfigException {
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

    /** Thrown when the config file cannot be loaded or parsed. */
    public static final class ConfigException extends Exception {
        public ConfigException(String message, Throwable cause) { super(message, cause); }
        public ConfigException(String message)                   { super(message); }
    }
}
