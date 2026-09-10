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

package de.soptim.opencgmes.cimxml.rdfs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.jena.graph.Node;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests that verify all RDF Schema files from the ENTSO-E application-profiles-library can be
 * loaded and registered by the CimXmlParser.
 *
 * <p>The application-profiles-library is included as a Git submodule at
 * {@code cimxml/testing/application-profiles-library} and is licensed under Apache License 2.0.
 * See <a href="https://github.com/entsoe/application-profiles-library">
 * https://github.com/entsoe/application-profiles-library</a></p>
 *
 * <p>Each test case corresponds to one directory containing {@code .rdf} files. A fresh
 * {@link CimXmlParser} instance is created per directory, and all {@code .rdf} files in that
 * directory are loaded and registered as CIM profiles.</p>
 */
@RunWith(Parameterized.class)
public class TestApplicationProfilesLibrary {

  private static final Logger LOG =
      LoggerFactory.getLogger(TestApplicationProfilesLibrary.class);

  private static final Path APL_ROOT = Paths.get("testing", "application-profiles-library");

  private static final List<Path> PROFILES_FOLDERS = List.of(
      APL_ROOT.resolve("cgmes2.4/CGMES/Original/RDFS"),
      APL_ROOT.resolve("cgmes3.0/CGMES/RDFS"),
      APL_ROOT.resolve("v1.1.1/CGMES/CurrentRelease/RDFS"),
      APL_ROOT.resolve("v1.1.1/NCP/CurrentRelease/RDFS")
  );


  private final String directoryName;
  private final Path directoryPath;

  public TestApplicationProfilesLibrary(String directoryName, Path directoryPath) {
    this.directoryName = directoryName;
    this.directoryPath = directoryPath;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> discoverProfileDirectories() throws IOException {
    List<Object[]> testCases = new ArrayList<>();

    for(var folder: PROFILES_FOLDERS) {
      if (!Files.exists(folder) || !Files.isDirectory(folder)) {
        testCases.add(new Object[]{
            "application-profiles-library folder not found " + folder, null
        });
      }

      List<Object[]> testCasesInFolder = new ArrayList<>();

      try (Stream<Path> paths = Files.list(folder)) {
        List<Path> rdfFiles = paths
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".rdf"))
            .sorted()
            .collect(Collectors.toList());

        rdfFiles.stream()
            .map(Path::getParent)
            .distinct()
            .sorted()
            .forEach(dir -> {
              String displayName = folder.toString();
              testCases.add(new Object[]{displayName, dir});
            });

        if (testCasesInFolder.isEmpty()) {
          testCases.add(new Object[]{"No .rdf files found in " + folder, null});
        }
        testCases.addAll(testCasesInFolder);
      }
    }

    return testCases;
  }

  private static List<Path> listRdfFiles(Path directory) throws IOException {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".rdf"))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  /**
   * Loads all .rdf files in the directory into the given parser. Some directories contain
   * profiles with overlapping version IRIs (e.g. CGMES 2.4 "Augmented" equipment profiles)
   * or multiple header profiles for the same CIM namespace. These duplicate registrations are
   * logged but not treated as failures, since the files themselves parse correctly.
   */
  private static CimXmlParser loadProfiles(Path directory) throws IOException {
    CimXmlParser parser = new CimXmlParser();
    for (Path rdfFile : listRdfFiles(directory)) {
      try {
        parser.parseAndRegisterCimProfile(rdfFile);
      } catch (IllegalArgumentException e) {
        if (e.getMessage() != null && (e.getMessage().contains("already registered"))) {
          LOG.info("Skipping duplicate profile {}: {}", rdfFile.getFileName(), e.getMessage());
        } else {
          throw e;
        }
      }
    }
    return parser;
  }

  @Test
  public void loadAllRdfSchemaFilesInDirectory() throws IOException {
    assumeTrue("Submodule not available, skipping test: " + directoryName,
        directoryPath != null);

    List<Path> rdfFiles = listRdfFiles(directoryPath);
    assertFalse("No .rdf files found in " + directoryName, rdfFiles.isEmpty());

    CimXmlParser parser = loadProfiles(directoryPath);

    assertFalse("No profiles were registered from " + directoryName,
        parser.getCimProfileRegistry().getRegisteredProfiles().isEmpty());
  }

  @Test
  public void allProfilesHaveNonEmptyGraph() throws IOException {
    assumeTrue("Submodule not available, skipping test: " + directoryName,
        directoryPath != null);

    CimXmlParser parser = loadProfiles(directoryPath);

    for (CimProfile profile : parser.getCimProfileRegistry().getRegisteredProfiles()) {
      assertTrue("Profile graph should not be empty: " + profile.getDcatKeyword(),
          profile.size() > 0);
      assertNotNull("Profile should have a CIM namespace",
          profile.getCimNamespace());
    }
  }

  @Test
  public void allNonHeaderProfilesHaveVersionIris() throws IOException {
    assumeTrue("Submodule not available, skipping test: " + directoryName,
        directoryPath != null);

    CimXmlParser parser = loadProfiles(directoryPath);

    for (CimProfile profile : parser.getCimProfileRegistry().getRegisteredProfiles()) {
      if (!profile.isHeaderProfile()) {
        assertNotNull("Non-header profile should have version IRIs: "
            + profile.getDcatKeyword(), profile.getOwlVersionIris());
        assertFalse("Non-header profile should have at least one version IRI: "
            + profile.getDcatKeyword(), profile.getOwlVersionIris().isEmpty());
      }
    }
  }

  @Test
  public void allNonHeaderProfilesHaveKeyword() throws IOException {
    assumeTrue("Submodule not available, skipping test: " + directoryName,
        directoryPath != null);

    CimXmlParser parser = loadProfiles(directoryPath);

    for (CimProfile profile : parser.getCimProfileRegistry().getRegisteredProfiles()) {
      if (!profile.isHeaderProfile()) {
        assertNotNull("Non-header profile should have a dcat:keyword: "
                + profile.getOwlVersionIris(),
            profile.getDcatKeyword());
        assertFalse("Non-header profile keyword should not be empty: "
                + profile.getOwlVersionIris(),
            profile.getDcatKeyword().isEmpty());
      }
    }
  }

  @Test
  public void datatypeCoverageNonEmptyPropertyMaps() throws IOException {
    assumeTrue("Submodule not available, skipping test: " + directoryName,
        directoryPath != null);

    CimXmlParser parser = loadProfiles(directoryPath);
    CimProfileRegistry registry = parser.getCimProfileRegistry();

    for (CimProfile profile : registry.getRegisteredProfiles()) {
      if (profile.isHeaderProfile()) {
        var headerProps = registry.getHeaderPropertiesAndDatatypes(
            profile.getCimNamespace());
        assertNotNull("Header property map should not be null for "
            + profile.getCimNamespace(), headerProps);
        assertFalse("Header property map should not be empty for "
            + profile.getCimNamespace(), headerProps.isEmpty());
      } else {
        Set<Node> versionIris = profile.getOwlVersionIris();
        var props = registry.getPropertiesAndDatatypes(versionIris);
        assertNotNull("Property map should not be null for "
            + profile.getDcatKeyword(), props);
        assertFalse("Property map should not be empty for "
            + profile.getDcatKeyword(), props.isEmpty());
      }
    }
  }
}
