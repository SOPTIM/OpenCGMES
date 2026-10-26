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

import de.soptim.opencgmes.cimreader.core.exception.ImportException;
import de.soptim.opencgmes.cimreader.core.exception.InvalidImportException;
import de.soptim.opencgmes.cimreader.core.source.XmlSourceBundle;
import de.soptim.opencgmes.cimreader.core.validation.CimValidator;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;

public class CimImporter {

  private final CimHeaderReader cimHeaderReader;

  public CimImporter() {
    this.cimHeaderReader = new CimHeaderReader();
  }

  public void importCim(Collection<Path> paths) {
    try (var sourceBundle = new XmlSourceBundle()) {
      sourceBundle.addAll(paths);
      var validator = new CimValidator();
      var xmlSources = sourceBundle.sources();
      var headers = cimHeaderReader.readHeaders(xmlSources);
      validator.validateHeaders(headers);
      validator.validateDependencies(headers);
      if (validator.hasErrors()) {
        throw new InvalidImportException(validator);
      }
      var sorted = CimSorter.sort(headers);
      CimXmlParser parser = new CimXmlParser();
      for (var source : sorted) {
        for (var headerSource : source) {
          InputStream inputStream = headerSource.xmlSource().open();
          var model = parser.parseCimModel(inputStream);
          inputStream.close();
        }
      }
    } catch (InvalidImportException e) {
      throw e;
    } catch (Exception e) {
      throw new ImportException(e);
    }
  }

}
