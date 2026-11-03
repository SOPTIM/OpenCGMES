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

package de.soptim.opencgmes.cimvocabcheck.cli.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

/** SARIF artifact locations must be valid URIs, whatever the file arguments look like. */
public class SarifFormatterTest {

  @Test
  public void plainRelativePathsStayReadable() {
    assertEquals(
        Map.of("uri", "queries/q.rq", "uriBaseId", "%SRCROOT%"),
        SarifFormatter.artifactLocation("queries/q.rq"));
  }

  @Test
  public void relativePathsArePercentEncoded() {
    Map<String, Object> location = SarifFormatter.artifactLocation("my dir/q 100%#1:x.rq");

    assertEquals("my%20dir/q%20100%25%231%3Ax.rq", location.get("uri"));
    assertEquals("%SRCROOT%", location.get("uriBaseId"));
    assertEquals("my dir/q 100%#1:x.rq", URI.create((String) location.get("uri")).getPath());
  }

  @Test
  public void nonAsciiNamesAreEncodedAsUtf8() {
    assertEquals("%C3%BCbersicht.rq", SarifFormatter.artifactLocation("übersicht.rq").get("uri"));
  }

  @Test
  public void stdinPlaceholderIsAValidRelativeReference() {
    String uri = (String) SarifFormatter.artifactLocation("<stdin>").get("uri");

    assertEquals("%3Cstdin%3E", uri);
    assertFalse(URI.create(uri).isAbsolute());
  }

  @Test
  public void absolutePathsBecomeFileUrisWithoutABase() {
    Path file = Path.of("/tmp/a b/../q.rq").toAbsolutePath();
    Map<String, Object> location = SarifFormatter.artifactLocation(file.toString());

    URI uri = URI.create((String) location.get("uri"));
    assertEquals("file", uri.getScheme());
    assertEquals(file.normalize(), Path.of(uri));
    assertTrue("an absolute URI takes no base", !location.containsKey("uriBaseId"));
  }
}
