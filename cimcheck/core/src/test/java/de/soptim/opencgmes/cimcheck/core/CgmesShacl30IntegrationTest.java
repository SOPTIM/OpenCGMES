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

import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.core.shacl.EmbeddedSparql;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration tests — SHACL validation against real ENTSO-E CGMES 3.0 shape files.
 *
 * <p>These tests are skipped automatically when the git submodule is not initialised.</p>
 *
 * <p>Two SHACL variants are exercised:</p>
 * <ul>
 *   <li><b>Simple</b> ({@code 61970-600-2_Equipment-AP-Con-Simple-SHACL.ttl}) — structural
 *       constraints only (cardinality, datatype, association type). No embedded SPARQL.</li>
 *   <li><b>Complex</b> ({@code 61970-301_Equipment-AP-Con-Complex-SHACL.ttl}) — structural
 *       constraints plus embedded {@code sh:select} queries for cross-profile business rules.</li>
 * </ul>
 *
 * <p>A known-by-design limitation: ENTSO-E shapes use {@code sh:path ( cim:SomeProp rdf:type )}
 * sequence paths to check association value types. The {@code rdf:type} step in these paths
 * is an RDF core term, not a CIM property, so it produces {@code UNKNOWN_PROPERTY} annotations
 * in structural shape analysis. The tests below assert that <em>only</em> {@code rdf:type}
 * triggers property annotations — no real CIM terms are falsely reported as unknown.</p>
 */
public class CgmesShacl30IntegrationTest {

    private static final String CIM100 = "http://iec.ch/TC57/CIM100#";

    // rdf:type appears as the second step in association value-type paths: sh:path ( cim:Prop rdf:type )
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final Path RDFS_DIR = Path.of(
            "testing/entsoe/application-profiles-library/CGMES/CurrentRelease/RDFS");
    private static final Path SHACL_DIR = Path.of(
            "testing/entsoe/application-profiles-library/CGMES/CurrentRelease/SHACL");

    private static final String EQ_VERSION_IRI = "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0";

    private static final String EQ_SIMPLE_SHACL = "61970-600-2_Equipment-AP-Con-Simple-SHACL.ttl";
    private static final String EQ_COMPLEX_SHACL = "61970-301_Equipment-AP-Con-Complex-SHACL.ttl";

    private SparqlValidationApi api;

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("CGMES 3.0 submodule not initialised — skipping",
                Files.isDirectory(RDFS_DIR) && hasRdfFiles(RDFS_DIR));

        CimProfileRegistry registry = new CimProfileRegistryStd();
        RdfXmlParser parser = new RdfXmlParser();
        try (var stream = Files.list(RDFS_DIR)) {
            stream.filter(p -> p.toString().endsWith(".rdf"))
                  .sorted()
                  .forEach(path -> {
                      try {
                          var profile = parser.parseCimProfile(path);
                          registry.register(profile);
                      } catch (IllegalArgumentException e) {
                          // Duplicate version IRIs — skip.
                      } catch (IOException e) {
                          throw new RuntimeException("Failed to read " + path, e);
                      }
                  });
        }
        api = new SparqlValidationApi(RdfsSchemaIndex.fromCimRegistry(registry));
    }

    // ---- Equipment Simple SHACL (structural constraints only) ----------------------------

    @Test
    public void equipmentSimpleShapes_noUnknownClassAnnotations() throws IOException {
        Graph g = loadShacl(EQ_SIMPLE_SHACL);
        var r = api.validateShacl(g);
        var classErrors = r.shapeAnnotations().stream()
                .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS)
                .toList();
        assertTrue("All sh:targetClass/sh:class terms must resolve to known CIM100 classes; got: "
                + classErrors, classErrors.isEmpty());
    }

    @Test
    public void equipmentSimpleShapes_unknownPropertyAnnotationsOnlyForRdfType() throws IOException {
        // ENTSO-E uses sequence paths ( cim:Prop rdf:type ) for value-type checks.
        // rdf:type is an RDF core term, not a CIM property — it's expected to be flagged.
        // No other property should be unknown.
        Graph g = loadShacl(EQ_SIMPLE_SHACL);
        var r = api.validateShacl(g);
        var nonRdfTypeErrors = r.shapeAnnotations().stream()
                .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_PROPERTY)
                .filter(a -> a.term() != null && !RDF_TYPE.equals(a.term().getURI()))
                .toList();
        assertTrue("Only rdf:type should appear as UNKNOWN_PROPERTY; unexpected terms: "
                + nonRdfTypeErrors, nonRdfTypeErrors.isEmpty());
    }

    @Test
    public void equipmentSimpleShapes_classDependenciesIncludeAcLineSegment() throws IOException {
        Graph g = loadShacl(EQ_SIMPLE_SHACL);
        Collection<org.apache.jena.graph.Node> classes = api.getShaclClassDependencies(g);
        assertTrue("getShaclClassDependencies must include cim:ACLineSegment from sh:targetClass",
                classes.contains(NodeFactory.createURI(CIM100 + "ACLineSegment")));
    }

    @Test
    public void equipmentSimpleShapes_profileDependencyIncludesEq() throws IOException {
        Graph g = loadShacl(EQ_SIMPLE_SHACL);
        Collection<VersionIri> profiles = api.getShaclProfileDependencies(g);
        assertTrue("Equipment SHACL must depend on the EQ 3.0 profile",
                profiles.contains(VersionIri.of(EQ_VERSION_IRI)));
    }

    // ---- Equipment Complex SHACL (structural + embedded SPARQL) -------------------------

    @Test
    public void equipmentComplexShapes_embeddedSparqlIsExtracted() throws IOException {
        Graph g = loadShacl(EQ_COMPLEX_SHACL);
        List<EmbeddedSparql> queries = api.extractShaclSparql(g);
        assertFalse("Complex Equipment SHACL must contain at least one embedded sh:select query",
                queries.isEmpty());
    }

    @Test
    public void equipmentComplexShapes_noUnknownClassInEmbeddedSparql() throws IOException {
        Graph g = loadShacl(EQ_COMPLEX_SHACL);
        var r = api.validateShacl(g);
        var embeddedClassErrors = r.embeddedResults().stream()
                .flatMap(er -> er.result().annotations().stream())
                .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS)
                .toList();
        assertTrue("Embedded SPARQL in Complex Equipment SHACL must not reference unknown CIM100 classes; got: "
                + embeddedClassErrors, embeddedClassErrors.isEmpty());
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Graph loadShacl(String filename) throws IOException {
        String text = Files.readString(SHACL_DIR.resolve(filename));
        Graph g = GraphFactory.createDefaultGraph();
        RDFParser.fromString(text, Lang.TURTLE).parse(g);
        return g;
    }

    private static boolean hasRdfFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".rdf"));
        }
    }
}
