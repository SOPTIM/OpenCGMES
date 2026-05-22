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

package de.soptim.opencgmes.sparql.validation.examples;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import de.soptim.opencgmes.sparql.validation.SparqlValidationAnnotation;
import de.soptim.opencgmes.sparql.validation.SparqlValidationApi;
import de.soptim.opencgmes.sparql.validation.SparqlValidationResult;
import de.soptim.opencgmes.sparql.validation.schema.RdfsSchemaIndex;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal runnable example: validate a SPARQL query against a CIM/RDFS schema, both loaded
 * from classpath resources, with the result printed to the terminal.
 *
 * <h2>Run it</h2>
 * <pre>{@code
 * mvn -q install -DskipTests            # once, so cimxml is in your local repo
 * mvn -q -pl sparql-validation exec:java
 * }</pre>
 *
 * <p>It reads two files from {@code src/main/resources/examples/}:</p>
 * <ul>
 *   <li>{@code cim-mini-schema.rdf} — a small CIM-style RDFS profile</li>
 *   <li>{@code example-query.rq}   — a SPARQL query with two deliberate mistakes</li>
 * </ul>
 */
public final class SparqlValidationExample {

    private static final String SCHEMA_RESOURCE = "examples/cim-mini-schema.rdf";
    private static final String QUERY_RESOURCE  = "examples/example-query.rq";

    private SparqlValidationExample() {}

    public static void main(String[] args) throws Exception {
        // 1. Load the RDFS schema and build the validation API.
        SparqlValidationApi api;
        try (Reader reader = resourceReader(SCHEMA_RESOURCE)) {
            CimProfile profile = new RdfXmlParser().parseCimProfile(reader);
            CimProfileRegistry registry = new CimProfileRegistryStd();
            registry.register(profile);
            api = new SparqlValidationApi(RdfsSchemaIndex.fromCimRegistry(registry));
        }

        // 2. Load the SPARQL query.
        String query = resourceText(QUERY_RESOURCE);

        // 3. Validate and print the result.
        SparqlValidationResult result = api.validateSparql(query);

        var index = (RdfsSchemaIndex) api.schemaIndex();
        System.out.println("==============================================================");
        System.out.println(" OpenCGMES — static SPARQL query validation");
        System.out.println("==============================================================");
        index.getAllProfiles().forEach(v -> {
            var schema = index.profiles().get(v);
            System.out.println(" schema : " + SCHEMA_RESOURCE);
            System.out.println(" profile: " + v.iri()
                    + "  (" + schema.classes().size() + " classes, "
                    + schema.properties().size() + " properties)");
        });
        System.out.println(" query  : " + QUERY_RESOURCE);

        System.out.println();
        System.out.println("----- query --------------------------------------------------");
        printNumbered(query);

        System.out.println();
        System.out.println("----- result -------------------------------------------------");
        System.out.println(" valid: " + result.isValid());
        if (result.annotations().isEmpty()) {
            System.out.println(" (no problems found)");
        }
        for (SparqlValidationAnnotation a : result.annotations()) {
            String where = (a.line() != null)
                    ? "line " + a.line() + ", col " + a.column()
                    : "(no position)";
            System.out.println();
            System.out.println(" [" + a.severity() + "] " + a.code() + "  —  " + where);
            System.out.println("   " + a.message());
        }
        System.out.println();
    }

    // ---- resource loading -----------------------------------------------------------------

    private static Reader resourceReader(String name) {
        InputStream in = SparqlValidationExample.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource: " + name);
        }
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static String resourceText(String name) throws Exception {
        try (InputStream in = SparqlValidationExample.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void printNumbered(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            System.out.printf(" %2d | %s%n", i + 1, lines[i]);
        }
    }
}
