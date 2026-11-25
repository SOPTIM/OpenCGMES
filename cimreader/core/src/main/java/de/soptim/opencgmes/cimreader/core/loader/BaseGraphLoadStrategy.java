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

package de.soptim.opencgmes.cimreader.core.loader;

import de.soptim.opencgmes.cimreader.core.source.ModelSource;
import de.soptim.opencgmes.cimxml.CimHeaderVocabulary;
import de.soptim.opencgmes.cimxml.graph.FastDeltaGraph;
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import de.soptim.opencgmes.cimxml.sparql.core.LinkedCimDatasetGraph;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphUtil;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.graph.GraphFactory;

public abstract class BaseGraphLoadStrategy implements LoadStrategy {

  private static final double DELTA_THRESHOLD = 0.35;

  protected abstract int selectReferenceIndex(List<ModelSource> sources);

  public static final Node HEADER_GRAPH = NodeFactory.createURI("urn:opencgmes:headers");

  @Override
  public DatasetGraph load(List<List<ModelSource>> headerSources) throws IOException {
    CimXmlParser parser = new CimXmlParser();
    Map<Node, Graph> cache = new HashMap<>();
    Map<Node, ModelSource> byModelId = buildByModelIdMap(headerSources);
    var headerGraph = GraphFactory.createDefaultGraph();

    var dataset = new LinkedCimDatasetGraph();
    for (var sources : headerSources) {
      if (!sources.isEmpty()) {
        var referenceIndex = selectReferenceIndex(sources);
        var referenceSource = sources.get(referenceIndex);
        var referenceGraph = toFullGraph(parse(referenceSource, parser), byModelId, cache, parser);
        dataset.addGraph(referenceSource.header().getModel(), referenceGraph);
        addHeader(headerGraph, referenceSource);
        var currentReferenceGraph = referenceGraph;
        for (int i = referenceIndex - 1; i >= 0; i--) {
          currentReferenceGraph = addGraph(parser, cache, byModelId, dataset, sources,
              currentReferenceGraph, i, headerGraph);
        }
        currentReferenceGraph = referenceGraph;
        for (int i = referenceIndex + 1; i < sources.size(); i++) {
          currentReferenceGraph = addGraph(parser, cache, byModelId, dataset, sources,
              currentReferenceGraph, i, headerGraph);
        }
      }
    }
    dataset.addGraph(HEADER_GRAPH, headerGraph);
    return dataset;
  }

  private Graph addGraph(CimXmlParser parser, Map<Node, Graph> cache,
      Map<Node, ModelSource> byModelId, DatasetGraph dataset, List<ModelSource> sources,
      Graph currentReferenceGraph, int i, Graph headerGraph) throws IOException {
    var source = sources.get(i);
    var cached = cache.get(source.header().getModel());
    var graph = cached == null ? toFullGraph(parse(source, parser), byModelId, cache, parser)
        : cached;
    var deltaGraph = buildDeltaGraph(currentReferenceGraph, graph);
    if (deltaGraph.isEmpty()) {
      dataset.addGraph(source.header().getModel(), graph);
      currentReferenceGraph = graph;
    } else {
      dataset.addGraph(source.header().getModel(), deltaGraph.get());
    }
    addHeader(headerGraph, source);
    return currentReferenceGraph;
  }

  private Graph toFullGraph(CimDatasetGraph datasetGraph, Map<Node, ModelSource> byModelId,
      Map<Node, Graph> cache, CimXmlParser parser)
      throws IOException {
    var cached = cache.get(datasetGraph.getModelHeader().getModel());
    if (cached != null) {
      return cached;
    }
    if (datasetGraph.isFullModel()) {
      cache.put(datasetGraph.getModelHeader().getModel(), datasetGraph.getBody());
      return datasetGraph.getBody();
    }
    var predecessorSource = datasetGraph.getModelHeader().getSupersedes().stream()
        .map(byModelId::get)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No predecessor found for " + datasetGraph.getModelHeader().getModel()));
    cached = cache.get(predecessorSource.header().getModel());
    var predecessorGraph =
        cached == null ? toFullGraph(parse(predecessorSource, parser), byModelId, cache, parser)
            : cached;
    var graph = differenceModelToFullModel(datasetGraph, predecessorGraph);
    cache.put(datasetGraph.getModelHeader().getModel(), graph);
    return graph;
  }

  private CimDatasetGraph parse(ModelSource hs, CimXmlParser parser) throws IOException {
    try (var in = hs.xmlSource().open()) {
      return parser.parseCimModel(in);
    }
  }

  private Graph differenceModelToFullModel(CimDatasetGraph datasetGraph, Graph fullModel) {
    var graph = GraphFactory.createDefaultGraph();
    GraphUtil.addInto(graph, fullModel);
    datasetGraph.getReverseDifferences().stream().forEach(graph::delete);
    datasetGraph.getForwardDifferences().stream().forEach(graph::add);
    return graph;
  }

  private Map<Node, ModelSource> buildByModelIdMap(List<List<ModelSource>> modelSources) {
    Map<Node, ModelSource> byModelId = new HashMap<>();
    modelSources.stream().flatMap(List::stream)
        .forEach(hs -> byModelId.put(hs.header().getModel(), hs));
    return byModelId;
  }

  private Optional<FastDeltaGraph> buildDeltaGraph(Graph baseGraph, Graph differenceGraph) {
    var additions = GraphFactory.createDefaultGraph();
    var deletions = GraphFactory.createDefaultGraph();
    differenceGraph.find().forEach(triple -> {
      if (!baseGraph.contains(triple)) {
        additions.add(triple);
      }
    });
    baseGraph.find().forEach(triple -> {
      if (!differenceGraph.contains(triple)) {
        deletions.add(triple);
      }
    });
    if (additions.size() + deletions.size() >= baseGraph.size() * DELTA_THRESHOLD) {
      return Optional.empty();
    }
    return Optional.of(new FastDeltaGraph(baseGraph, additions, deletions));
  }

  private void addHeader(Graph headerGraph, ModelSource source) {
    var header = source.header();
    var model = header.getModel();
    if (header.getModelingAuthoritySet() != null) {
      headerGraph.add(model, CimHeaderVocabulary.PREDICATE_MODELING_AUTHORITY_SET,
          header.getModelingAuthoritySet());
    }
    if (header.getScenarioTime() != null) {
      headerGraph.add(model, CimHeaderVocabulary.PREDICATE_SCENARIO_TIME,
          header.getScenarioTime());
    }
    if (header.getProfiles() != null) {
      header.getProfiles().forEach(
          profile -> headerGraph.add(model, CimHeaderVocabulary.PREDICATE_PROFILE, profile));
    }
  }
}
