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
import de.soptim.opencgmes.cimxmlcli.model.CimXmlModelReader;
import de.soptim.opencgmes.cimxmlcli.model.CountingErrorHandler;
import de.soptim.opencgmes.cimxmlcli.model.ParsedModel;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the conversion: loads and registers all RDFS profiles, parses all CIMXML models
 * sequentially and validates the result. All problems are collected and reported together.
 */
public final class Cimxml2JsonLdConverter {

  private final FileTreeSourceProvider schemaSources;
  private final FileTreeSourceProvider modelSources;
  private final CimXmlSchemaLoader schemaLoader;
  private final CimXmlModelReader modelReader;
  private final List<ValidationRule> rules;

  /** Creates a converter from its parts. */
  public Cimxml2JsonLdConverter(
      FileTreeSourceProvider schemaSources,
      FileTreeSourceProvider modelSources,
      CimXmlSchemaLoader schemaLoader,
      CimXmlModelReader modelReader,
      List<ValidationRule> rules) {
    this.schemaSources = schemaSources;
    this.modelSources = modelSources;
    this.schemaLoader = schemaLoader;
    this.modelReader = modelReader;
    this.rules = List.copyOf(rules);
  }

  /** Creates a converter for {@code .rdf} schema files and {@code .xml} model files. */
  public static Cimxml2JsonLdConverter createDefault() {
    final var warnings = new CountingErrorHandler();
    final var parser = new CimXmlParser(warnings);
    return new Cimxml2JsonLdConverter(
        new FileTreeSourceProvider(".rdf"),
        new FileTreeSourceProvider(".xml"),
        new CimXmlSchemaLoader(new RdfXmlParser(), parser.getCimProfileRegistry()),
        new CimXmlModelReader(parser, warnings),
        defaultRules());
  }

  /** Returns the validation rules applied by {@link #createDefault()}. */
  public static List<ValidationRule> defaultRules() {
    return List.of(
        new FullModelOnlyRule(),
        new SchemaAvailableRule(),
        new SingleCimVersionRule(),
        new SingleSourcePerGraphRule());
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
    readAll(schemaSources, schemaFolder, ".rdf", problems, s -> loaded.add(schemaLoader.load(s)));
    final var registration = schemaLoader.registerAll(loaded);
    problems.addAll(registration.problems());
    // Parsed after registration: the parser needs the profiles
    final var models = new ArrayList<ParsedModel>();
    readAll(modelSources, modelFolder, ".xml", problems, s -> models.add(modelReader.read(s)));

    final var context =
        new ConversionContext(registration.registered(), models, registration.warnings());
    // Run even after read errors, to report all problems at once
    for (var rule : rules) {
      problems.addAll(rule.check(context));
    }
    if (!problems.isEmpty()) {
      throw new ConversionException(problems, context.warnings());
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
          handler.handle(sources.next());
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

  /** Handles one source; may fail for this source only. */
  @FunctionalInterface
  private interface SourceHandler {
    void handle(CimSource source) throws SourceException;
  }
}
