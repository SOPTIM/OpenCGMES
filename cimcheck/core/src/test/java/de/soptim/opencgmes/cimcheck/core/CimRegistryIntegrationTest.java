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
import org.apache.jena.graph.NodeFactory;
import org.junit.Before;
import org.junit.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration test for {@link RdfsSchemaIndex#fromCimRegistry}: parses a small CIM profile via
 * the cimxml parser, registers it in a {@link CimProfileRegistry}, builds a schema index from
 * the registry, then validates SPARQL against the result.
 *
 * <p>The interesting part is that CIM profiles describe property ranges via
 * {@code cims:dataType} rather than {@code rdfs:range}. Without the {@code PropertyInfo}
 * overlay in {@code fromCimRegistry} the validator wouldn't see those datatypes and
 * {@code DATATYPE_MISMATCH} could never fire.</p>
 */
public class CimRegistryIntegrationTest {

    private static final String CIM = "http://iec.ch/TC57/CIM100#";
    private static final String PROFILE_IRI = "http://example.org/MyCustom/1/1";

    private static final String CLASS_A         = CIM + "ClassA";
    private static final String CLASS_IDENT     = CIM + "IdentifiedObject";
    private static final String PROP_FLOAT      = CIM + "ClassA.floatProperty";
    private static final String PROP_TEXT       = CIM + "ClassA.textProperty";

    private static final String PROFILE_RDFXML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
           xmlns:cim="http://iec.ch/TC57/CIM100#"
           xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
           xmlns:dcat="http://www.w3.org/ns/dcat#"
           xmlns:owl="http://www.w3.org/2002/07/owl#"
           xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
           xml:base="http://iec.ch/TC57/CIM100">
            <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                <dcat:keyword>MYCUST</dcat:keyword>
                <owl:versionIRI rdf:resource="http://example.org/MyCustom/1/1"/>
                <owl:versionInfo xml:lang="en">1.1.0</owl:versionInfo>
                <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description>

            <rdf:Description rdf:about="#IdentifiedObject">
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#concrete"/>
            </rdf:Description>

            <rdf:Description rdf:about="#ClassA">
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                <rdfs:subClassOf rdf:resource="#IdentifiedObject"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#concrete"/>
            </rdf:Description>

            <rdf:Description rdf:about="#ClassA.floatProperty">
                <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                <rdfs:domain rdf:resource="#ClassA"/>
                <cims:dataType rdf:resource="#Float"/>
            </rdf:Description>

            <rdf:Description rdf:about="#ClassA.textProperty">
                <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                <rdfs:domain rdf:resource="#ClassA"/>
                <cims:dataType rdf:resource="#String"/>
            </rdf:Description>

            <rdf:Description rdf:about="#Float">
                <rdfs:label xml:lang="en">Float</rdfs:label>
                <cims:stereotype>Primitive</cims:stereotype>
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
            </rdf:Description>

            <rdf:Description rdf:about="#String">
                <rdfs:label xml:lang="en">String</rdfs:label>
                <cims:stereotype>Primitive</cims:stereotype>
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
            </rdf:Description>
        </rdf:RDF>
        """;

    private static final String PREAMBLE =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX cim: <" + CIM + ">\n"
            + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";

    private SparqlValidationApi api;

    @Before
    public void setUp() {
        var profile = new RdfXmlParser().parseCimProfile(new StringReader(PROFILE_RDFXML));
        CimProfileRegistry registry = new CimProfileRegistryStd();
        registry.register(profile);
        var index = RdfsSchemaIndex.fromCimRegistry(registry);
        api = new SparqlValidationApi(index);
    }

    @Test
    public void schemaIndexExposesClassesPropertiesAndSubClassOf() {
        var index = (RdfsSchemaIndex) api.schemaIndex();
        var versionIri = VersionIri.of(PROFILE_IRI);
        var schema = index.profiles().get(versionIri);
        assertNotNull("profile must be registered under owl:versionIRI", schema);

        assertTrue(schema.classes().contains(NodeFactory.createURI(CLASS_A)));
        assertTrue(schema.classes().contains(NodeFactory.createURI(CLASS_IDENT)));

        assertTrue(schema.properties().contains(NodeFactory.createURI(PROP_FLOAT)));
        assertTrue(schema.properties().contains(NodeFactory.createURI(PROP_TEXT)));

        // subClassOf is read from rdfs:subClassOf in the graph.
        var superA = schema.subClassOf().get(NodeFactory.createURI(CLASS_A));
        assertNotNull("ClassA should declare a super-class", superA);
        assertTrue(superA.contains(NodeFactory.createURI(CLASS_IDENT)));
    }

    @Test
    public void propertyDomainAndRangeComeFromCimPropertyInfo() {
        var index = (RdfsSchemaIndex) api.schemaIndex();
        var scope = List.of(VersionIri.of(PROFILE_IRI));

        var domains = index.domainsOf(NodeFactory.createURI(PROP_FLOAT), scope);
        assertEquals("ClassA.floatProperty domain comes from rdfs:domain via PropertyInfo",
                1, domains.size());
        assertTrue(domains.contains(NodeFactory.createURI(CLASS_A)));

        var floatRanges = index.rangesOf(NodeFactory.createURI(PROP_FLOAT), scope);
        assertEquals("xsd:float should be the resolved range", 1, floatRanges.size());
        assertEquals("http://www.w3.org/2001/XMLSchema#float",
                floatRanges.iterator().next().getURI());

        var textRanges = index.rangesOf(NodeFactory.createURI(PROP_TEXT), scope);
        assertEquals(1, textRanges.size());
        assertEquals("http://www.w3.org/2001/XMLSchema#string",
                textRanges.iterator().next().getURI());
    }

    // ---- Validator behaviour through the CIM-derived index --------------------------------

    @Test
    public void datatypeMismatchFiresOnStringForFloatProperty() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ClassA ; cim:ClassA.floatProperty \"abc\" . }");
        var a = expectSingle(r, SparqlValidationCode.DATATYPE_MISMATCH);
        assertEquals(SparqlValidationSeverity.WARN, a.severity());
        assertTrue("message should mention xsd:float as the expected range",
                a.message().contains("float"));
    }

    @Test
    public void datatypeMismatchSilentForCompatibleNumeric() {
        // xsd:int is in the numeric bucket; xsd:float range is also numeric → no WARN.
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ClassA ; cim:ClassA.floatProperty \"5\"^^xsd:int . }");
        long mismatch = r.annotations().stream()
                .filter(an -> an.code() == SparqlValidationCode.DATATYPE_MISMATCH).count();
        assertEquals(0, mismatch);
    }

    @Test
    public void propertyNotAllowedFiresOnWrongSubjectClass() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:IdentifiedObject ; cim:ClassA.floatProperty ?v . }");
        var a = expectSingle(r, SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS);
        assertEquals(SparqlValidationSeverity.ERROR, a.severity());
        assertTrue(a.message().contains("ClassA.floatProperty"));
        assertTrue(a.message().contains("IdentifiedObject"));
    }

    @Test
    public void propertyAllowedOnDeclaredDomain() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ClassA ; cim:ClassA.floatProperty ?v . }");
        long errors = r.annotations().stream()
                .filter(an -> an.severity() == SparqlValidationSeverity.ERROR).count();
        assertEquals("no ERROR annotations expected; got: " + r.annotations(), 0, errors);
    }

    @Test
    public void impliedTypeInfoFromUntypedSubject() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:ClassA.textProperty ?n . }");
        var a = expectSingle(r, SparqlValidationCode.QUERY_IMPLIED_TYPE);
        assertEquals(SparqlValidationSeverity.INFO, a.severity());
        assertEquals(CLASS_A, a.term().getURI());
    }

    @Test
    public void sourceLocatorLinesUpWithPrefixedName() {
        // The error is on line 2; the prefixed name 'cim:doesNotExist' should be located
        // there by the upgraded SourceLocator.
        String query = PREAMBLE
                + "SELECT * WHERE { ?s cim:doesNotExist ?v . }";
        var r = api.validateSparql(query);
        var a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertNotNull("locator should resolve the prefixed name", a.line());
        // Find expected column manually (1-based) to make sure the locator returned the
        // exact character position.
        int needle = query.indexOf("cim:doesNotExist");
        int col = 1;
        for (int i = query.lastIndexOf('\n', needle) + 1; i < needle; i++) col++;
        assertEquals(Integer.valueOf(col), a.column());
    }

    @Test
    public void sourceLocatorPinpointsAKeywordForUnknownClass() {
        // 'a' shorthand for rdf:type with an unknown class object — the locator should hit
        // the unknown class's prefixed form (not the 'a' keyword, since the error term is
        // the class, not rdf:type).
        String query = PREAMBLE + "SELECT * WHERE { ?s a cim:DoesNotExist . }";
        var r = api.validateSparql(query);
        var a = expectSingle(r, SparqlValidationCode.UNKNOWN_CLASS);
        int needle = query.indexOf("cim:DoesNotExist");
        int col = 1;
        for (int i = query.lastIndexOf('\n', needle) + 1; i < needle; i++) col++;
        assertEquals(Integer.valueOf(col), a.column());
    }

    // ---- helpers --------------------------------------------------------------------------

    private static SparqlValidationAnnotation expectSingle(
            SparqlValidationResult r, SparqlValidationCode code) {
        var matches = r.annotations().stream().filter(a -> a.code() == code).toList();
        assertEquals("expected exactly one annotation with code " + code + ", got: "
                + r.annotations(), 1, matches.size());
        return matches.get(0);
    }
}
