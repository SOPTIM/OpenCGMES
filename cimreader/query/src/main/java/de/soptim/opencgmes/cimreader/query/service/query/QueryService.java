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

package de.soptim.opencgmes.cimreader.query.service.query;

import de.soptim.opencgmes.cimreader.core.loader.BaseGraphLoadStrategy;
import de.soptim.opencgmes.cimreader.query.dataset.DatasetRepository;
import de.soptim.opencgmes.cimreader.query.dataset.exception.DatasetNotLoadedException;
import de.soptim.opencgmes.cimxml.CimHeaderVocabulary;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.resultset.ResultSetLang;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.resultset.ResultsWriter;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService implements QueryResultUseCase {

  private final DatasetRepository datasetRepository;

  @Value("${query.execution.timeout:30s}")
  private Duration timeout;

  @Value("${query.execution.max-results:10000}")
  private long maxResults;

  private record GraphAtTime(Node graph, Instant scenarioTime) {}

  @Override
  public QueryResult getQueryResult(String tso, String scenarioTime, String sparql)
      throws DatasetNotLoadedException {
    requireNonBlank(tso, "tso");
    requireNonBlank(scenarioTime, "scenarioTime");
    var query = parseAndValidate(sparql);
    var requestedTime = parseTime(scenarioTime);

    var fullDataset = datasetRepository.require();
    var scenarioDataset = buildScenarioDataset(fullDataset, tso, requestedTime);
    return execute(query, scenarioDataset);
  }

  private static DatasetGraph buildScenarioDataset(
      DatasetGraph fullDataset, String tso, Instant requestedTime) {
    var header = fullDataset.getGraph(BaseGraphLoadStrategy.HEADER_GRAPH);
    var scenarioDataset = DatasetGraphFactory.createGeneral();
    for (var graphs : findGraphsByProfile(header, tso).values()) {
      findLatestGraphAt(header, graphs, requestedTime)
          .ifPresent(graph -> scenarioDataset.addGraph(graph, fullDataset.getGraph(graph)));
    }
    return scenarioDataset;
  }

  private static Map<Node, List<Node>> findGraphsByProfile(Graph header, String tso) {
    var tsoLiteral = NodeFactory.createLiteralString(tso);
    var graphs = header
        .find(Node.ANY, CimHeaderVocabulary.PREDICATE_MODELING_AUTHORITY_SET, tsoLiteral)
        .mapWith(Triple::getSubject)
        .toList();

    Map<Node, List<Node>> graphsByProfile = new HashMap<>();
    for (var graph : graphs) {
      var profiles = header.find(graph, CimHeaderVocabulary.PREDICATE_PROFILE, Node.ANY)
          .mapWith(Triple::getObject)
          .toList();
      for (var profile : profiles) {
        graphsByProfile.computeIfAbsent(profile, _ -> new ArrayList<>()).add(graph);
      }
    }
    return graphsByProfile;
  }

  private static Optional<Node> findLatestGraphAt(
      Graph header, List<Node> graphs, Instant requestedTime) {
    return graphs.stream()
        .flatMap(graph -> getScenarioTime(header, graph)
            .map(time -> new GraphAtTime(graph, time))
            .stream())
        .filter(candidate -> !candidate.scenarioTime().isAfter(requestedTime))
        // Tie-breaker on the graph name keeps the selection deterministic.
        .max(Comparator.comparing(GraphAtTime::scenarioTime)
            .thenComparing(candidate -> candidate.graph().toString()))
        .map(GraphAtTime::graph);
  }

  private QueryResult execute(Query query, DatasetGraph dataset) {
    limitResults(query);
    try (QueryExec exec = QueryExec.dataset(dataset)
        .query(query)
        .set(ARQ.httpServiceAllowed, false)
        .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()) {
      var out = new ByteArrayOutputStream();
      var jsonWriter = ResultsWriter.create().lang(ResultSetLang.RS_JSON).build();
      var lang = switch (query.queryType()) {
        case SELECT -> { jsonWriter.write(out, exec.select()); yield ResultSetLang.RS_JSON; }
        case ASK -> { jsonWriter.write(out, exec.ask()); yield ResultSetLang.RS_JSON; }
        case CONSTRUCT -> { RDFDataMgr.write(out, exec.construct(), Lang.TURTLE); yield Lang.TURTLE; }
        case DESCRIBE -> { RDFDataMgr.write(out, exec.describe(), Lang.TURTLE); yield Lang.TURTLE; }
        default -> throw new IllegalArgumentException("Unsupported query type: " + query.queryType());
      };
      return new QueryResult(out.toString(StandardCharsets.UTF_8), lang.getHeaderString());
    }
  }

  private void limitResults(Query query) {
    if (!query.hasLimit() || query.getLimit() > maxResults) {
      query.setLimit(maxResults);
    }
  }

  private static Query parseAndValidate(String sparql) {
    requireNonBlank(sparql, "sparql");
    Query query;
    try {
      query = QueryFactory.create(sparql);
    } catch (QueryParseException e) {
      throw new IllegalArgumentException(
          "Invalid SPARQL query (only SELECT, ASK, CONSTRUCT and DESCRIBE are supported): "
              + e.getMessage(), e);
    }
    rejectServiceClauses(query.getQueryPattern());
    return query;
  }

  private static void rejectServiceClauses(Element element) {
    if (element == null) {
      return;
    }
    ElementWalker.walk(element, new ElementVisitorBase() {
      @Override
      public void visit(ElementService el) {
        throw new IllegalArgumentException("SERVICE clauses are not allowed");
      }

      @Override
      public void visit(ElementSubQuery el) {
        rejectServiceClauses(el.getQuery().getQueryPattern());
      }
    });
  }

  private static Instant parseTime(String value) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
    }
  }

  private static Optional<Instant> getScenarioTime(Graph header, Node graph) {
    var scenarioTimes = header
        .find(graph, CimHeaderVocabulary.PREDICATE_SCENARIO_TIME, Node.ANY)
        .mapWith(Triple::getObject)
        .toList();
    if (scenarioTimes.isEmpty()) {
      return Optional.empty();
    }
    var value = scenarioTimes.getFirst();
    if (!value.isLiteral()) {
      log.warn("Ignoring graph {}: scenarioTime is not a literal", graph);
      return Optional.empty();
    }
    try {
      return Optional.of(parseTime(value.getLiteralLexicalForm()));
    } catch (DateTimeParseException e) {
      log.warn("Ignoring graph {}: invalid scenarioTime '{}'", graph, value.getLiteralLexicalForm());
      return Optional.empty();
    }
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Parameter '" + name + "' must not be blank");
    }
  }
}