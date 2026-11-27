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

package de.soptim.opencgmes.cimreader.query.dataset;

import de.soptim.opencgmes.cimreader.query.dataset.exception.DatasetNotLoadedException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.sparql.core.DatasetGraph;
import org.springframework.stereotype.Component;

@Component
public class DatasetRepository {

  private final AtomicReference<DatasetGraph> current = new AtomicReference<>();

  public Optional<DatasetGraph> current() {
    return Optional.ofNullable(current.get());
  }

  public DatasetGraph require() throws DatasetNotLoadedException {
    var dataset = current.get();
    if (dataset == null) {
      throw new DatasetNotLoadedException("No dataset loaded");
    }
    return dataset;
  }

  public void publish(DatasetGraph dataset) {
    current.set(dataset);
  }

  public void clear() {
    current.set(null);
  }

  public boolean isLoaded() {
    return current.get() != null;
  }

}
