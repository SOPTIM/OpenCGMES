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

package de.soptim.opencgmes.sparql.validation.lsp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates and parses {@code .cgmes/validation.json}.
 *
 * <p>Auto-discovery walks up from a start directory; explicit loading takes a direct path.</p>
 */
public final class ConfigLoader {

    private static final String CONFIG_SUBPATH = ".cgmes/validation.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ConfigLoader() {}

    /**
     * Loads config from an explicit path.
     *
     * @throws ConfigException if the file cannot be read or parsed
     */
    public static LspConfig load(Path configFile) throws ConfigException {
        try {
            return MAPPER.readValue(configFile.toFile(), LspConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config " + configFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Walks upward from {@code startDir} looking for {@code .cgmes/validation.json}.
     *
     * @return the config, or empty if no file is found anywhere in the hierarchy
     * @throws ConfigException if a file is found but cannot be parsed
     */
    public static Optional<LspConfig> discover(Path startDir) throws ConfigException {
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(CONFIG_SUBPATH);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(load(candidate));
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
