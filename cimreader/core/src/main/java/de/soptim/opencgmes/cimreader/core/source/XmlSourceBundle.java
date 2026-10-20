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

package de.soptim.opencgmes.cimreader.core.source;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class XmlSourceBundle implements AutoCloseable {

  private final List<XmlSource> sources = new ArrayList<>();
  private final List<Closeable> resources = new ArrayList<>();

  public XmlSourceBundle add(Path path) throws IOException {
    XmlSourceSet sourceSet = XmlSources.fromPath(path);
    resources.add(sourceSet);
    sources.addAll(sourceSet.getSources());
    return this;
  }

  public XmlSourceBundle addAll(Collection<Path> paths) throws IOException {
    for (Path path : paths) {
      add(path);
    }
    return this;
  }

  public List<XmlSource> sources() {
    return sources;
  }

  @Override
  public void close() throws Exception {
    Exception exceptions = null;
    for (Closeable resource : resources) {
      try {
        resource.close();
      } catch (Exception e) {
        if (exceptions == null) {
          exceptions = e;
        } else {
          exceptions.addSuppressed(e);
        }
      }
    }
    if (exceptions != null) {
      throw exceptions;
    }
  }
}
