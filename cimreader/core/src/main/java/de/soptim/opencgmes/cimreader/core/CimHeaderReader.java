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

package de.soptim.opencgmes.cimreader.core;

import de.soptim.opencgmes.cimreader.core.source.HeaderSource;
import de.soptim.opencgmes.cimreader.core.source.XmlSource;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CimHeaderReader {

  public List<HeaderSource> readHeaders(List<XmlSource> sources) {
    List<HeaderSource> result = new ArrayList<>();
    CimXmlParser parser = new CimXmlParser();
    for (XmlSource source : sources) {
      try {
        var inputStream = source.open();
        result.add(new HeaderSource(parser.parseCimModelHeader(inputStream), source));
        inputStream.close();
      } catch (IOException ignored) {
      }
    }
    return result;
  }
  
}
