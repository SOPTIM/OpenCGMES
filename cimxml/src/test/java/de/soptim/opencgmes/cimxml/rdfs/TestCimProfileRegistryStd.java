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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.parser.ReaderCIMXML_StAX_SR;
import de.soptim.opencgmes.cimxml.parser.system.StreamCimXmlToDatasetGraph;
import java.io.StringReader;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.NodeFactory;
import org.junit.Test;

public class TestCimProfileRegistryStd {

  @Test
  public void registerProfileWithOneClassAndTwoSimpleProperties() {
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
                <cims:dataType rdf:resource="#Float"/>
             </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassA.textProperty">
                <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                <rdfs:domain rdf:resource="#ClassA"/>
                <cims:dataType rdf:resource="#String"/>
            </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#Float">
                <rdfs:label xml:lang="en">Float</rdfs:label>
                <cims:stereotype>Primitive</cims:stereotype>
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
            </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#String">
                <rdfs:label xml:lang="en">String</rdfs:label>
                <cims:stereotype>Primitive</cims:stereotype>
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
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
  public void registerProfileWithMultipleVersionIRIs() {
    final var rdfxml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
           xmlns:cim="http://iec.ch/TC57/CIM100#"
           xmlns:dcat="http://www.w3.org/ns/dcat#"
           xmlns:owl="http://www.w3.org/2002/07/owl#"
           xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           xml:base ="http://iec.ch/TC57/CIM100">
            <!-- ······························································································· -->
            <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                <dcat:keyword>MYCUST</dcat:keyword>
                <owl:versionIRI rdf:resource="http://example.org/MyCustomCore/1/1"/>
                <owl:versionIRI rdf:resource="http://example.org/MyCustomOperation/1/1"/>
               <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description >
        </rdf:RDF>
        """;

    var profile = new RdfXmlParser().parseCimProfile(new StringReader(rdfxml));

    var registry = new CimProfileRegistryStd();
    registry.register(profile);

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/MyCustomCore/1/1"),
          NodeFactory.createURI("http://example.org/MyCustomOperation/1/1"));

      assertTrue(registry.containsProfile(owlVersionIRIs));
      assertNotNull(registry.getPropertiesAndDatatypes(owlVersionIRIs));
    }

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/MyCustomCore/1/1"));

      assertTrue(registry.containsProfile(owlVersionIRIs));
      assertNotNull(registry.getPropertiesAndDatatypes(owlVersionIRIs));
    }

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/MyCustomOperation/1/1"));

      assertTrue(registry.containsProfile(owlVersionIRIs));
      assertNotNull(registry.getPropertiesAndDatatypes(owlVersionIRIs));
    }

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/AnyOtherProfile/1/1"));

      assertFalse(registry.containsProfile(owlVersionIRIs));
      assert (registry.getPropertiesAndDatatypes(owlVersionIRIs).isEmpty());
    }
  }

  @Test
  public void registerProfilesWithSameVersionIris() {
    final var rdfxml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
           xmlns:cim="http://iec.ch/TC57/CIM100#"
           xmlns:dcat="http://www.w3.org/ns/dcat#"
           xmlns:owl="http://www.w3.org/2002/07/owl#"
           xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           xml:base ="http://iec.ch/TC57/CIM100">
            <!-- ······························································································· -->
            <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                <dcat:keyword>MYCUST</dcat:keyword>
                <owl:versionIRI rdf:resource="http://example.org/MyCustom/1/1"/>
               <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description>
        </rdf:RDF>
        """;

    var profileA = new RdfXmlParser().parseCimProfile(new StringReader(rdfxml));
    var profileB = new RdfXmlParser().parseCimProfile(new StringReader(rdfxml));

    var registry = new CimProfileRegistryStd();
    registry.register(profileA);

    assertThrows(IllegalArgumentException.class, () -> registry.register(profileB));
  }

  @Test
  public void registerProfilesWithMultipleSameVersionIris() {
    final var rdfxml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
           xmlns:cim="http://iec.ch/TC57/CIM100#"
           xmlns:dcat="http://www.w3.org/ns/dcat#"
           xmlns:owl="http://www.w3.org/2002/07/owl#"
           xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           xml:base ="http://iec.ch/TC57/CIM100">
            <!-- ······························································································· -->
            <rdf:Description rdf:about="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU#Ontology">
                <dcat:keyword>MYCUST</dcat:keyword>
                <owl:versionIRI rdf:resource="http://example.org/MyCustomCore/1/1"/>
                <owl:versionIRI rdf:resource="http://example.org/MyCustomOperation/1/1"/>
               <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description>
        </rdf:RDF>
        """;

    var profileA = new RdfXmlParser().parseCimProfile(new StringReader(rdfxml));
    var profileB = new RdfXmlParser().parseCimProfile(new StringReader(rdfxml));

    var registry = new CimProfileRegistryStd();
    registry.register(profileA);

    assertThrows(IllegalArgumentException.class, () -> registry.register(profileB));
  }

  @Test
  public void registerProfilesWithSingleAndMultiVersionIrisMixed() {
    final var rdfxmlProfileA = """
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
                <owl:versionIRI rdf:resource="http://example.org/MyCustomCore/1/1"/>
                <owl:versionIRI rdf:resource="http://example.org/MyCustomOperation/1/1"/>
               <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description>
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
                <cims:dataType rdf:resource="#Float"/>
             </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#Float">
                <rdfs:label xml:lang="en">Float</rdfs:label>
                <cims:stereotype>Primitive</cims:stereotype>
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
            </rdf:Description>
        </rdf:RDF>
        """;

    final var rdfxmlProfileB = """
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
                <owl:versionIRI rdf:resource="http://example.org/MyCustomCore/1/1"/>
               <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassB">
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
                <rdfs:subClassOf rdf:resource="#IdentifiedObject"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#concrete"/>
            </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#ClassB.floatProperty">
                <rdf:type rdf:resource="http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"/>
                <cims:stereotype rdf:resource="http://iec.ch/TC57/NonStandard/UML#attribute"/>
                <rdfs:domain rdf:resource="#ClassB"/>
                <cims:dataType rdf:resource="#Float"/>
             </rdf:Description>
            <!-- ······························································································· -->
            <rdf:Description rdf:about="#Float">
                <rdfs:label xml:lang="en">Float</rdfs:label>
                <cims:stereotype>Primitive</cims:stereotype>
                <rdf:type rdf:resource="http://www.w3.org/2000/01/rdf-schema#Class"/>
            </rdf:Description>
        </rdf:RDF>
        """;

    var profileA = new RdfXmlParser().parseCimProfile(new StringReader(rdfxmlProfileA));
    var profileB = new RdfXmlParser().parseCimProfile(new StringReader(rdfxmlProfileB));

    var registry = new CimProfileRegistryStd();
    registry.register(profileA);
    registry.register(profileB);

    //add in different order
    registry = new CimProfileRegistryStd();
    registry.register(profileB);
    registry.register(profileA);

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/MyCustomCore/1/1"),
          NodeFactory.createURI("http://example.org/MyCustomOperation/1/1"));

      assertTrue(registry.containsProfile(owlVersionIRIs));
      var properties = registry.getPropertiesAndDatatypes(owlVersionIRIs);
      assertNotNull(properties);
      assertTrue(properties.containsKey(
          NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.floatProperty")));
    }

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/MyCustomCore/1/1"));

      assertTrue(registry.containsProfile(owlVersionIRIs));
      var properties = registry.getPropertiesAndDatatypes(owlVersionIRIs);
      assertNotNull(properties);
      assertTrue(properties.containsKey(
          NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassB.floatProperty")));
    }

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/MyCustomOperation/1/1"));

      assertTrue(registry.containsProfile(owlVersionIRIs));
      var properties = registry.getPropertiesAndDatatypes(owlVersionIRIs);
      assertNotNull(properties);
      assertTrue(properties.containsKey(
          NodeFactory.createURI("http://iec.ch/TC57/CIM100#ClassA.floatProperty")));
    }

    {
      var owlVersionIRIs = Set.of(
          NodeFactory.createURI("http://example.org/AnyOtherProfile/1/1"));

      assertFalse(registry.containsProfile(owlVersionIRIs));
      assertTrue(registry.getPropertiesAndDatatypes(owlVersionIRIs).isEmpty());
    }
  }

}