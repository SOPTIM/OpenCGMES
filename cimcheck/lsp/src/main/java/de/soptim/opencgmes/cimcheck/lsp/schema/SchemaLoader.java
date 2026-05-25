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

package de.soptim.opencgmes.cimcheck.lsp.schema;

import de.soptim.opencgmes.cimcheck.core.CgmesSchemaLoader;
import de.soptim.opencgmes.cimcheck.core.CgmesSchemaLoader.LoadedIndex;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.lsp.config.LspConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a {@link RdfsSchemaIndex} from an {@link LspConfig}, delegating to
 * {@link CgmesSchemaLoader} for file discovery and parsing.
 */
public final class SchemaLoader {

    private SchemaLoader() {}

    /**
     * Carries the loaded index together with the {@link VersionIri} → source-file mapping
     * needed for go-to-definition and workspace symbol navigation.
     *
     * <p>{@link #skippedFiles()} is non-empty when one or more schema files could not be parsed;
     * callers should surface these as user-visible warnings.</p>
     */
    public record SchemaAndSources(RdfsSchemaIndex index, Map<VersionIri, Path> sourcePaths,
                                   List<String> skippedFiles) {}

    /**
     * Loads an index from the given LSP config. Paths are resolved relative to {@code configBase}.
     *
     * @throws SchemaLoadException if no schema files are found, any file fails to parse, or
     *                             no CIM profiles are registered
     */
    public static RdfsSchemaIndex load(LspConfig config, Path configBase) throws SchemaLoadException {
        return loadWithSources(config, configBase).index();
    }

    /**
     * Loads both the index and the source-file map from the given LSP config.
     *
     * <p>Files that cannot be parsed are recorded in {@link SchemaAndSources#skippedFiles()}
     * rather than causing a hard failure; the load only fails if no valid CIM profile loads.</p>
     *
     * @throws SchemaLoadException if no schema files are found or no CIM profiles could be registered
     */
    public static SchemaAndSources loadWithSources(LspConfig config, Path configBase)
            throws SchemaLoadException {
        try {
            LoadedIndex loaded = resolveLoader(config, configBase).loadIndexWithSources();
            return new SchemaAndSources(loaded.index(), loaded.sourcePaths(), loaded.skippedFiles());
        } catch (CgmesSchemaLoader.SchemaLoadException e) {
            throw new SchemaLoadException(e.getMessage(), e);
        }
    }

    // ---- Private ---------------------------------------------------------------------------

    private static CgmesSchemaLoader resolveLoader(LspConfig config, Path base)
            throws SchemaLoadException {
        if (!config.schemas().isEmpty()) {
            List<Path> files = config.schemas().stream()
                    .map(s -> base.resolve(s).normalize())
                    .collect(Collectors.toList());
            return CgmesSchemaLoader.fromFiles(files);
        }
        if (config.schemasDirectory() != null) {
            Path dir = base.resolve(config.schemasDirectory()).normalize();
            return CgmesSchemaLoader.fromDirectory(dir);
        }
        throw new SchemaLoadException("Config must specify 'schemasDirectory' or 'schemas'.");
    }

    // ---- Exception -------------------------------------------------------------------------

    /** Thrown when schema loading fails. */
    public static final class SchemaLoadException extends Exception {
        public SchemaLoadException(String message)                   { super(message); }
        public SchemaLoadException(String message, Throwable cause)  { super(message, cause); }
    }
}
