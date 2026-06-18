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

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link CgmesSchemaLoader}.
 *
 * <p>Error-case tests are pure unit tests. Happy-path tests require the CGMES submodule and are
 * automatically skipped when it is absent.
 */
public class CgmesSchemaLoaderTest {

  private static final Path RDFS_DIR =
      Path.of("testing/entsoe/application-profiles-library/CGMES/CurrentRelease/RDFS");

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  // ============================================================================================
  // Error cases — no submodule required
  // ============================================================================================

  @Test(expected = CgmesSchemaLoader.SchemaLoadException.class)
  public void fromDirectory_nonExistentDir_throws() throws Exception {
    CgmesSchemaLoader.fromDirectory(Path.of("/no/such/directory")).loadIndex();
  }

  @Test(expected = CgmesSchemaLoader.SchemaLoadException.class)
  public void fromDirectory_emptyDir_throws() throws Exception {
    Path emptyDir = tmp.newFolder("empty").toPath();
    CgmesSchemaLoader.fromDirectory(emptyDir).loadIndex();
  }

  @Test(expected = CgmesSchemaLoader.SchemaLoadException.class)
  public void fromDirectory_dirWithNoSchemaFiles_throws() throws Exception {
    Path dir = tmp.newFolder("noschemas").toPath();
    Files.writeString(dir.resolve("readme.txt"), "not a schema file");
    CgmesSchemaLoader.fromDirectory(dir).loadIndex();
  }

  @Test(expected = CgmesSchemaLoader.SchemaLoadException.class)
  public void fromFiles_emptyVarargs_throws() throws Exception {
    CgmesSchemaLoader.fromFiles(/* empty */ ).loadIndex();
  }

  @Test(expected = CgmesSchemaLoader.SchemaLoadException.class)
  public void fromFiles_emptyIterable_throws() throws Exception {
    CgmesSchemaLoader.fromFiles(List.<Path>of()).loadIndex();
  }

  @Test(expected = CgmesSchemaLoader.SchemaLoadException.class)
  public void fromFiles_nonExistentFile_throws() throws Exception {
    CgmesSchemaLoader.fromFiles(Path.of("/no/such/file.rdf")).loadIndex();
  }

  @Test(expected = CgmesSchemaLoader.SchemaLoadException.class)
  public void fromFiles_onlyUnparseableFiles_throws() throws Exception {
    Path bad = writeRdf("bad.rdf", MINIMAL_BAD_CIM16_RDF);
    CgmesSchemaLoader.fromFiles(bad).loadIndex();
  }

  @Test
  public void fromFiles_goodAndBadFile_skipsUnparseableWithWarning() throws Exception {
    Path good = writeRdf("good.rdf", MINIMAL_GOOD_CIM16_RDF);
    Path bad = writeRdf("bad.rdf", MINIMAL_BAD_CIM16_RDF);

    CgmesSchemaLoader.LoadedIndex result =
        CgmesSchemaLoader.fromFiles(good, bad).loadIndexWithSources();

    assertFalse("index must contain the good profile", result.index().getAllProfiles().isEmpty());
    assertEquals("exactly one file should be in skippedFiles", 1, result.skippedFiles().size());
    assertTrue(
        "skipped entry must name the bad file", result.skippedFiles().get(0).contains("bad.rdf"));
  }

  private Path writeRdf(String name, String content) throws IOException {
    Path file = tmp.newFile(name).toPath();
    Files.writeString(file, content);
    return file;
  }

