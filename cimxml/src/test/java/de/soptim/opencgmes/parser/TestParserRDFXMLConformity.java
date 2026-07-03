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

package de.soptim.opencgmes.parser;

import de.soptim.opencgmes.cimxml.parser.ReaderCIMXML_StAX_SR;
import de.soptim.opencgmes.cimxml.parser.system.StreamCimXmlToDatasetGraph;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * RDF/XML parser conformity tests using W3C examples.
 *
 * <p>This software includes material copied from or derived from the
 * W3C RDF/XML Syntax Specification (Revised), W3C Recommendation 10 February 2004.
 * Copyright © 2004 World Wide Web Consortium.
 * <a href="https://www.w3.org/copyright/software-license-2023/">https://www.w3.org/copyright/software-license-2023/</a></p>
 *
 * <p>Original source: <a href="https://www.w3.org/TR/rdf-syntax-grammar/">
 * https://www.w3.org/TR/rdf-syntax-grammar/</a></p>
 *
 * @see <a href="https://www.w3.org/TR/2004/REC-rdf-syntax-grammar-20040210/">
 *      W3C RDF/XML Syntax Specification (Revised)</a>
 */
@RunWith(Parameterized.class)
public class TestParserRDFXMLConformity {


    private static final Path W3C_TEST_DIR = Paths.get("testing", "w3c-rdf-syntax-grammar");

    // Parameterized test fields
    public final String testName;
    public final Path rdfXmlPath;
    public final Path nTriplesPath;
    private final boolean skipTest;

    public TestParserRDFXMLConformity(String testName, Path rdfXmlPath, Path nTriplesPath, boolean skipTest) {
        this.testName = testName;
        this.rdfXmlPath = rdfXmlPath;
        this.nTriplesPath = nTriplesPath;
        this.skipTest = skipTest;
    }

    /**
     * Provides test cases for the parameterized test.
     * Scans the w3c-examples directory for .rdf files and their corresponding .nt files.
     * Provides test parameters for the parameterized test.
     * Returns a collection of test cases, each containing:
     * [0] = test name (String)
     * [1] = RDF/XML file path (Path)
     * [2] = N-Triples file path (Path, can be null)
     */
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> discoverW3cTestCases() throws IOException {
        final List<Object[]> testCases = new ArrayList<>();

        // Check if test files are available
        if (!Files.exists(W3C_TEST_DIR) && Files.isDirectory(W3C_TEST_DIR)) {
            System.err.println("W3C test files not found at: " + W3C_TEST_DIR.toAbsolutePath());
            System.err.println("To run these tests, extract W3C RDF/XML examples to: testing/w3c-rdf-syntax-grammar/");
            System.err.println("Download from: https://www.w3.org/TR/rdf-syntax-grammar/");

            // Add dummy test case that will be skipped
            testCases.add(new Object[] {
                    "W3C test files not available",
                    null,
                    null,
                    true  // skipTest = true
            });
            return testCases;
        }

        // Find all .rdf files in the directory
        try (Stream<Path> paths = Files.walk(W3C_TEST_DIR, 1)) {
            paths.filter(path -> path.toString().endsWith(".rdf"))
                    .sorted()
                    .forEach(rdfPath -> {
                        final String fileName = rdfPath.getFileName().toString();
                        final String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

                        // Check for corresponding .nt file
                        final Path ntPath = rdfPath.getParent().resolve(baseName + ".nt");
                        final boolean hasNTriples = Files.exists(ntPath);

                        // Create display name
                        final String displayName = baseName.replace("example", "W3C Example ");

                        testCases.add(new Object[]{
                                displayName,
                                rdfPath,
                                hasNTriples ? ntPath : null,
                                false  // skipTest = false
                        });
                    });
        }

        // If no test cases found, add a dummy case that will be skipped
        if (testCases.isEmpty()) {
            testCases.add(new Object[] { "No test files", null, null, true } );
        }

        return testCases;
    }

    @Test
    public void testW3cRdfXmlExample() throws Exception {
        // Skip if test files are not available
        assertFalse("W3C test files not available", skipTest);
        assertNotNull("Test file path is null", rdfXmlPath);
        // Run the test
        parseAndCompare(rdfXmlPath, nTriplesPath);
    }


    public void parseAndCompare(Path rdfxml, Path nTriples) throws Exception {
        Objects.requireNonNull(rdfxml);
        final var expectedGraph = GraphFactory.createGraphMem();
        final var parser = new ReaderCIMXML_StAX_SR();
        final var streamRDF = new StreamCimXmlToDatasetGraph();

        try(var fileReader = new FileReader(rdfxml.toFile())) {
            parser.read(fileReader, streamRDF);
        }
        var graph = streamRDF.getCimDatasetGraph().getDefaultGraph();

        RDFParser.create()
                .source(rdfxml)
                .lang(org.apache.jena.riot.Lang.RDFXML)
                .checking(false)
                .parse(expectedGraph);

        assertGraphEquals(expectedGraph, graph);
        assertPrefixMappingEquals(expectedGraph.getPrefixMapping(), streamRDF.getCimDatasetGraph().prefixes());

        if (nTriples != null) {
            final var nTriplesGraph = GraphFactory.createGraphMem();
            RDFParser.create()
                    .source(nTriples)
                    .lang(org.apache.jena.riot.Lang.NTRIPLES)
                    .checking(false)
                    .parse(nTriplesGraph);

            assertGraphEquals(expectedGraph, nTriplesGraph);
        }
    }

    public void assertGraphEquals(Graph expected, Graph actual) {
        // check that all triples in expected graph are in actual graph
        expected.find().forEachRemaining(expectedTriple -> {
            if (!actual.contains(expectedTriple)) {
                if (expectedTriple.getSubject().isBlank() || expectedTriple.getPredicate().isBlank() || expectedTriple.getObject().isBlank()) {
                    return; // Blank nodes are not compared by value, so we skip them
                }
            }
            assertTrue("Graphs are not equal: missing triple " + expectedTriple,
                    actual.contains(expectedTriple));
        });

        // check graph sizes
        assertEquals("Graphs are not equal: different sizes.",
                expected.size(), actual.size());

        assertTrue(expected.isIsomorphicWith(actual)); // isIsomorphicWith also checks blank nodes
    }

    public void assertPrefixMappingEquals(PrefixMapping expected, PrefixMap actual) {

        // check namespace mappings size
        assertEquals("PrefixMappings are not equal: different number of namespaces.",
                expected.numPrefixes(), actual.size());

        // check that all namespaces in expected graph are in actual graph
        expected.getNsPrefixMap().forEach((prefix, uri) -> {
            assertTrue("PrefixMappings are not equal: missing namespace " + prefix + " -> " + uri,
                    actual.containsPrefix(prefix));
            assertEquals("PrefixMappings are not equal: different URI for namespace " + prefix,
                    uri, actual.get(prefix));
        });
    }
}
