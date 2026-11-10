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

package de.soptim.opencgmes.cimxmlcli.dataset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimxmlcli.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DatasetAssemblerTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void namesTheGraphsByProfileKeyword() throws IOException {
    final var context =
        TestData.miniContext(tmp.newFolder("rdfs").toPath(), tmp.newFolder("models").toPath());

    final var dataset = new DatasetAssembler().assemble(context);

    final var keywords = List.of("EQ", "EQ_BD", "SSH", "SV", "TP");
    final var expected = new TreeSet<String>();
    for (var keyword : keywords) {
      expected.add("cgmes:schema:" + keyword);
      expected.add("cgmes:header:" + keyword);
      expected.add("cgmes:model:" + keyword);
    }
    expected.add("cgmes:schema:FH");
    assertEquals(expected, TestData.graphSizes(dataset).keySet());
    assertTrue(dataset.getDefaultGraph().isEmpty());
  }

  @Test
  public void separatesHeaderAndBody() throws IOException {
    final var context =
        TestData.miniContext(tmp.newFolder("rdfs").toPath(), tmp.newFolder("models").toPath());

    final var sizes = TestData.graphSizes(new DatasetAssembler().assemble(context));

    // MINI_EQ.xml: md:FullModel with type and one profile; two lines with type, name and r
    assertEquals(Long.valueOf(2), sizes.get("cgmes:header:EQ"));
    assertEquals(Long.valueOf(6), sizes.get("cgmes:model:EQ"));
  }

  @Test
  public void addsAModelWithSeveralProfilesUnderEachKeyword() throws IOException {
    final var models = tmp.newFolder("models").toPath();
    final var rdfs = tmp.newFolder("rdfs").toPath();
    TestData.copyMiniRdfs(rdfs);
    TestData.copyMiniModels(models);
    Files.delete(models.resolve("MINI_TP.xml"));
    // The EQ file additionally declares the TP profile
    TestData.write(
        models.resolve("MINI_EQ.xml"),
        TestData.read("/igm-2415/model/MINI_EQ.xml")
            .replace(
                "</md:Model.profile>",
                "</md:Model.profile>\n    <md:Model.profile>http://entsoe.eu/CIM/Topology/4/1"
                    + "</md:Model.profile>"));
    final var context = TestData.validate(rdfs, models);

    final var sizes = TestData.graphSizes(new DatasetAssembler().assemble(context));

    assertEquals(sizes.get("cgmes:model:EQ"), sizes.get("cgmes:model:TP"));
    assertEquals(sizes.get("cgmes:header:EQ"), sizes.get("cgmes:header:TP"));
    assertTrue(sizes.containsKey("cgmes:schema:TP"));
  }
}
