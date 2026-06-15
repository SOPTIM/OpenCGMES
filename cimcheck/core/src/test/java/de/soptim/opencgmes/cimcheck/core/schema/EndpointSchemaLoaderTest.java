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

package de.soptim.opencgmes.cimcheck.core.schema;

import de.soptim.opencgmes.cimcheck.core.VersionIri;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link EndpointSchemaLoader} and {@link NamedGraphProfileResolver} end-to-end against an
 * in-memory {@link Dataset}: the schema is loaded from the schema graphs and each instance graph is
 * auto-mapped to the profile whose discriminating terms it uses.
 */
public class EndpointSchemaLoaderTest {

    private static final String EQ_VERSION = "http://iec.ch/TC57/CIM100/EquipmentCore/1.0";
    private static final String TP_VERSION = "http://iec.ch/TC57/CIM100/Topology/1.0";

    /** Schema graph declaring the EQ profile with an EQ-only class. */
    private static final String EQ_SCHEMA = """
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            <http://iec.ch/TC57/CIM100/EquipmentCore> a owl:Ontology ;
                owl:versionIRI <http://iec.ch/TC57/CIM100/EquipmentCore/1.0> ;
                dcat:keyword "EQ" .

            <http://iec.ch/TC57/CIM100#ACLineSegment> a rdfs:Class .
            """;

    /** Schema graph declaring the TP profile with a TP-only class. */
    private static final String TP_SCHEMA = """
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            <http://iec.ch/TC57/CIM100/Topology> a owl:Ontology ;
                owl:versionIRI <http://iec.ch/TC57/CIM100/Topology/1.0> ;
                dcat:keyword "TP" .

            <http://iec.ch/TC57/CIM100#TopologicalNode> a rdfs:Class .
            """;

    private static Model parse(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.fromString(ttl, Lang.TURTLE).parse(m);
        return m;
    }

    private static Dataset datasetWithSchema() {
        Dataset ds = DatasetFactory.createGeneral();
        ds.addNamedModel("http://ex.org/schema/eq", parse(EQ_SCHEMA));
        ds.addNamedModel("http://ex.org/schema/tp", parse(TP_SCHEMA));
        ds.addNamedModel("http://ex.org/data/eq", parse(
                "<http://ex.org/l1> a <http://iec.ch/TC57/CIM100#ACLineSegment> ."));
        ds.addNamedModel("http://ex.org/data/tp", parse(
                "<http://ex.org/n1> a <http://iec.ch/TC57/CIM100#TopologicalNode> ."));
        return ds;
    }

    @Test
    public void autoMapsInstanceGraphsToTheirProfile() {
        EndpointSchema es = EndpointSchemaLoader.load(new DatasetSparqlGraphSource(datasetWithSchema()));

        assertTrue("schema should be resolved", es.hasSchema());
        var scope = es.namedGraphScope();
        assertEquals(VersionIri.of(EQ_VERSION),
                scope.get(NodeFactory.createURI("http://ex.org/data/eq")).iterator().next());
        assertEquals(VersionIri.of(TP_VERSION),
                scope.get(NodeFactory.createURI("http://ex.org/data/tp")).iterator().next());
        assertTrue("no graph should be unmatched", es.unmatchedGraphs().isEmpty());
        // Schema graphs are not classified as instance data.
        assertFalse(scope.containsKey(NodeFactory.createURI("http://ex.org/schema/eq")));
    }

    @Test
    public void reportsGraphsWithNoMatchingProfileAsUnmatched() {
        Dataset ds = datasetWithSchema();
        ds.addNamedModel("http://ex.org/data/junk", parse("<http://ex.org/x> a <http://example.org/Foo> ."));

        EndpointSchema es = EndpointSchemaLoader.load(new DatasetSparqlGraphSource(ds));

        assertTrue(es.hasSchema());
        assertFalse(es.namedGraphScope().containsKey(NodeFactory.createURI("http://ex.org/data/junk")));
        assertTrue(es.unmatchedGraphs().contains(NodeFactory.createURI("http://ex.org/data/junk")));
    }

    @Test
    public void reportsNoSchemaWhenEndpointHasNoProfileGraphs() {
        Dataset ds = DatasetFactory.createGeneral();
        ds.addNamedModel("http://ex.org/data/eq", parse(
                "<http://ex.org/l1> a <http://iec.ch/TC57/CIM100#ACLineSegment> ."));

        EndpointSchema es = EndpointSchemaLoader.load(new DatasetSparqlGraphSource(ds));

        assertFalse("no schema graphs means no schema", es.hasSchema());
        assertNull(es.index());
        assertTrue(es.namedGraphScope().isEmpty());
    }
}
