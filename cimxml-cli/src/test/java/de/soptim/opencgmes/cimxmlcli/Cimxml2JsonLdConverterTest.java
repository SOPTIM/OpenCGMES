/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimxmlcli;

import static de.soptim.opencgmes.cimxmlcli.TestData.copyMiniModels;
import static de.soptim.opencgmes.cimxmlcli.TestData.copyMiniRdfs;
import static de.soptim.opencgmes.cimxmlcli.TestData.entries;
import static de.soptim.opencgmes.cimxmlcli.TestData.read;
import static de.soptim.opencgmes.cimxmlcli.TestData.write;
import static de.soptim.opencgmes.cimxmlcli.TestData.zip;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxmlcli.model.ParsedModel;
import de.soptim.opencgmes.cimxmlcli.progress.ProgressListener;
import de.soptim.opencgmes.cimxmlcli.validation.ConversionContext;
import de.soptim.opencgmes.cimxmlcli.validation.ConversionException;
import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Cimxml2JsonLdConverterTest {

  private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
  private static final String EQ_IRI = "http://entsoe.eu/CIM/EquipmentCore/3/1";
  private static final String TP_IRI = "http://entsoe.eu/CIM/Topology/4/1";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path rdfs;
  private Path models;

  @Before
  public void setUp() throws IOException {
    rdfs = copyMiniRdfs(tmp.newFolder("rdfs").toPath());
    models = copyMiniModels(tmp.newFolder("models").toPath());
  }

  private ConversionContext convert() throws ConversionException, IOException {
    return Cimxml2JsonLdConverter.createDefault(false, ProgressListener.NONE)
        .readAndValidate(rdfs, models);
  }

  private List<Problem> problems() throws IOException {
    try {
      convert();
    } catch (ConversionException e) {
      return e.getProblems();
    }
    fail("expected a ConversionException");
    return List.of();
  }

  private static Problem single(List<Problem> problems) {
    assertEquals(problems.toString(), 1, problems.size());
    return problems.getFirst();
  }

  private static String fullModel(String cimNamespace, String... profileIris) {
    final var header = new StringBuilder();
    for (var iri : profileIris) {
      header.append("    <md:Model.profile>").append(iri).append("</md:Model.profile>\n");
    }
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <rdf:RDF xmlns:cim="%s"
        xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
        xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
      <md:FullModel rdf:about="urn:uuid:1b4cf1b5-1a2f-4a1e-9d43-6d2a4b0e4f1e">
    %s  </md:FullModel>
    </rdf:RDF>
    """
        .formatted(cimNamespace, header);
  }

  @Test
  public void acceptsTheMiniIgm() throws Exception {
    final var context = convert();

    assertEquals(6, context.schemas().size());
    assertEquals(
        List.of("EQ", "EQ_BD", "SSH", "SV", "TP"),
        context.models().stream().map(m -> m.keywords().getFirst()).sorted().toList());
    final var eq = context.models().getFirst();
    assertEquals("MINI_EQ.xml", eq.displayPath());
    assertEquals(CIM16, eq.cimNamespace());
    assertEquals(0, eq.warnings().total());
  }

  @Test
  public void runsOnlyOnce() throws Exception {
    final var converter = Cimxml2JsonLdConverter.createDefault(false, ProgressListener.NONE);
    converter.readAndValidate(rdfs, models);

    assertThrows(IllegalStateException.class, () -> converter.readAndValidate(rdfs, models));
  }

  @Test
  public void readsModelsFromNestedZipArchives() throws Exception {
    final var zipped = tmp.newFolder("zipped").toPath();
    final var inner =
        zip(
            entries(
                "MINI_SSH.xml",
                read("/igm-2415/model/MINI_SSH.xml"),
                "MINI_SV.xml",
                read("/igm-2415/model/MINI_SV.xml")));
    write(
        zipped.resolve("igm.zip"),
        zip(
            entries(
                "MINI_EQ.xml",
                read("/igm-2415/model/MINI_EQ.xml"),
                "nested/inner.zip",
                inner,
                "readme.txt",
                "ignored")));
    models = zipped;

    assertEquals(
        List.of(
            "igm.zip!/MINI_EQ.xml",
            "igm.zip!/nested/inner.zip!/MINI_SSH.xml",
            "igm.zip!/nested/inner.zip!/MINI_SV.xml"),
        convert().models().stream().map(ParsedModel::displayPath).toList());
  }

  @Test
  public void rejectsTwoModelsForTheSameProfile() throws Exception {
    Files.copy(
        models.resolve("MINI_EQ.xml"),
        Files.createDirectories(models.resolve("other")).resolve("EQ2.xml"));

    final var problem = single(problems());
    assertEquals("cgmes:model:EQ", problem.location());
    assertTrue(problem.message(), problem.message().contains("MINI_EQ.xml"));
    assertTrue(problem.message(), problem.message().contains("other/EQ2.xml"));
  }

  @Test
  public void rejectsDifferenceModels() throws Exception {
    write(
        models.resolve("DIFF.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#"
            xmlns:dm="http://iec.ch/TC57/61970-552/DifferenceModel/1#"
            xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <dm:DifferenceModel rdf:about="urn:uuid:5c6e3e5a-0b1e-4c56-8a51-0c4c0c5a3f11">
            <md:Model.profile>http://entsoe.eu/CIM/Topology/4/1</md:Model.profile>
            <dm:forwardDifferences rdf:parseType="Statements"/>
            <dm:reverseDifferences rdf:parseType="Statements"/>
          </dm:DifferenceModel>
        </rdf:RDF>
        """);

    final var problem = single(problems());
    assertEquals("DIFF.xml", problem.location());
    assertTrue(problem.message(), problem.message().contains("DifferenceModel"));
  }

  @Test
  public void rejectsModelsWithoutHeader() throws Exception {
    write(
        models.resolve("NOHEADER.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#"
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <cim:TopologicalNode rdf:ID="_6f1c2a3e-63a5-4a8e-9d55-4b7f2b8c9e01"/>
        </rdf:RDF>
        """);

    final var problem = single(problems());
    assertEquals("NOHEADER.xml", problem.location());
    assertTrue(problem.message(), problem.message().contains("no md:FullModel header"));
  }

  @Test
  public void rejectsModelsWithoutMatchingSchema() throws Exception {
    Files.delete(rdfs.resolve("EquipmentProfile.rdf"));

    final var problem = single(problems());
    assertEquals("MINI_EQ.xml", problem.location());
    assertTrue(problem.message(), problem.message().contains(EQ_IRI));
    assertTrue(problem.hint(), problem.hint().contains("schema folder"));
  }

  @Test
  public void acceptsModelsDeclaringSeveralProfiles() throws Exception {
    // Like CGMES 3.0 EQ files that also declare Operation and ShortCircuit
    write(models.resolve("MINI_EQ.xml"), fullModel(CIM16, EQ_IRI, TP_IRI));
    Files.delete(models.resolve("MINI_TP.xml"));

    final var eq =
        convert().convertibleModels().stream()
            .filter(m -> m.displayPath().equals("MINI_EQ.xml"))
            .findFirst()
            .orElseThrow();

    assertEquals(List.of("EQ", "TP"), eq.keywords());
  }

  @Test
  public void rejectsASeparateFileForAProfileThatIsAlreadyDeclared() throws Exception {
    write(models.resolve("MINI_EQ.xml"), fullModel(CIM16, EQ_IRI, TP_IRI));

    final var problem = single(problems());
    assertEquals("cgmes:model:TP", problem.location());
    assertTrue(problem.message(), problem.message().contains("[MINI_EQ.xml, MINI_TP.xml]"));
  }

  @Test
  public void rejectsModelsOfDifferentCimVersions() throws Exception {
    write(
        rdfs.resolve("Custom30.rdf"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#"
            xmlns:dcat="http://www.w3.org/ns/dcat#"
            xmlns:owl="http://www.w3.org/2002/07/owl#"
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="http://example.org/Custom#Ontology">
            <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Ontology"/>
            <dcat:keyword>CUSTOM</dcat:keyword>
            <owl:versionIRI rdf:resource="http://example.org/Custom/1/0"/>
          </rdf:Description>
        </rdf:RDF>
        """);
    write(
        models.resolve("CUSTOM.xml"),
        fullModel("http://iec.ch/TC57/CIM100#", "http://example.org/Custom/1/0"));

    final var problem = single(problems());
    assertTrue(problem.message(), problem.message().contains("CGMES 2.4.15"));
    assertTrue(
        problem.message(),
        problem.message().contains("CGMES 3.0 (http://iec.ch/TC57/CIM100#) [CUSTOM.xml]"));
  }

  @Test
  public void reportsBrokenFilesAndKeepsChecking() throws Exception {
    write(
        models.resolve("BROKEN.xml"),
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">");
    Files.delete(rdfs.resolve("TopologyProfile.rdf"));

    final var problems = problems();
    assertEquals(problems.toString(), 2, problems.size());
    assertEquals("BROKEN.xml", problems.get(0).location());
    assertTrue(
        problems.get(0).message(), problems.get(0).message().startsWith("cannot parse CIMXML"));
    assertEquals("MINI_TP.xml", problems.get(1).location());
  }

  @Test
  public void rejectsASecondFileHeaderProfile() throws Exception {
    Files.copy(rdfs.resolve("FileHeader.rdf"), rdfs.resolve("FileHeader2.rdf"));

    final var problem = single(problems());
    assertEquals("FileHeader2.rdf", problem.location());
    assertTrue(problem.message(), problem.message().contains("second file header (FH) profile"));
    assertEquals("Remove one of: FileHeader.rdf, FileHeader2.rdf", problem.hint());
  }

  private static String withoutClass(String rdfs, String className) {
    return rdfs.replaceAll(
        "(?s)  <rdf:Description rdf:about=\"#" + className + "\">.*?</rdf:Description>\n", "");
  }

  private static boolean definesClass(CimProfile profile, String className) {
    return profile.contains(
        NodeFactory.createURI(CIM16 + className), RDF.type.asNode(), RDFS.Class.asNode());
  }

  @Test
  public void mergesVariantsOfOneProfile() throws Exception {
    // Like the CGMES 2.4.15 EQ variants: same keyword and version IRIs, fewer classes
    final var full = read("/igm-2415/rdfs/EquipmentProfile.rdf");
    write(rdfs.resolve("AEquipmentCore.rdf"), withoutClass(full, "ACLineSegment"));

    final var context = convert();

    assertEquals(6, context.schemas().size());
    final var warning = single(context.warnings());
    assertEquals("AEquipmentCore.rdf + EquipmentProfile.rdf", warning.location());
    assertTrue(warning.message(), warning.message().contains("merged into one profile"));
    final var eq =
        context.schemas().stream()
            .filter(s -> warning.location().equals(s.displayPath()))
            .findFirst()
            .orElseThrow();
    assertTrue(definesClass(eq.profile(), "ACLineSegment"));
  }

  @Test
  public void mergesVariantsThatComplementEachOther() throws Exception {
    // Like Core+Operation and Core+ShortCircuit without the variant containing both
    final var full = read("/igm-2415/rdfs/EquipmentProfile.rdf");
    Files.delete(rdfs.resolve("EquipmentProfile.rdf"));
    write(rdfs.resolve("EqWithoutLine.rdf"), withoutClass(full, "ACLineSegment"));
    write(rdfs.resolve("EqWithoutIdentifiedObject.rdf"), withoutClass(full, "IdentifiedObject"));

    final var context = convert();

    final var eq =
        context.schemas().stream()
            .filter(s -> s.displayPath().contains(" + "))
            .findFirst()
            .orElseThrow();
    assertTrue(definesClass(eq.profile(), "ACLineSegment"));
    assertTrue(definesClass(eq.profile(), "IdentifiedObject"));
  }

  @Test
  public void mergesIdenticalProfiles() throws Exception {
    write(
        rdfs.resolve("copies.zip"),
        zip(entries("EQ.rdf", read("/igm-2415/rdfs/EquipmentProfile.rdf"))));

    final var warning = single(convert().warnings());
    assertEquals("EquipmentProfile.rdf + copies.zip!/EQ.rdf", warning.location());
  }

  @Test
  public void rejectsContradictingVariants() throws Exception {
    final var full = read("/igm-2415/rdfs/EquipmentProfile.rdf");
    write(
        rdfs.resolve("Contradicting.rdf"),
        full.replace(
            "<rdfs:domain rdf:resource=\"#ACLineSegment\"/>\n"
                + "    <cims:dataType rdf:resource=\"#Float\"/>",
            "<rdfs:domain rdf:resource=\"#ACLineSegment\"/>\n"
                + "    <cims:dataType rdf:resource=\"#String\"/>"));

    final var problem = single(problems());
    assertEquals("EquipmentProfile.rdf", problem.location());
    assertTrue(problem.message(), problem.message().startsWith("ACLineSegment.r has dataType"));
    assertTrue(problem.message(), problem.message().contains("in Contradicting.rdf"));
  }

  @Test
  public void rejectsProfilesWithTheSameVersionIrisButDifferentKeywords() throws Exception {
    write(
        rdfs.resolve("Other.rdf"),
        read("/igm-2415/rdfs/EquipmentProfile.rdf")
            .replace(">EQ</cims:isFixed>", ">EQX</cims:isFixed>"));

    final var problem = single(problems());
    assertEquals("Other.rdf", problem.location());
    assertTrue(problem.message(), problem.message().contains("is defined twice"));
    assertEquals("Remove one of: EquipmentProfile.rdf, Other.rdf", problem.hint());
  }

  @Test
  public void rejectsFilesThatAreNoProfiles() throws Exception {
    write(
        rdfs.resolve("NotAProfile.rdf"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:ex="http://example.org/">
          <rdf:Description rdf:about="http://example.org/a"><ex:p>x</ex:p></rdf:Description>
        </rdf:RDF>
        """);

    final var problem = single(problems());
    assertEquals("NotAProfile.rdf", problem.location());
    assertTrue(problem.message(), problem.message().startsWith("not a CGMES RDFS profile"));
  }

  @Test
  public void reportsEmptyFolders() throws Exception {
    models = tmp.newFolder("empty").toPath();

    final var problem = single(problems());
    assertEquals(models.toString(), problem.location());
    assertTrue(problem.message(), problem.message().contains("no .xml files"));
  }
}
