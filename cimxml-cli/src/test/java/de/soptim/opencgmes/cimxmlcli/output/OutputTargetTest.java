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

package de.soptim.opencgmes.cimxmlcli.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import org.junit.Test;

public class OutputTargetTest {

  @Test
  public void derivesTheFormatFromTheExtension() {
    assertEquals(
        new OutputTarget(Path.of("out/a.jsonld"), false), OutputTarget.of(Path.of("out/a.jsonld")));
    assertEquals(
        new OutputTarget(Path.of("a.jsonld.gz"), true), OutputTarget.of(Path.of("a.jsonld.gz")));
    assertEquals(true, OutputTarget.of(Path.of("A.JSONLD.GZ")).gzip());
  }

  @Test
  public void rejectsOtherExtensions() {
    for (var name : new String[] {"a.json", "a.ttl", "a.jsonld.zip", "a.gz", ".jsonld", "jsonld"}) {
      assertThrows(name, IllegalArgumentException.class, () -> OutputTarget.of(Path.of(name)));
    }
  }
}
