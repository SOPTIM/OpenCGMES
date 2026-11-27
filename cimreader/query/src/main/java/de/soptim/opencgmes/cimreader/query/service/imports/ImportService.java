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

package de.soptim.opencgmes.cimreader.query.service.imports;

import de.soptim.opencgmes.cimreader.core.CimImporter;
import de.soptim.opencgmes.cimreader.core.exception.ImportException;
import de.soptim.opencgmes.cimreader.core.loader.FirstGraphLoadStrategy;
import de.soptim.opencgmes.cimreader.core.loader.LastGraphLoadStrategy;
import de.soptim.opencgmes.cimreader.core.loader.LoadStrategy;
import de.soptim.opencgmes.cimreader.core.loader.MiddleGraphLoadStrategy;
import de.soptim.opencgmes.cimreader.core.loader.RandomGraphLoadStrategy;
import de.soptim.opencgmes.cimreader.query.dataset.DatasetRepository;
import de.soptim.opencgmes.cimreader.query.dataset.exception.ConfigurationException;
import de.soptim.opencgmes.cimreader.query.dataset.exception.ImportInProgressException;
import de.soptim.opencgmes.cimreader.query.dataset.exception.NoContentException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService implements ImportXmlUseCase {

  private static final DateTimeFormatter RUN_DIR_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
  private static final String PROCESSING = "processing";
  private static final String DONE = "done";
  private static final String FAILED = "failed";

  private final DatasetRepository datasetRepository;
  private final ReentrantLock importLock = new ReentrantLock();

  @Value("${query.import.path}")
  private Path basePath;
  @Value("${query.import.load-strategy}")
  private LoadStrategyType loadStrategyType;

  @Override
  public void importCimXml() throws ImportException {
    if (!importLock.tryLock()) {
      throw new ImportInProgressException("Another import is already running");
    }
    try {
      runImport();
    } finally {
      importLock.unlock();
    }
  }

  private void runImport() throws ImportException {
    if (!Files.isDirectory(basePath)) {
      throw new ConfigurationException("The provided path is not a directory: " + basePath);
    }
    var files = listFiles(basePath);
    if (files.isEmpty()) {
      throw new NoContentException("No files found in the import directory: " + basePath);
    }

    var runId = LocalDateTime.now().format(RUN_DIR_FORMAT);
    Path processingDir;
    try {
      processingDir = createRunDir(basePath.resolve(PROCESSING), runId);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    try {
      var stagedFiles = moveAllTo(files, processingDir);
      var dataset = new CimImporter().importCim(stagedFiles, loadStrategyType.create());
      moveDir(processingDir, basePath.resolve(DONE), runId);
      datasetRepository.publish(dataset);
      log.info("Import {} finished: {} file(s) loaded", runId, stagedFiles.size());
    } catch (IOException e) {
      moveToFailed(processingDir, runId, e);
      throw new UncheckedIOException(e);
    } catch (ImportException | RuntimeException e) {
      moveToFailed(processingDir, runId, e);
      throw e;
    }
  }

  private static List<Path> listFiles(Path dir) {
    try (var stream = Files.list(dir)) {
      return stream.filter(Files::isRegularFile).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path createRunDir(Path parent, String runId) throws IOException {
    Files.createDirectories(parent);
    return Files.createDirectory(parent.resolve(runId));
  }

  private static List<Path> moveAllTo(List<Path> files, Path targetDir) throws IOException {
    var moved = new ArrayList<Path>(files.size());
    for (var file : files) {
      moved.add(Files.move(file, targetDir.resolve(file.getFileName()),
          StandardCopyOption.ATOMIC_MOVE));
    }
    return moved;
  }

  private static void moveDir(Path dir, Path targetParent, String runId) throws IOException {
    Files.createDirectories(targetParent);
    Files.move(dir, targetParent.resolve(runId), StandardCopyOption.ATOMIC_MOVE);
  }

  private void moveToFailed(Path processingDir, String runId, Exception cause) {
    try {
      moveDir(processingDir, basePath.resolve(FAILED), runId);
      log.warn("Import {} failed, files moved to {}", runId, basePath.resolve(FAILED), cause);
    } catch (IOException e) {
      cause.addSuppressed(e);
      log.error("Import {} failed and files could not be moved to {}", runId, FAILED, e);
    }
  }

  public enum LoadStrategyType {
    FIRST_GRAPH(FirstGraphLoadStrategy::new),
    LAST_GRAPH(LastGraphLoadStrategy::new),
    MIDDLE_GRAPH(MiddleGraphLoadStrategy::new),
    RANDOM_GRAPH(RandomGraphLoadStrategy::new);

    private final Supplier<LoadStrategy> factory;

    LoadStrategyType(Supplier<LoadStrategy> factory) {
      this.factory = factory;
    }

    LoadStrategy create() {
      return factory.get();
    }
  }

}