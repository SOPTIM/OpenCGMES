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

package de.soptim.opencgmes.cimxmlcli.model;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import org.apache.jena.riot.RiotException;
import org.junit.Test;

public class CountingErrorHandlerTest {

  @Test
  public void reducesMessagesToTheirKind() {
    assertEquals(
        "CIM UUID without dashes",
        CountingErrorHandler.kindOf(
            "[line: 12, col: 3] CIM UUID without dashes: '550e8400e29b41d4a716446655440000' -"
                + " converted to dashed form."));
    assertEquals(
        "Property '...' could not be found in current profiles",
        CountingErrorHandler.kindOf(
            "Property 'cim:Foo.bar' could not be found in current profiles. Profiles: [x]"));
  }

  @Test
  public void countsWarningsPerKindUntilReset() {
    final var handler = new CountingErrorHandler();
    handler.warning("CIM UUID without dashes: 'a' - converted.", 1, 1);
    handler.warning("CIM UUID without dashes: 'b' - converted.", 2, 1);
    handler.warning("No model header has been found in CIMXML.", -1, -1);

    final var summary = handler.summary();
    assertEquals(3, summary.total());
    assertEquals(
        Map.of("CIM UUID without dashes", 2, "No model header has been found in CIMXML", 1),
        summary.countsByKind());
    assertEquals("CIM UUID without dashes", summary.countsByKind().keySet().iterator().next());

    handler.reset();
    assertEquals(0, handler.summary().total());
  }

  @Test(expected = RiotException.class)
  public void fatalErrorsAbortParsing() {
    new CountingErrorHandler().fatal("broken", 1, 1);
  }
}
