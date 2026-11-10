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

package de.soptim.opencgmes.cimxmlcli;

import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxmlcli.dataset.DatasetAssembler;
import de.soptim.opencgmes.cimxmlcli.model.CimXmlModelReader;
import de.soptim.opencgmes.cimxmlcli.model.CountingErrorHandler;
import de.soptim.opencgmes.cimxmlcli.model.ParsedModel;
import de.soptim.opencgmes.cimxmlcli.output.JsonLdDatasetWriter;
import de.soptim.opencgmes.cimxmlcli.output.OutputTarget;
import de.soptim.opencgmes.cimxmlcli.progress.ProgressListener;
import de.soptim.opencgmes.cimxmlcli.schema.CimXmlSchemaLoader;
import de.soptim.opencgmes.cimxmlcli.schema.LoadedSchema;
import de.soptim.opencgmes.cimxmlcli.source.CimSource;
import de.soptim.opencgmes.cimxmlcli.source.FileTreeSourceProvider;
import de.soptim.opencgmes.cimxmlcli.source.SourceException;
import de.soptim.opencgmes.cimxmlcli.validation.ConversionContext;
import de.soptim.opencgmes.cimxmlcli.validation.ConversionException;
import de.soptim.opencgmes.cimxmlcli.validation.FullModelOnlyRule;
import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import de.soptim.opencgmes.cimxmlcli.validation.SchemaAvailableRule;
import de.soptim.opencgmes.cimxmlcli.validation.SingleCimVersionRule;
import de.soptim.opencgmes.cimxmlcli.validation.SingleSourcePerGraphRule;
import de.soptim.opencgmes.cimxmlcli.validation.ValidationRule;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Graph;

/**
 * Orchestrates the conversion: loads and registers all RDFS profiles, parses all CIMXML models
 * sequentially, validates the result and writes the assembled dataset. All problems are collected
 * and reported together.
 */
public final class Cimxml2JsonLdConverter {

  private final FileTreeSourceProvider schemaSources;
  private final FileTreeSourceProvider modelSources;
  private final CimXmlSchemaLoader schemaLoader;
  private final CimXmlModelReader modelReader;
  private final List<ValidationRule> rules;
  private final JsonLdDatasetWriter writer;
  private final ProgressListener progress;

  /** Creates a converter from its parts. */
  public Cimxml2JsonLdConverter(
      FileTreeSourceProvider schemaSources,
      FileTreeSourceProvider modelSources,
      CimXmlSchemaLoader schemaLoader,
      CimXmlModelReader modelReader,
      List<ValidationRule> rules,
      JsonLdDatasetWriter writer,
      ProgressListener progress) {
    this.schemaSources = schemaSources;
    this.modelSources = modelSources;
    this.schemaLoader = schemaLoader;
    this.modelReader = modelReader;
    this.rules = List.copyOf(rules);
    this.writer = writer;
    this.progress = progress;
  }

  /**
   * Creates a converter for {@code .rdf} schema files and {@code .xml} model files that writes
   * expanded or, with {@code compact}, compacted JSON-LD.
   */
  public static Cimxml2JsonLdConverter createDefault(boolean compact, ProgressListener progress) {
    final var warnings = new CountingErrorHandler();
    final var parser = new CimXmlParser(warnings);
    return new Cimxml2JsonLdConverter(
        new FileTreeSourceProvider(".rdf"),
        new FileTreeSourceProvider(".xml"),
        new CimXmlSchemaLoader(new RdfXmlParser(), parser.getCimProfileRegistry()),
        new CimXmlModelReader(parser, warnings),
        defaultRules(),
        new JsonLdDatasetWriter(compact),
        progress);
  }

  /** Returns the validation rules applied by {@link #createDefault}. */
  public static List<ValidationRule> defaultRules() {
    return List.of(
        new FullModelOnlyRule(),
        new SchemaAvailableRule(),
        new SingleCimVersionRule(),
        new SingleSourcePerGraphRule());
  }

  /**
   * Reads and validates both folders, then writes the dataset to {@code target}.
   *
   * @return the validated input that was written
   * @throws ConversionException if any problem was found; nothing is written then
   * @throws IOException if a folder cannot be listed or the output cannot be written
   */
  public ConversionContext convert(Path schemaFolder, Path modelFolder, OutputTarget target)
      throws ConversionException, IOException {
    final var context = readAndValidate(schemaFolder, modelFolder);
    final var dataset = new DatasetAssembler().assemble(context);
    progress.writingStarted(target.path());
    final var start = System.nanoTime();
    final var bytes = writer.write(dataset, target);
    final var quads = dataset.getGraphs().stream().mapToLong(Graph::size).sum();
    progress.writingFinished(
        target.path(), quads, bytes, Duration.ofNanos(System.nanoTime() - start));
    return context;
  }

  /**
   * Reads both folders and validates their content.
   *
   * @throws ConversionException if any problem was found
   * @throws IOException if a folder cannot be listed
   */
  public ConversionContext readAndValidate(Path schemaFolder, Path modelFolder)
      throws ConversionException, IOException {
    final var problems = new ArrayList<Problem>();
    // Parse all RDFS before registering, so duplicates can be resolved
    final var loaded = new ArrayList<LoadedSchema>();
    progress.schemaReadingStarted(schemaFolder);
    readAll(
        schemaSources,
        schemaFolder,
        ".rdf",
        problems,
        (index, source) -> {
          final var schema = schemaLoader.load(source);
          loaded.add(schema);
          progress.schemaLoaded(index, schema);
        });
    final var registration = schemaLoader.registerAll(loaded);
    problems.addAll(registration.problems());
    registration.warnings().forEach(progress::warning);
    // Parsed after registration: the parser needs the profiles
    final var models = new ArrayList<ParsedModel>();
    progress.modelReadingStarted(modelFolder);
    readAll(
        modelSources,
        modelFolder,
        ".xml",
        problems,
        (index, source) -> {
          final var start = System.nanoTime();
          final var model = modelReader.read(source);
          models.add(model);
          progress.modelParsed(index, model, Duration.ofNanos(System.nanoTime() - start));
        });

    final var context =
        new ConversionContext(registration.registered(), models, registration.warnings());
    // Run even after read errors, to report all problems at once
    for (var rule : rules) {
      problems.addAll(rule.check(context));
    }
    if (!problems.isEmpty()) {
      throw new ConversionException(problems);
    }
    return context;
  }

  private static void readAll(
      FileTreeSourceProvider provider,
      Path folder,
      String extension,
      List<Problem> problems,
      SourceHandler handler)
      throws IOException {
    var found = 0;
    try (var sources = provider.open(folder)) {
      while (sources.hasNext()) {
        found++;
        try {
          handler.handle(found, sources.next());
        } catch (SourceException e) {
          // Only this source failed; continue with the next one
          problems.add(e.getProblem());
        }
      }
      // Unreadable or too deeply nested ZIP archives
      problems.addAll(sources.problems());
    }
    if (found == 0) {
      problems.add(
          Problem.of(
              folder.toString(), "contains no " + extension + " files (ZIP archives included)"));
    }
  }

  /** Handles the {@code index}-th source (1-based); may fail for this source only. */
  @FunctionalInterface
  private interface SourceHandler {
    void handle(int index, CimSource source) throws SourceException;
  }
}
