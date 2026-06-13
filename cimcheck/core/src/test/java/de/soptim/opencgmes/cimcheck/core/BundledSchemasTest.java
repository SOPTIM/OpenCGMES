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
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the CGMES 3.0 profiles bundled in the jar extract to the cache directory and load as a
 * usable schema index with zero configuration.
 */
public class BundledSchemasTest {

    @Test
    public void extractsAllManifestFiles() throws IOException {
        Path dir = BundledSchemas.extractedDir();
        assertTrue("cache dir should exist", Files.isDirectory(dir));
        for (String name : BundledSchemas.profileFileNames()) {
            assertTrue("missing extracted file: " + name, Files.isRegularFile(dir.resolve(name)));
        }
        // Idempotent: a second call returns the same directory without error.
        assertTrue(Files.isSameFile(dir, BundledSchemas.extractedDir()));
    }

    @Test
    public void bundledDefaultLoadsCimProfiles() throws Exception {
        RdfsSchemaIndex index = CgmesSchemaLoader.bundledDefault().loadIndex();
        assertNotNull(index);
        // The CGMES 3.0 Equipment profile declares cim:ACLineSegment in the CIM100 namespace.
        assertTrue("expected CGMES 3.0 classes to be present",
                index.allClasses().contains(org.apache.jena.graph.NodeFactory.createURI(
                        "http://iec.ch/TC57/CIM100#ACLineSegment")));
    }
}
