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

package de.soptim.opencgmes.cimcheck.core.examples;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.core.shacl.EmbeddedSparql;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclEmbeddedQueryResult;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Runnable example: validate a SHACL shapes graph against CGMES 3.0 RDFS profiles and print
 * per-fragment results.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * # Once: initialise the submodule
 * git submodule update --init
 *
 * # Build and run with all defaults (CGMES 3.0 profiles + built-in example shapes)
 * mvn -q install -DskipTests
 * mvn -q -pl sparql-validation exec:java \
 *     -Dexec.mainClass=de.soptim.opencgmes.sparql.validation.examples.ShaclValidationExample
 *
 * # Custom RDFS folder and/or shapes file
 * mvn -q -pl sparql-validation exec:java \
 *     -Dexec.mainClass=de.soptim.opencgmes.sparql.validation.examples.ShaclValidationExample \
 *     -Dexec.args="path/to/rdfs-folder path/to/shapes.ttl"
 * }</pre>
 *
 * <h2>Arguments (both optional)</h2>
 * <ol>
 *   <li>{@code rdfs-dir}    — directory that contains {@code *.rdf} RDFS profile files.
 *       Defaults to the submodule at
 *       {@code testing/entsoe/application-profiles-library/CGMES/CurrentRelease/RDFS}.</li>
 *   <li>{@code shapes-file} — path to a Turtle ({@code *.ttl}) SHACL shapes file.
 *       Defaults to the built-in {@code examples/example-shapes.ttl} classpath resource.</li>
 * </ol>
 *
 * <h2>What is validated</h2>
 * <ul>
 *   <li><b>Shape structure</b> — every {@code sh:targetClass}, {@code sh:class}, and
 *       {@code sh:path} IRI is checked against the schema index.</li>
 *   <li><b>Embedded SPARQL</b> — every {@code sh:select}/{@code sh:ask}/{@code sh:construct}
 *       string is parsed and validated, with {@code $this} treated as the target class declared
 *       by the enclosing shape for domain/range inference.</li>
 * </ul>
 */
public final class ShaclValidationExample {

    private static final Path DEFAULT_RDFS_DIR = Path.of(
            "testing", "entsoe", "application-profiles-library",
            "CGMES", "CurrentRelease", "RDFS");

    private static final String DEFAULT_SHAPES_RESOURCE = "examples/example-shapes.ttl";

    private ShaclValidationExample() {}

