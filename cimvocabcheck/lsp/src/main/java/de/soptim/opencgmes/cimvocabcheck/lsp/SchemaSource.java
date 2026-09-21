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

import java.nio.file.Path;
import java.util.List;

/**
 * Where a document's schema comes from: a remote SPARQL endpoint URL, one or more local schema
 * files — several {@code # [endpoint=...]} directives and glob patterns form a union of files — or
 * a model held in a running RDFArchitect ({@code # [rdfarchitect=...]}). Exactly one of the three
 * components is set.
 *
 * @param rdfArchitect the RDFArchitect reference as written: a dataset name, or a link naming a
 *     dataset or snapshot — see {@code RdfArchitectSource}
 */
record SchemaSource(String remoteUrl, List<Path> files, String rdfArchitect) {

  static SchemaSource remote(String url) {
    return new SchemaSource(url, null, null);
  }

  static SchemaSource localFiles(List<Path> files) {
    return new SchemaSource(null, List.copyOf(files), null);
  }

  static SchemaSource rdfArchitect(String ref) {
    return new SchemaSource(null, null, ref);
  }

  boolean isRemote() {
    return remoteUrl != null;
  }

  boolean isRdfArchitect() {
    return rdfArchitect != null;
  }
}
