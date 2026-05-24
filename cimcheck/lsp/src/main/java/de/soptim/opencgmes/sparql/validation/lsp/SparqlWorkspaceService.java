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

package de.soptim.opencgmes.sparql.validation.lsp;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles workspace-level events.
 *
 * <p>Both configuration changes and watched-file changes (the {@code .cgmes/validation.json}
 * file registered during {@code initialized}) trigger a schema reload. Revalidation of all
 * open documents is driven by the {@code onLoaded} callback registered in
 * {@link SparqlLanguageServer}.</p>
 */
final class SparqlWorkspaceService implements WorkspaceService {

    private static final Logger LOG = LoggerFactory.getLogger(SparqlWorkspaceService.class);

    private final SchemaManager schemaManager;

    SparqlWorkspaceService(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        LOG.info("Configuration changed — reloading schema");
        schemaManager.reloadAsync();
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        boolean configChanged = params.getChanges().stream()
                .anyMatch(e -> e.getUri().contains(".cgmes/validation.json"));
        if (configChanged) {
            LOG.info(".cgmes/validation.json changed — reloading schema");
            schemaManager.reloadAsync();
        }
    }
}
