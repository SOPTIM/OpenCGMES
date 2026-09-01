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

import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class EndpointDirectiveTest {

  @Test
  public void parsesRemoteEndpoint() {
    String text = "# [endpoint=https://lindas.admin.ch/query]\nSELECT * WHERE { ?s ?p ?o }";
    assertEquals(Optional.of("https://lindas.admin.ch/query"), EndpointDirective.parse(text));
  }

  @Test
  public void parsesRelativeFileEndpoint() {
    String text = "# [endpoint=./relative/path/file.ttl]\nSELECT * WHERE { ?s ?p ?o }";
    assertEquals(Optional.of("./relative/path/file.ttl"), EndpointDirective.parse(text));
  }

  @Test
  public void parsesDirectiveWithNoSpaceAfterHash() {
    // SPARQL Notebook samples use both "# [endpoint=...]" and "#[endpoint=...]".
    String text = "#[endpoint=https://int.lindas.admin.ch/query]\nSELECT * {}";
    assertEquals(Optional.of("https://int.lindas.admin.ch/query"), EndpointDirective.parse(text));
  }

  @Test
  public void parsesRelativeParentPathEndpoint() {
    String text = "# [endpoint=../deep/deep.ttl]\nSELECT * WHERE { ?s ?p ?o }";
    assertEquals(Optional.of("../deep/deep.ttl"), EndpointDirective.parse(text));
  }

  @Test
  public void toleratesSurroundingWhitespace() {
    String text = "   #   [ endpoint = ./schema.ttl ]   \nASK {}";
    assertEquals(Optional.of("./schema.ttl"), EndpointDirective.parse(text));
  }

  @Test
  public void returnsFirstWhenMultipleDeclared() {
    String text = "# [endpoint=./first.ttl]\n# [endpoint=./second.ttl]\nSELECT * {}";
    assertEquals(Optional.of("./first.ttl"), EndpointDirective.parse(text));
  }

  @Test
  public void findsDirectiveOnLaterLine() {
    String text = "PREFIX cim: <x>\n# [endpoint=./schema.ttl]\nSELECT * {}";
    assertEquals(Optional.of("./schema.ttl"), EndpointDirective.parse(text));
  }

  @Test
  public void absentWhenNoDirective() {
    assertFalse(EndpointDirective.parse("SELECT * WHERE { ?s ?p ?o }").isPresent());
  }

  @Test
  public void absentWhenNotAComment() {
    // Same shape but not a # comment line — must not be treated as a directive.
    assertTrue(EndpointDirective.parse("SELECT * WHERE { [endpoint=x] }").isEmpty());
  }

  @Test
  public void absentForNullText() {
    assertTrue(EndpointDirective.parse(null).isEmpty());
  }

  @Test
  public void parseAllReturnsEveryDirectiveInOrder() {
    String text = "# [endpoint=./first.ttl]\n# [endpoint=./second.ttl]\nSELECT * {}";
    assertEquals(List.of("./first.ttl", "./second.ttl"), EndpointDirective.parseAll(text));
  }

  @Test
  public void parseAllKeepsGlobPatterns() {
    String text = "# [endpoint=./rdf/{a,b}.ttl]\n# [endpoint=./rdf/*.ttl]\nSELECT * {}";
    assertEquals(List.of("./rdf/{a,b}.ttl", "./rdf/*.ttl"), EndpointDirective.parseAll(text));
  }

  @Test
  public void parseAllIsEmptyWithoutDirectives() {
    assertEquals(List.of(), EndpointDirective.parseAll("SELECT * {}"));
    assertEquals(List.of(), EndpointDirective.parseAll(null));
  }
}
