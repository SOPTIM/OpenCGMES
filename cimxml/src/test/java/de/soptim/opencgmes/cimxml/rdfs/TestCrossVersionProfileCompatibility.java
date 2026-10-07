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

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests cross-version compatibility by loading CGMES 2.4 (CIM 16), CGMES 3.0 (CIM 17/18)
 * and NCP v1.1.1 (CIM 17/18) application profiles into the same CimProfileRegistry and verifying
 * they coexist.
 *
 * <p>CGMES 2.4 profiles use {@code http://iec.ch/TC57/2013/CIM-schema-cim16#} as the CIM
 * namespace, while CGMES 3.0 profiles use {@code http://iec.ch/TC57/CIM100#}
 * and NCP v1.1.1 profiles use {@code https://cim.ucaiug.io/ns#} as the CIM namespace.
 * </p>
 */
public class TestCrossVersionProfileCompatibility {

  private static final Path PROFILES_ROOT =
      Paths.get("testing", "application-profiles-library");

  private static final Path CGMES_30_RDFS =
      PROFILES_ROOT.resolve("cgmes3.0/CGMES/RDFS");

  private static final Path CGMES_24_RDFS =
      PROFILES_ROOT.resolve("cgmes2.4/CGMES/Original/RDFS");

  private static final Path NCP_v1_1_1_RDFS =
      PROFILES_ROOT.resolve("v1.1.1/NCP/CurrentRelease/RDFS");


  private boolean submoduleAvailable;

  private static List<Path> listRdfFiles(Path directory) throws IOException {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".rdf"))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  @Before
  public void checkSubmodule() {
    submoduleAvailable = Files.exists(PROFILES_ROOT) && Files.isDirectory(PROFILES_ROOT)
        && Files.exists(CGMES_30_RDFS) && Files.exists(CGMES_24_RDFS) && Files.exists(NCP_v1_1_1_RDFS);
  }

  @Test
  public void cgmes24And30CoexistInSameRegistry() throws IOException {
    assumeTrue("Submodule not available", submoduleAvailable);

    CimXmlParser parser = new CimXmlParser();

    // Load CGMES 3.0 profiles first (skip second header to avoid duplicate)
    for (Path rdfFile : listRdfFiles(CGMES_30_RDFS)) {
      if (rdfFile.getFileName().toString().contains("RDFS2019")) {
        continue; // Skip the older RDFS2019 header; RDFS2020 header will be loaded
      }
      parser.parseAndRegisterCimProfile(rdfFile);
    }

    int cgmes30Count = parser.getCimProfileRegistry().getRegisteredProfiles().size();
    assertTrue("Should have registered CGMES 3.0 profiles", cgmes30Count > 0);

    // Load CGMES 2.4 base profiles (skip augmented supersets to avoid duplicate IRIs)
    for (Path rdfFile : listRdfFiles(CGMES_24_RDFS)) {
      String name = rdfFile.getFileName().toString();
      // Skip profiles that list all verionIRIs but contain less, because they then conflict with
      // the more complete profiles that are also in the directory. This is a known issue with some
      // CGMES 2.4 "Augmented" equipment profiles, which list all version IRIs but only contain a
      // subset of the properties. Since these files parse correctly and the registry handles
      // duplicate IRIs by ignoring subsequent registrations, we can skip them in this test to
      // focus on verifying that the unique profiles from both versions coexist without conflicts.
      if (name.endsWith("EquipmentProfileCoreOperationRDFSAugmented-v2_4_15-4Sep2020.rdf")
        || name.endsWith("EquipmentProfileCoreRDFSAugmented-v2_4_15-4Sep2020.rdf")
        || name.endsWith("EquipmentProfileCoreShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf")) {
        continue;
      }
      // print file name for debugging
      System.out.println("Before loading: " + rdfFile.getFileName());
      var profile = parser.parseAndRegisterCimProfile(rdfFile);
      // print concatenated version IRIs for loaded file for debugging
      var versionIris = profile.getOwlVersionIris().stream()
          .map(Object::toString)
          .collect(Collectors.joining(", "));
      System.out.println("Registered version IRIs after loading: " + versionIris);
    }

    Set<CimProfile> allProfiles = parser.getCimProfileRegistry().getRegisteredProfiles();
    int totalCount = allProfiles.size();
    assertTrue("Should have registered more profiles after adding CGMES 2.4",
        totalCount > cgmes30Count);

    // Load NCP v1.1.1 profiles
    for (Path rdfFile : listRdfFiles(NCP_v1_1_1_RDFS)) {
      parser.parseAndRegisterCimProfile(rdfFile);
    }

    allProfiles = parser.getCimProfileRegistry().getRegisteredProfiles();
    totalCount = allProfiles.size();
    assertTrue("Should have registered more profiles after adding NCP v1.1.1",
        totalCount > cgmes30Count);

    // Verify both CIM namespaces are present
    Set<String> namespaces = new HashSet<>();
    for (CimProfile profile : allProfiles) {
      namespaces.add(profile.getCimNamespace());
    }
    assertTrue("Should contain CIM 16 namespace (CGMES 2.4)",
        namespaces.stream().anyMatch(ns -> ns.contains("http://iec.ch/TC57/2013/CIM-schema-cim16#")));
    assertTrue("Should contain CIM 100 namespace (CGMES 3.0)",
        namespaces.stream().anyMatch(ns -> ns.contains("http://iec.ch/TC57/CIM100#")));
    assertTrue("Should contain CIM 100 namespace (CGMES 3.0)",
        namespaces.stream().anyMatch(ns -> ns.contains("https://cim.ucaiug.io/ns#")));
  }
}
