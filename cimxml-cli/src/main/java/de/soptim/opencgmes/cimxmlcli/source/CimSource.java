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

package de.soptim.opencgmes.cimxmlcli.source;

import java.io.IOException;
import java.io.InputStream;

/**
 * One input file, either a plain file or an entry inside a (possibly nested) ZIP archive.
 *
 * <p>A source returned by a {@link FileTreeSourceIterator} is only valid until the iterator is
 * advanced, because ZIP entries are read straight from the archive stream.
 */
public interface CimSource {

  /**
   * Returns the path shown to the user, relative to the scanned folder. Entries inside ZIP archives
   * are separated by {@code !/}, e.g. {@code igm.zip!/inner.zip!/EQ.xml}.
   */
  String displayPath();

  /**
   * Opens the content of the source. The caller must close the stream; closing the stream of a ZIP
   * entry does not close the archive.
   */
  InputStream openStream() throws IOException;
}