    public static void main(String[] args) throws Exception {
        Path rdfsDir    = args.length > 0 ? Path.of(args[0]) : DEFAULT_RDFS_DIR;
        Path shapesFile = args.length > 1 ? Path.of(args[1]) : null;

        // 1. Discover .rdf files and build the schema index.
        List<Path> rdfFiles = findRdfFiles(rdfsDir);
        if (rdfFiles.isEmpty()) {
            System.err.println("No .rdf files found in: " + rdfsDir.toAbsolutePath());
            System.err.println("Did you run: git submodule update --init ?");
            System.exit(1);
        }

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

        RdfsSchemaIndex index = RdfsSchemaIndex.fromCimRegistry(registry);
        SparqlValidationApi api = new SparqlValidationApi(index);

        // 2. Load shapes graph — from file or classpath.
        String shapesText;
        String shapesLabel;
        if (shapesFile != null) {
            shapesText  = Files.readString(shapesFile, StandardCharsets.UTF_8);
            shapesLabel = shapesFile.toString();
        } else {
            shapesText  = resourceText(DEFAULT_SHAPES_RESOURCE);
            shapesLabel = DEFAULT_SHAPES_RESOURCE + " (classpath)";
        }
        Graph shapesGraph = parseTurtle(shapesText);

        // 3. Validate.
        ShaclValidationResult result = api.validateShacl(shapesGraph);

        // 4. Print report.
        System.out.println("=================================================================");
        System.out.println(" OpenCGMES -- static SHACL shapes graph validation");
        System.out.println("=================================================================");
        System.out.println();

        System.out.println("--- schema profiles loaded from: " + rdfsDir.toAbsolutePath());
        List<VersionIri> allProfiles = index.getAllProfiles();
        for (VersionIri v : allProfiles) {
            var schema = index.profiles().get(v);
            System.out.printf("  %-55s  %3d cls  %3d prop%n",
                    shortIri(v.iri()),
                    schema.classes().size(),
                    schema.properties().size());
        }
        if (!parseErrors.isEmpty()) {
            System.out.println();
            System.out.println("  [WARN] parse errors (files skipped):");
            parseErrors.forEach(e -> System.out.println("    " + e));
        }

        System.out.println();
        System.out.println("--- shapes file: " + shapesLabel);
        printNumbered(shapesText);

        // Shape-structure section.
        System.out.println();
        System.out.println("--- shape-structure validation");
        List<SparqlValidationAnnotation> shapeAnn = result.shapeAnnotations();
        System.out.println("  annotations: " + shapeAnn.size());
        if (shapeAnn.isEmpty()) {
            System.out.println("  (no problems found)");
        } else {
            System.out.println();
            for (SparqlValidationAnnotation a : shapeAnn) {
                printAnnotation(a, "  ");
            }
        }

        // Embedded-SPARQL section.
        List<ShaclEmbeddedQueryResult> fragments = result.embeddedResults();
        System.out.println();
        System.out.println("--- embedded SPARQL fragments (" + fragments.size() + " found)");

        if (fragments.isEmpty()) {
            System.out.println("  (no embedded SPARQL queries in this shapes graph)");
        } else {
            for (int i = 0; i < fragments.size(); i++) {
                ShaclEmbeddedQueryResult fr = fragments.get(i);
                EmbeddedSparql emb = fr.embedded();
                System.out.println();
                System.out.println("  -- Fragment " + (i + 1) + " of " + fragments.size()
                        + "  [" + emb.kind() + "]"
                        + targetClassSuffix(emb.targetClasses()));
                printNumbered(emb.rawQuery().strip(), "     ");
                List<SparqlValidationAnnotation> fAnn = fr.result().annotations();
                if (fAnn.isEmpty()) {
                    System.out.println("  valid: true  (no problems)");
                } else {
                    System.out.println("  valid: false  -- " + fAnn.size() + " annotation(s)");
                    System.out.println();
                    for (SparqlValidationAnnotation a : fAnn) {
                        printAnnotation(a, "    ");
                    }
                }
            }
        }

        // Overall summary.
        System.out.println();
        System.out.println("=================================================================");
        int total = result.totalAnnotations();
        if (result.isValid()) {
            System.out.println(" Overall: VALID");
        } else {
            System.out.println(" Overall: INVALID  (" + total + " annotation(s)  -- "
                    + shapeAnn.size() + " shape-structure, "
                    + fragments.stream().mapToInt(f -> f.result().annotations().size()).sum()
                    + " fragment)");
        }
        System.out.println("=================================================================");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void printAnnotation(SparqlValidationAnnotation a, String indent) {
        String where = (a.line() != null)
                ? "line " + a.line() + ", col " + a.column()
                : "(no source location)";
        System.out.println(indent + "[" + a.severity() + "] " + a.code() + "  -- " + where);
        System.out.println(indent + "  " + a.message());
        Collection<VersionIri> selected = a.selectedProfiles();
        if (!selected.isEmpty()) {
            System.out.println(indent + "  scope: " + selected.stream()
                    .map(v -> shortIri(v.iri())).toList());
        }
        Collection<VersionIri> found = a.foundInOtherProfiles();
        if (!found.isEmpty()) {
            System.out.println(indent + "  exists in: " + found.stream()
                    .map(v -> shortIri(v.iri())).toList());
        }
        System.out.println();
    }

    private static String targetClassSuffix(java.util.Set<Node> classes) {
        if (classes.isEmpty()) return "";
        var sb = new StringBuilder("  target: [");
        boolean first = true;
        for (Node n : classes) {
            if (!first) sb.append(", ");
            sb.append(n.isURI() ? localName(n.getURI()) : n.toString());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String localName(String iri) {
        int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        return last >= 0 ? iri.substring(last + 1) : iri;
    }

    private static List<Path> findRdfFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return List.of();
        var out = new ArrayList<Path>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".rdf"))
                  .sorted()
                  .forEach(out::add);
        }
        return out;
    }

    private static String resourceText(String name) throws Exception {
        try (InputStream in = ShaclValidationExample.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Graph parseTurtle(String text) {
        Graph g = GraphFactory.createDefaultGraph();
        RDFParser.fromString(text, Lang.TURTLE).parse(g);
        return g;
    }

    private static void printNumbered(String text) {
        printNumbered(text, "  ");
    }

    private static void printNumbered(String text, String indent) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            System.out.printf("%s%2d | %s%n", indent, i + 1, lines[i]);
        }
    }

    private static String shortIri(String iri) {
        int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        if (last < 0) return iri;
        int prev = Math.max(iri.lastIndexOf('/', last - 1), iri.lastIndexOf('#', last - 1));
        return prev >= 0 ? iri.substring(prev + 1) : iri.substring(last + 1);
    }
}
