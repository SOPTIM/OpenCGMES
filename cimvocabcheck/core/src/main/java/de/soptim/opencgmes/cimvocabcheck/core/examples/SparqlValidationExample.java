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

package de.soptim.opencgmes.cimvocabcheck.core.examples;

import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationResult;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Runnable example: validate a SPARQL query against all CGMES 3.0 RDFS profiles from the ENTSO-E
 * <em>Application Profiles Library</em> submodule, then print the result.
 *
 * <h2>Quick start</h2>
 *
 * <pre>{@code
 * # Once: initialise the submodule
 * git submodule update --init
 *
 * # Build and run with all defaults (CGMES 3.0 profiles + built-in example query)
 * mvn -q install -DskipTests
 * mvn -q -pl cimvocabcheck/core exec:java
 *
 * # Custom RDFS folder and/or query file
 * mvn -q -pl cimvocabcheck/core exec:java \
 *     -Dexec.args="path/to/rdfs-folder path/to/query.rq"
 * }</pre>
 *
 * <h2>Arguments (both optional)</h2>
 *
 * <ol>
 *   <li>{@code rdfs-dir} — directory that contains {@code *.rdf} RDFS profile files. Defaults to
 *       the submodule at {@code
 *       testing/entsoe/application-profiles-library/CGMES/CurrentRelease/RDFS}.
 *   <li>{@code query-file} — path to a {@code *.rq} SPARQL file. Defaults to the built-in {@code
 *       examples/example-query.rq} classpath resource.
 * </ol>
 */
public final class SparqlValidationExample {

  private static final Path DEFAULT_RDFS_DIR =
      Path.of(
          "testing", "entsoe", "application-profiles-library", "CGMES", "CurrentRelease", "RDFS");

  private static final String DEFAULT_QUERY_RESOURCE = "examples/example-query.rq";

  private SparqlValidationExample() {}

  /** Runs the example: loads schemas, validates a query, and prints a report. */
  public static void main(String[] args) throws Exception {
    Path rdfsDir = args.length > 0 ? Path.of(args[0]) : DEFAULT_RDFS_DIR;
    Path queryFile = args.length > 1 ? Path.of(args[1]) : null;

    // 1. Discover .rdf files in rdfsDir.
    List<Path> rdfFiles = findRdfFiles(rdfsDir);
    if (rdfFiles.isEmpty()) {
      System.err.println("No .rdf files found in: " + rdfsDir.toAbsolutePath());
      System.err.println("Did you run: git submodule update --init ?");
      System.exit(1);
    }

    // 2. Parse every profile and build the schema index.
    CimProfileRegistry registry = new CimProfileRegistryStd();
    var parseErrors = new ArrayList<String>();
    for (Path f : rdfFiles) {
      try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
        CimProfile profile = new RdfXmlParser().parseCimProfile(r);
        registry.register(profile);
      } catch (Exception e) {
        parseErrors.add(f.getFileName() + ": " + e.getMessage());
      }
    }

    // 3. Load the query — from file or classpath.
    String queryText;
    String queryLabel;
    if (queryFile != null) {
      queryText = Files.readString(queryFile, StandardCharsets.UTF_8);
      queryLabel = queryFile.toString();
    } else {
      queryText = resourceText(DEFAULT_QUERY_RESOURCE);
      queryLabel = DEFAULT_QUERY_RESOURCE + " (classpath)";
    }

    // Print a report.
    System.out.println("=================================================================");
    System.out.println(" OpenCGMES — static SPARQL query validation");
    System.out.println("=================================================================");
    System.out.println();

    System.out.println("--- schema profiles loaded from: " + rdfsDir.toAbsolutePath());
    RdfsSchemaIndex index = RdfsSchemaIndex.fromCimRegistry(registry);
    List<VersionIri> allProfiles = index.getAllProfiles();
    for (VersionIri v : allProfiles) {
      var schema = index.profiles().get(v);
      System.out.printf(
          "  %-55s  %3d cls  %3d prop%n",
          shortIri(v.iri()), schema.classes().size(), schema.properties().size());
    }
    if (!parseErrors.isEmpty()) {
      System.out.println();
      System.out.println("  [WARN] parse errors (files skipped):");
      parseErrors.forEach(e -> System.out.println("    " + e));
    }

    System.out.println();
    System.out.println("--- query: " + queryLabel);
    printNumbered(queryText);

    System.out.println();

    // Validate the query against all loaded profiles, then print the result.
    SparqlValidationApi api = new SparqlValidationApi(index);
    SparqlValidationResult result = api.validateSparql(queryText);
    System.out.println("--- validation result");
    System.out.println("  valid: " + result.isValid());
    System.out.println("  annotations: " + result.annotations().size());

    if (result.annotations().isEmpty()) {
      System.out.println("  (no problems found)");
    } else {
      System.out.println();
      for (SparqlValidationAnnotation a : result.annotations()) {
        String where =
            (a.line() != null)
                ? "line " + a.line() + ", col " + a.column()
                : "(no source location)";
        System.out.println("  [" + a.severity() + "] " + a.code() + "  —  " + where);
        System.out.println("    " + a.message());
        Collection<VersionIri> selected = a.selectedProfiles();
        if (!selected.isEmpty()) {
          System.out.println(
              "    scope: " + selected.stream().map(v -> shortIri(v.iri())).toList());
        }
        Collection<VersionIri> found = a.foundInOtherProfiles();
        if (!found.isEmpty()) {
          System.out.println(
              "    exists in: " + found.stream().map(v -> shortIri(v.iri())).toList());
        }
        System.out.println();
      }
    }
  }

  private static List<Path> findRdfFiles(Path dir) throws Exception {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    var out = new ArrayList<Path>();
    try (var stream = Files.list(dir)) {
      stream.filter(p -> p.toString().endsWith(".rdf")).sorted().forEach(out::add);
    }
    return out;
  }

  private static String resourceText(String name) throws Exception {
    try (InputStream in =
        SparqlValidationExample.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void printNumbered(String text) {
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      System.out.printf("  %2d | %s%n", i + 1, lines[i]);
    }
  }

  // Returns the last two path/fragment segments: "CoreEquipment-EU/3.0" for a version IRI.
  private static String shortIri(String iri) {
    int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
    if (last < 0) {
      return iri;
    }
    int prev = Math.max(iri.lastIndexOf('/', last - 1), iri.lastIndexOf('#', last - 1));
    return prev >= 0 ? iri.substring(prev + 1) : iri.substring(last + 1);
  }
}
