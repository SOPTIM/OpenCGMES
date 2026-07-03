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

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.LinkedCimDatasetGraph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.Ignore;
import org.junit.Test;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestConvertCimXmlToJsonLd {

    private static final String CIM_SCHEMA_IRI = "cgmes:schema:";
    private static final String CIM_MODEL_IRI = "cgmes:model:";
    private static final String CIM_HEADER_IRI = "cgmes:header:";

    public void convertCimXmlToJsonLd(String rdfsSchemaFolder, String cimXmlFolder, String jsonldOutputFile) {
        convertCimXmlToJsonLd(Path.of(rdfsSchemaFolder), Path.of(cimXmlFolder), Path.of(jsonldOutputFile));
    }

    public void convertCimXmlToJsonLd(Path rdfsSchemaFolder, Path cimXmlFolder, Path jsonldOutputFile) {
        final var dataset = new LinkedCimDatasetGraph();
        final var parser = new CimXmlParser();
        try (var rdfFiles = Files.list(rdfsSchemaFolder)) {
            rdfFiles.parallel()
                    .filter(f -> f.toString().endsWith(".rdf"))
                    .forEach(rdfFile -> {
                        try {
                            final var schema = parser.parseAndRegisterCimProfile(rdfFile);
                            // add only header profiles to the dataset.
                            // the other profiles are only added when referenced in the dataset.
                            if(schema.isHeaderProfile()) {
                                dataset.addGraph(NodeFactory.createURI(CIM_SCHEMA_IRI + schema.getDcatKeyword()), schema);
                                dataset.prefixes().putAll(schema.getPrefixMapping());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (var cimXmlFiles = Files.list(cimXmlFolder)) {
            cimXmlFiles.parallel()
                    .filter(f -> f.toString().endsWith(".xml"))
                    .forEach(cimXmlFile -> {
                        try {
                            final var cimDataset = parser.parseCimModel(cimXmlFile);
                            if(!cimDataset.isFullModel()) {
                                System.out.println("File " + cimXmlFile.getFileName() + " is not a FullModel. Skipping.");
                                return;
                            }
                            final var keywords = parser.getCimProfileRegistry()
                                    .getMatchingProfiles(cimDataset.getModelHeader().getProfiles())
                                    .stream()
                                    .peek(schema -> {
                                        final var schemaGraphName = NodeFactory.createURI(
                                            CIM_SCHEMA_IRI + schema.getDcatKeyword());
                                        if(!dataset.containsGraph(schemaGraphName)) {
                                            dataset.addGraph(schemaGraphName, schema);
                                            dataset.prefixes().putAll(schema.getPrefixMapping());
                                        }
                                    })
                                    .map(CimProfile::getDcatKeyword)
                                    .distinct()
                                    .sorted()
                                    .toList();
                            if(keywords.isEmpty()) {
                                System.out.println("File " + cimXmlFile.getFileName() + " does not match any profile. Skipping.");
                                return;
                            }
                            var keyword = keywords.getFirst();
                            if(keywords.size() > 1) {
                                System.out.println("File " + cimXmlFile.getFileName() + " matches multiple profiles: " + keywords + ". Using first one: " + keyword);
                            }
                            dataset.addGraph(NodeFactory.createURI(CIM_HEADER_IRI + keyword), cimDataset.getModelHeader());
                            dataset.addGraph(NodeFactory.createURI(CIM_MODEL_IRI + keyword), cimDataset.getBody());
                            dataset.prefixes().putAll(cimDataset.prefixes());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // create java nio file and output stream
        try (var rdfFiles = new BufferedOutputStream(Files.newOutputStream(jsonldOutputFile))) {
            RDFDataMgr.write(rdfFiles, dataset, Lang.JSONLD11);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Ignore
    @Test
    public void convertFullGridMerged() {
        convertCimXmlToJsonLd(
                "../data/ApplicationProfilesLibrary-release1-1-0/CGMES/RDFS/",
                "../data/CGMES_ConformityAssessmentScheme_r3-0-2/CGMES_ConformityAssessmentScheme_TestConfigurations_v3-0-3/v3.0/FullGrid/FullGrid-Merged/",
                "../output/FullGrid-Merged.jsonld");
    }

    @Ignore
    @Test
    public void convertRealGridMerged() {
        convertCimXmlToJsonLd(
                "../data/ApplicationProfilesLibrary-release1-1-0/CGMES/RDFS/",
                "../data/CGMES_ConformityAssessmentScheme_r3-0-2/CGMES_ConformityAssessmentScheme_TestConfigurations_v3-0-3/v3.0/RealGrid/RealGrid-Merged/",
                "../output/RealGrid-Merged.jsonld");
    }
}