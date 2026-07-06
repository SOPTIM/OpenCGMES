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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.ReaderCIMXML_StAX_SR;
import de.soptim.opencgmes.cimxml.parser.system.StreamCimXmlToDatasetGraph;
import java.io.StringReader;
import java.util.Set;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.NodeFactory;
import org.junit.Test;

public class TestCimProfileRegistryStdTypeMapping {

  /**
   * The test is identical to
   * de.soptim.opencgmes.cimxml.rdfs.TestCimProfileRegistryStd#registerProfileWithOneClassAndTwoSimpleProperties()
   * except that not CIM primitive datatypes are referenced using 'cims:dataType', except the XML
   * Schema datatypes are referenced directly using 'rdfs:range'.
   */
  @Test
  public void registerProfileWithOneClassAndTwoXSDProperties() {
    final var rdfxml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
           xmlns:cim="http://iec.ch/TC57/CIM100#"
           xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
           xmlns:dcat="http://www.w3.org/ns/dcat#"
           xmlns:owl="http://www.w3.org/2002/07/owl#"
           xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
           xml:base ="http://iec.ch/TC57/CIM100">
            <!-- ······························································································· -->
            <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                <dcat:keyword>MYCUST</dcat:keyword>
                <owl:versionIRI rdf:resource="http://example.org/MyCustom/1/1"/>
                <owl:versionInfo xml:lang ="en">1.1.0</owl:versionInfo>
               <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description >
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassA">
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                <rdfs:subClassOf rdf:resource="#IdentifiedObject"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#concrete"/>
            </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassA.floatProperty">
                <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                <rdfs:domain rdf:resource="#ClassA"/>
                <rdfs:range rdf:resource="http://www.w3.org/2001/XMLSchema#float"/>
             </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassA.textProperty">
                <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                <rdfs:domain rdf:resource="#ClassA"/>
                <rdfs:range rdf:resource="http://www.w3.org/2001/XMLSchema#string"/>
            </rdf:Description>
        </rdf:RDF>
        """;

    final var parser = new ReaderCIMXML_StAX_SR();
    final var streamRDF = new StreamCimXmlToDatasetGraph();

    parser.read(new StringReader(rdfxml), streamRDF);

    var graph = streamRDF.getCimDatasetGraph().getDefaultGraph();

    var profile = CimProfile.wrap(graph);

    var registry = new CimProfileRegistryStd();
    registry.register(profile);

    var owlVersionIRIs = Set.of(NodeFactory.createURI("http://example.org/MyCustom/1/1"));

    assertTrue(registry.containsProfile(owlVersionIRIs));

    assertTrue(registry.getRegisteredProfiles().contains(profile));
    assertEquals(1, registry.getRegisteredProfiles().size());

    var properties = registry.getPropertiesAndDatatypes(owlVersionIRIs);
    assertNotNull(properties);
    assertEquals(2, properties.size());

    var floatProperty = NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.floatProperty");
    assertTrue(properties.containsKey(floatProperty));
    var propertyInfo = properties.get(floatProperty);
    assertEquals(NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA"), propertyInfo.rdfType());
    assertEquals(floatProperty, propertyInfo.property());
    assertEquals(XSDDatatype.XSDfloat, propertyInfo.primitiveType());
    assertNull(propertyInfo.referenceType());

    var textProperty = NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.textProperty");
    assertTrue(properties.containsKey(textProperty));
    propertyInfo = properties.get(textProperty);
    assertEquals(NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA"), propertyInfo.rdfType());
    assertEquals(textProperty, propertyInfo.property());
    assertEquals(XSDDatatype.XSDstring, propertyInfo.primitiveType());
    assertNull(propertyInfo.referenceType());
  }

  @Test
  public void registerProfileUsingCustomDataType() {
    final var rdfxml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
           xmlns:cim="http://iec.ch/TC57/CIM100#"
           xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"
           xmlns:dcat="http://www.w3.org/ns/dcat#"
           xmlns:owl="http://www.w3.org/2002/07/owl#"
           xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
           xml:base ="http://iec.ch/TC57/CIM100">
            <!-- ······························································································· -->
            <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                <dcat:keyword>MYCUST</dcat:keyword>
                <owl:versionIRI rdf:resource="http://example.org/MyCustom/1/1"/>
                <owl:versionInfo xml:lang ="en">1.1.0</owl:versionInfo>
               <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description >
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassA">
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                <rdfs:subClassOf rdf:resource="#IdentifiedObject"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#concrete"/>
            </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassA.version">
                <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                <rdfs:domain rdf:resource="#ClassA"/>
                <rdfs:range rdf:resource="https://semver.org#v2_0_0"/>
             </rdf:Description>
        </rdf:RDF>
        """;

    var semVerDataType = new BaseDatatype("https://semver.org#v2_0_0");
    var typeMapper = TypeMapper.getInstance();
    try {
      typeMapper.registerDatatype(semVerDataType);

      final var parser = new ReaderCIMXML_StAX_SR();
      final var streamRDF = new StreamCimXmlToDatasetGraph();

      parser.read(new StringReader(rdfxml), streamRDF);

      var graph = streamRDF.getCimDatasetGraph().getDefaultGraph();

      var profile = CimProfile.wrap(graph);

      var registry = new CimProfileRegistryStd();
      registry.register(profile);

      var owlVersionIRIs = Set.of(NodeFactory.createURI("http://example.org/MyCustom/1/1"));

      assertTrue(registry.containsProfile(owlVersionIRIs));

      assertTrue(registry.getRegisteredProfiles().contains(profile));
      assertEquals(1, registry.getRegisteredProfiles().size());

      var properties = registry.getPropertiesAndDatatypes(owlVersionIRIs);
      assertNotNull(properties);
      assertEquals(1, properties.size());

      var versionProperty = NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.version");
      assertTrue(properties.containsKey(versionProperty));
      var propertyInfo = properties.get(versionProperty);
      assertEquals(NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA"),
          propertyInfo.rdfType());
      assertEquals(versionProperty, propertyInfo.property());
      assertEquals(semVerDataType, propertyInfo.primitiveType());
      assertNull(propertyInfo.referenceType());
    } finally {
      typeMapper.unregisterDatatype(semVerDataType);
    }
  }

}