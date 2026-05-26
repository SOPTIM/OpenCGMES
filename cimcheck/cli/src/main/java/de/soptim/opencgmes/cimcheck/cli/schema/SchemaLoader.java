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

package de.soptim.opencgmes.cimcheck.cli.schema;

import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import de.soptim.opencgmes.cimcheck.cli.config.CliConfig;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds a {@link RdfsSchemaIndex} from the CLI config or from explicit schema file paths.
 */
public final class SchemaLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaLoader.class);

    private SchemaLoader() {}

    /**
     * Loads an index from the given config (either {@code schemasDirectory} or explicit
     * {@code schemas} list). Paths in the config are resolved relative to {@code configBase}.
     *
     * @throws SchemaLoadException if no schema files are found or any file fails to parse
     */
    public static RdfsSchemaIndex load(CliConfig config, Path configBase) throws SchemaLoadException {
        List<Path> files = resolveFiles(config, configBase);
        return buildIndex(files);
    }

    /**
     * Loads an index from an explicit list of schema file paths.
     *
     * @throws SchemaLoadException if the list is empty or any file fails to parse
     */
    public static RdfsSchemaIndex load(List<Path> schemaFiles) throws SchemaLoadException {
        if (schemaFiles.isEmpty()) {
            throw new SchemaLoadException("No schema files provided.");
        }
        return buildIndex(schemaFiles);
    }

    // ---- private helpers -------------------------------------------------------------------

    private static List<Path> resolveFiles(CliConfig config, Path base) throws SchemaLoadException {
        if (!config.schemas().isEmpty()) {
            return config.schemas().stream()
                    .map(s -> base.resolve(s).normalize())
                    .collect(Collectors.toList());
        }
        if (config.schemasDirectory() != null) {
            Path dir = base.resolve(config.schemasDirectory()).normalize();
            if (!Files.isDirectory(dir)) {
                throw new SchemaLoadException(
                        "schemasDirectory does not exist or is not a directory: " + dir);
            }
            try (Stream<Path> walk = Files.walk(dir, 1, FileVisitOption.FOLLOW_LINKS)) {
                var files = walk
                        .filter(p -> isSchemaFile(p.getFileName().toString()))
                        .sorted()
                        .collect(Collectors.toList());
                if (files.isEmpty()) {
                    throw new SchemaLoadException(
                            "No .rdf / .ttl / .owl files found in schemasDirectory: " + dir);
                }
                return files;
            } catch (IOException e) {
                throw new SchemaLoadException("Cannot list schemasDirectory " + dir + ": " + e.getMessage(), e);
            }
        }
        throw new SchemaLoadException(
                "Config must specify either 'schemasDirectory' or 'schemas'.");
    }

    private static boolean isSchemaFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".rdf") || lower.endsWith(".ttl") || lower.endsWith(".owl");
    }

    private static RdfsSchemaIndex buildIndex(List<Path> files) throws SchemaLoadException {
        var registry = new CimProfileRegistryStd();
        var parser   = new RdfXmlParser();
        var failed   = new ArrayList<String>();

        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                throw new SchemaLoadException("Schema file does not exist: " + f);
            }
            try {
                registry.register(parser.parseCimProfile(f));
                LOG.info("Loaded schema: {}", f.getFileName());
            } catch (Exception e) {
                failed.add(f + " (" + e.getMessage() + ")");
                LOG.warn("Failed to load schema {}: {}", f, e.getMessage());
            }
        }

        if (!failed.isEmpty()) {
            throw new SchemaLoadException("Failed to parse schema file(s):\n  " +
                    String.join("\n  ", failed));
        }
        if (registry.getRegisteredProfiles().isEmpty()) {
            throw new SchemaLoadException("No profiles were loaded — check your schema files.");
        }

        return RdfsSchemaIndex.fromCimRegistry(registry);
    }

    /** Thrown when schema loading fails. */
    public static final class SchemaLoadException extends Exception {
        public SchemaLoadException(String message) { super(message); }
        public SchemaLoadException(String message, Throwable cause) { super(message, cause); }
    }
}
