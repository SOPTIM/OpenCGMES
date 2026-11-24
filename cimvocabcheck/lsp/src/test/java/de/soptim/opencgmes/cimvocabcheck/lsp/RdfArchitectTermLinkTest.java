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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public class RdfArchitectTermLinkTest {

  private static final String TERM = "http://iec.ch/TC57/CIM100#ACLineSegment";

  @Test
  public void carriesTheDirectiveTheEditorsDecode() {
    var target =
        new RdfArchitectTermLink.Target(
            "http://localhost:3000", "cgmes-3.0", "EQ profile.rdf", "CoreEquipment-EU");
    URI link = URI.create(RdfArchitectTermLink.uriFor(TERM, target));

    assertEquals("rdfarchitect", link.getScheme());
    String[] segments = link.getPath().split("/");
    String directive =
        new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
    assertEquals(RdfArchitectTermLink.directive(TERM, target), directive);
    // Percent-encoded inside the payload, because a graph is named after the file it came from.
    assertTrue(directive.contains("graph=EQ%20profile.rdf"));
    assertEquals("CoreEquipment-EU", segments[2]);
    assertEquals("ACLineSegment", segments[3]);
  }

  @Test
  public void survivesBeingPercentDecoded() {
    // LSP4IJ decodes a location URI before resolving it; the payload must come through unchanged.
    var target = new RdfArchitectTermLink.Target(null, null, "a b%c", "EQ");
    String link = RdfArchitectTermLink.uriFor(TERM, target);
    assertFalse("nothing in the link may be percent-encoded: " + link, link.contains("%"));
  }

  @Test
  public void leavesOutWhatIsNotKnown() {
    var target = new RdfArchitectTermLink.Target(null, null, null, "EQ");
    assertEquals(
        "class=http%3A%2F%2Fiec.ch%2FTC57%2FCIM100%23ACLineSegment",
        RdfArchitectTermLink.directive(TERM, target));
  }
}
