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

package de.soptim.opencgmes.cimcheck.core;

import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import org.apache.jena.graph.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Convenience entry point for building a {@link SparqlValidationApi} (or the underlying
 * {@link RdfsSchemaIndex}) from RDFS schema files on disk.
 *
 * <p>Schema files in ENTSO-E RDFS format ({@code .rdf}), Turtle ({@code .ttl}), and OWL
 * ({@code .owl}) are accepted. They are parsed via the {@code cimxml} CIM profile registry,
 * which handles the CIM-specific {@code cims:dataType} / {@code cims:range} annotations that
 * plain RDFS parsers would miss.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * // Load every .rdf/.ttl/.owl file from a directory
 * SparqlValidationApi api = CgmesSchemaLoader.fromDirectory(Path.of(".cgmes/schemas")).load();
 *
 * // Load specific files
 * SparqlValidationApi api = CgmesSchemaLoader.fromFiles(eqPath, tpPath).load();
 *
 * // Obtain just the index (for wiring into custom validators)
 * RdfsSchemaIndex index = CgmesSchemaLoader.fromDirectory(schemasDir).loadIndex();
 * </pre>
 */
public final class CgmesSchemaLoader {

    private static final Logger LOG = LoggerFactory.getLogger(CgmesSchemaLoader.class);

    private final Path       directory; // non-null when constructed via fromDirectory
    private final List<Path> files;     // non-null when constructed via fromFiles

    private CgmesSchemaLoader(Path directory, List<Path> files) {
        this.directory = directory;
        this.files     = files;
    }

    // ---- Factory methods -------------------------------------------------------------------

    /**
     * Loads all {@code .rdf}, {@code .ttl}, and {@code .owl} files found directly inside
     * {@code dir} (non-recursive, sorted alphabetically).
     *
     * @param dir directory to scan; must exist and be a directory
     */
    public static CgmesSchemaLoader fromDirectory(Path dir) {
        return new CgmesSchemaLoader(Objects.requireNonNull(dir, "dir"), null);
    }

    /**
     * Loads specific schema files.
     *
     * @param files one or more schema file paths; must be non-empty
     */
    public static CgmesSchemaLoader fromFiles(Path... files) {
        Objects.requireNonNull(files, "files");
        return new CgmesSchemaLoader(null, List.of(files));
    }

    /**
     * Loads specific schema files.
     *
     * @param files one or more schema file paths; must be non-empty
     */
    public static CgmesSchemaLoader fromFiles(Iterable<Path> files) {
        Objects.requireNonNull(files, "files");
        var list = new ArrayList<Path>();
        files.forEach(list::add);
        return new CgmesSchemaLoader(null, List.copyOf(list));
    }

    // ---- Loading ---------------------------------------------------------------------------

    /**
     * Carries a loaded {@link RdfsSchemaIndex} together with the {@link VersionIri} → source-file
     * mapping collected while parsing. Callers that need file-level navigation (go-to-definition,
     * workspace symbols) use {@link #sourcePaths()} to locate declarations in profile files.
     */
    public record LoadedIndex(RdfsSchemaIndex index, Map<VersionIri, Path> sourcePaths) {}

    /**
     * Parses the configured files and returns the {@link RdfsSchemaIndex}.
     *
     * @throws SchemaLoadException if the directory does not exist, no schema files are found,
     *                             any file fails to parse, or no CIM profiles are registered
     *                             after parsing
     */
    public RdfsSchemaIndex loadIndex() throws SchemaLoadException {
        return buildIndexAndSources(resolveFiles()).index();
    }

    /**
     * Parses the configured files and returns both the index and the {@link VersionIri} → file
     * mapping needed for source navigation.
     *
     * @throws SchemaLoadException see {@link #loadIndex()}
     */
    public LoadedIndex loadIndexWithSources() throws SchemaLoadException {
        return buildIndexAndSources(resolveFiles());
    }

    /**
     * Parses the configured files and returns a fully initialised {@link SparqlValidationApi}.
     *
     * @throws SchemaLoadException see {@link #loadIndex()}
     */
    public SparqlValidationApi load() throws SchemaLoadException {
        return new SparqlValidationApi(loadIndex());
    }

    // ---- Private ---------------------------------------------------------------------------

    private List<Path> resolveFiles() throws SchemaLoadException {
        if (directory != null) {
            if (!Files.isDirectory(directory)) {
                throw new SchemaLoadException(
                        "Directory does not exist or is not a directory: " + directory);
            }
            try (Stream<Path> entries = Files.list(directory)) {
                var found = entries
                        .filter(p -> isSchemaFile(p.getFileName().toString()))
                        .sorted()
                        .collect(Collectors.toList());
                if (found.isEmpty()) {
                    throw new SchemaLoadException(
                            "No .rdf/.ttl/.owl files found in directory: " + directory);
                }
                return found;
            } catch (SchemaLoadException rethrow) {
                throw rethrow;
            } catch (IOException e) {
                throw new SchemaLoadException(
                        "Cannot list directory " + directory + ": " + e.getMessage(), e);
            }
        }
        if (files.isEmpty()) {
            throw new SchemaLoadException("No schema files specified.");
        }
        return files;
    }

    private static boolean isSchemaFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".rdf") || lower.endsWith(".ttl") || lower.endsWith(".owl");
    }

    private static LoadedIndex buildIndexAndSources(List<Path> filePaths) throws SchemaLoadException {
        var registry    = new CimProfileRegistryStd();
        var parser      = new RdfXmlParser();
        var failed      = new ArrayList<String>();
        var sourcePaths = new LinkedHashMap<VersionIri, Path>();

        for (Path f : filePaths) {
            if (!Files.isRegularFile(f)) {
                throw new SchemaLoadException("Schema file does not exist: " + f);
            }
            try {
                CimProfile profile = parser.parseCimProfile(f);
                try {
                    registry.register(profile);
                    if (!profile.isHeaderProfile()) {
                        for (Node iriNode : profile.getOwlVersionIRIs()) {
                            sourcePaths.put(new VersionIri(iriNode), f);
                        }
                    }
                    LOG.debug("Loaded schema: {}", f.getFileName());
                } catch (IllegalArgumentException dup) {
                    // Duplicate version IRI — multiple files declare the same profile.
                    // Skip the duplicate; the first registration wins.
                    LOG.debug("Skipping {} — duplicate version IRI: {}",
                            f.getFileName(), dup.getMessage());
                }
            } catch (Exception e) {
                failed.add(f + " (" + e.getMessage() + ")");
                LOG.warn("Failed to load {}: {}", f, e.getMessage());
            }
        }

        if (!failed.isEmpty()) {
            throw new SchemaLoadException(
                    "Failed to parse schema file(s):\n  " + String.join("\n  ", failed));
        }
        if (registry.getRegisteredProfiles().isEmpty()) {
            throw new SchemaLoadException(
                    "No CIM profiles were registered — check your schema files.");
        }
        return new LoadedIndex(
                RdfsSchemaIndex.fromCimRegistry(registry),
                Collections.unmodifiableMap(sourcePaths));
    }

    // ---- Exception -------------------------------------------------------------------------

    /** Thrown when schema loading fails due to a missing directory, unreadable file, or parse error. */
    public static final class SchemaLoadException extends Exception {
        public SchemaLoadException(String message) {
            super(message);
        }
        public SchemaLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
