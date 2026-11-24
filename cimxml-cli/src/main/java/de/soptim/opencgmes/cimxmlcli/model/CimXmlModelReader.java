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

package de.soptim.opencgmes.cimxmlcli.model;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import de.soptim.opencgmes.cimxmlcli.source.CimSource;
import de.soptim.opencgmes.cimxmlcli.source.SourceException;
import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.atlas.RuntimeIOException;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.RiotException;

/**
 * Parses CIMXML models with the cimxml {@link CimXmlParser} and resolves the profiles referenced by
 * their headers.
 */
public final class CimXmlModelReader {

  private final CimXmlParser parser;
  private final CountingErrorHandler warnings;

  /**
   * Creates a reader. {@code warnings} must be the error handler {@code parser} was created with.
   */
  public CimXmlModelReader(CimXmlParser parser, CountingErrorHandler warnings) {
    this.parser = parser;
    this.warnings = warnings;
  }

  /** Parses the CIMXML model in {@code source}. */
  public ParsedModel read(CimSource source) throws SourceException {
    final var path = source.displayPath();
    // The handler is shared; count the warnings per source
    warnings.reset();
    final CimDatasetGraph dataset;
    try (var in = source.openStream()) {
      dataset = parser.parseCimModel(in);
    } catch (IOException | RiotException | RuntimeIOException | IllegalArgumentException e) {
      throw new SourceException(Problem.of(path, "cannot parse CIMXML: " + e.getMessage()), e);
    }
    // Only FullModels and DifferenceModels have a header
    final Set<Node> headerProfiles =
        dataset.isFullModel() || dataset.isDifferenceModel()
            ? asUris(dataset.getModelHeader().getProfiles())
            : Set.of();
    final Set<CimProfile> profiles =
        headerProfiles.isEmpty()
            ? Set.of()
            : parser.getCimProfileRegistry().getMatchingProfiles(headerProfiles);
    return new ParsedModel(
        path, dataset, headerProfiles, profiles, dataset.prefixes().get("cim"), warnings.summary());
  }

  /**
   * Without a file header (FH) profile the parser keeps the profile IRIs of the header as plain
   * literals; it converts them to IRIs internally, and so do we.
   */
  private static Set<Node> asUris(Set<Node> profiles) {
    return profiles.stream()
        .map(p -> p.isLiteral() ? NodeFactory.createURI(p.getLiteralLexicalForm()) : p)
        .collect(Collectors.toUnmodifiableSet());
  }
}