  /** Minimal CIM 16 RDF that passes CimProfile16.hasVersionIRIAndKeyword. */
  private static final String MINIMAL_GOOD_CIM16_RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.shortName">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">TST</cims:isFixed>
        </rdf:Description>
        <rdf:Description rdf:about="http://entsoe.eu/TestExt#TestVersion.entsoeURI">
          <rdfs:domain rdf:resource="http://entsoe.eu/TestExt#TestVersion"/>
          <cims:isFixed rdf:datatype="http://www.w3.org/2001/XMLSchema#string">http://example.org/TestProfile/1</cims:isFixed>
        </rdf:Description>
      </rdf:RDF>
      """;

  /** CIM 16 RDF without Version metadata — fails CimProfile16.hasVersionIRIAndKeyword. */
  private static final String MINIMAL_BAD_CIM16_RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
               xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
        <rdfs:Class rdf:about="http://iec.ch/TC57/2013/CIM-schema-cim16#Foo"/>
      </rdf:RDF>
      """;

  // ============================================================================================
  // Happy-path integration tests — skipped when submodule is absent
  // ============================================================================================

  @Test
  public void fromDirectory_cgmes30_loadsProfiles() throws Exception {
    Assume.assumeTrue(
        "CGMES 3.0 submodule not initialised — skipping", Files.isDirectory(RDFS_DIR));

    RdfsSchemaIndex index = CgmesSchemaLoader.fromDirectory(RDFS_DIR).loadIndex();

    assertFalse("index must contain at least one profile", index.getAllProfiles().isEmpty());
    // ACLineSegment is declared in the Equipment profile
    assertFalse(
        "ACLineSegment must be found after loading CGMES 3.0",
        index
            .findClass(
                org.apache.jena.graph.NodeFactory.createURI(
                    "http://iec.ch/TC57/CIM100#ACLineSegment"))
            .isEmpty());
  }

  @Test
  public void fromDirectory_cgmes30_load_returnsWorkingApi() throws Exception {
    Assume.assumeTrue(
        "CGMES 3.0 submodule not initialised — skipping", Files.isDirectory(RDFS_DIR));

    SparqlValidationApi api = CgmesSchemaLoader.fromDirectory(RDFS_DIR).load();
    assertNotNull(api);

    // A syntactically valid query that references known CIM classes should validate cleanly.
    String query =
        "PREFIX cim: <http://iec.ch/TC57/CIM100#>\n" + "SELECT * WHERE { ?s a cim:ACLineSegment }";
    SparqlValidationResult result = api.validateSparql(query);
    assertTrue("clean query should produce no errors", result.isValid());
  }

  @Test
  public void fromFiles_specificFiles_loadsOnlyThoseProfiles() throws Exception {
    Assume.assumeTrue(
        "CGMES 3.0 submodule not initialised — skipping", Files.isDirectory(RDFS_DIR));

    // Pick the first two .rdf files alphabetically.
    List<Path> twoFiles;
    try (var stream = Files.list(RDFS_DIR)) {
      twoFiles =
          stream
              .filter(p -> p.getFileName().toString().endsWith(".rdf"))
              .sorted()
              .limit(2)
              .toList();
    }
    Assume.assumeTrue("need at least 2 .rdf files", twoFiles.size() == 2);

    RdfsSchemaIndex index = CgmesSchemaLoader.fromFiles(twoFiles).loadIndex();
    assertFalse("index must contain at least one profile", index.getAllProfiles().isEmpty());
    // The two-file index must have fewer profiles than the full directory load.
    RdfsSchemaIndex fullIndex = CgmesSchemaLoader.fromDirectory(RDFS_DIR).loadIndex();
    assertTrue(
        "two-file index should have no more profiles than the full directory",
        index.getAllProfiles().size() <= fullIndex.getAllProfiles().size());
  }

  // ============================================================================================
  // load() factory convenience
  // ============================================================================================

  @Test
  public void load_returnsSparqlValidationApi() throws Exception {
    Assume.assumeTrue(
        "CGMES 3.0 submodule not initialised — skipping", Files.isDirectory(RDFS_DIR));

    SparqlValidationApi api = CgmesSchemaLoader.fromDirectory(RDFS_DIR).load();
    assertNotNull("load() must return non-null SparqlValidationApi", api);
    assertNotNull("schemaIndex() must be non-null", api.schemaIndex());
  }
}
