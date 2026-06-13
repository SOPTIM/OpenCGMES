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

/**
 * The scaffold written by {@code cimcheck init} (and the editor "Create config" actions): a
 * commented {@code opencgmes.json} that documents every CIMcheck setting. All settings are
 * pre-commented because CIMcheck works with zero configuration against the bundled CGMES 3.0
 * profiles; the file only needs uncommenting to customise behaviour.
 *
 * <p>The template relies on JSON-with-comments, which the config loaders accept.</p>
 */
public final class ConfigTemplate {

    private ConfigTemplate() {}

    /** The file name CIMcheck discovers, walking up from a document toward the filesystem root. */
    public static final String FILE_NAME = "opencgmes.json";

    private static final String TEMPLATE = """
            {
              // Configuration for OpenCGMES tools. CIMcheck reads the "cimcheck" section.
              "cimcheck": {
                // By default CIMcheck validates against the CGMES 3.0 profiles bundled with the
                // extension, so this file is optional. Uncomment a setting below to customise.

                // --- Schemas -----------------------------------------------------------------
                // Use your own RDFS/OWL profiles instead of the bundled CGMES 3.0 ones. Paths are
                // relative to this file. Pick ONE of the following:
                // "schemasDirectory": "schemas",
                // "schemas": ["schemas/MyEquipment.rdf", "schemas/MyTopology.rdf"],

                // --- Strictness --------------------------------------------------------------
                // "permissive" | "default" | "strict" | "pedantic"
                "strictness": "default"

                // --- Named graphs ------------------------------------------------------------
                // Restrict each instance named graph to the profile(s) that constrain it.
                // "namedGraphs": {
                //   "urn:uuid:eq-network": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"]
                // },

                // --- Prefixes ----------------------------------------------------------------
                // Override the built-in SPARQL prefix map. Omit to keep the defaults.
                // "prefixes": { "cim": "http://iec.ch/TC57/CIM100#" },

                // --- Standard vocabulary check ----------------------------------------------
                // "check" (default) flags typos in rdf/rdfs/owl/sh terms; "ignore" disables it.
                // "standardVocabulary": "check"
              }
            }
            """;

    /** Returns the commented {@code opencgmes.json} scaffold. */
    public static String defaultJson() {
        return TEMPLATE;
    }
}
