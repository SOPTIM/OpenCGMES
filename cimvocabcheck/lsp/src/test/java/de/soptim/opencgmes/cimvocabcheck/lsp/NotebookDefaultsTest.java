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
import static org.junit.Assert.assertNull;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Covers {@link NotebookDefaults}: the client sets a default on the <em>notebook</em> URI, while
 * every lookup comes from a <em>cell</em> URI — the two must agree on a key, or a notebook's
 * directive-less cells silently fall back to syntax-only validation.
 */
public class NotebookDefaultsTest {

  private static final String NOTEBOOK_URI = "file:///home/u/proj/analysis.cimnb.md";
  private static final String CELL_URI = "vscode-notebook-cell:/home/u/proj/analysis.cimnb.md#W0s";
  private static final String OTHER_CELL_URI = "vscode-notebook-cell:/home/u/proj/other.cimnb.md#A";

  @Test
  public void cellsOfTheNotebookSeeItsDefault() {
    NotebookDefaults defaults = new NotebookDefaults();

    defaults.set(NOTEBOOK_URI, "./schema.ttl");

    assertEquals("./schema.ttl", defaults.forCell(CELL_URI));
    assertNull("a cell of another notebook is unaffected", defaults.forCell(OTHER_CELL_URI));
  }

  @Test
  public void blankEndpointClearsTheDefault() {
    NotebookDefaults defaults = new NotebookDefaults();
    defaults.set(NOTEBOOK_URI, "http://localhost:3030/ds/query");

    defaults.set(NOTEBOOK_URI, null);

    assertNull(defaults.forCell(CELL_URI));
  }

  @Test
  public void unsavedNotebooksKeyOnTheirOpaqueUri() {
    NotebookDefaults defaults = new NotebookDefaults();

    defaults.set("untitled:Untitled-1", "http://localhost:3030/ds/query");

    assertEquals(
        "http://localhost:3030/ds/query",
        defaults.forCell("vscode-notebook-cell:Untitled-1#W0sZmlsZQ"));
  }

  @Test
  public void applyParsesTheCommandArgumentAndNotifies() {
    NotebookDefaults defaults = new NotebookDefaults();
    AtomicInteger changes = new AtomicInteger();
    defaults.addOnChangeCallback(changes::incrementAndGet);

    defaults.apply(
        List.of(
            JsonParser.parseString(
                """
                { "notebookUri": "%s", "endpoint": "local-fuseki" }
                """
                    .formatted(NOTEBOOK_URI))));

    assertEquals("local-fuseki", defaults.forCell(CELL_URI));
    assertEquals("open cells must be revalidated against the new default", 1, changes.get());

    // A null endpoint is how the client clears a default.
    defaults.apply(
        List.of(
            JsonParser.parseString(
                """
                { "notebookUri": "%s", "endpoint": null }
                """
                    .formatted(NOTEBOOK_URI))));

    assertNull(defaults.forCell(CELL_URI));
    assertEquals(2, changes.get());
  }

  @Test
  public void malformedArgumentsAreIgnored() {
    NotebookDefaults defaults = new NotebookDefaults();

    defaults.apply(null);
    defaults.apply(List.of());
    defaults.apply(List.of(JsonParser.parseString("\"not-an-object\"")));
    defaults.apply(List.of(JsonParser.parseString("{ \"endpoint\": \"http://x/query\" }")));

    assertNull(defaults.forCell(CELL_URI));
  }
}
