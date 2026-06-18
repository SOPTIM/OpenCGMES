---
title: API Reference
sidebar_position: 7
---

# API Reference

The entry point is **`SparqlValidationApi`** (`de.soptim.opencgmes.cimvocabcheck.core`). Construct
it with a `SchemaIndex`, then validate SPARQL and SHACL. For task-oriented examples see
[Library & tests](/cimvocabcheck/library-and-tests).

## Construction

```java
public SparqlValidationApi(SchemaIndex schemaIndex)
public SparqlValidationApi(SchemaIndex schemaIndex, Map<String,String> defaultPrefixes)
public SparqlValidationApi(SchemaIndex schemaIndex, Map<String,String> defaultPrefixes,
                           boolean checkStandardVocabulary)
```

`defaultPrefixes` are injected into queries that don't declare them (see
[`prefixes`](/cimvocabcheck/configuration#prefixes)); `checkStandardVocabulary` toggles
[standard-vocabulary checking](/cimvocabcheck/configuration#standardvocabulary).

### `SchemaIndex` implementations

| Type | Build with | Notes |
| --- | --- | --- |
| `RdfsSchemaIndex` | `RdfsSchemaIndex.fromCimRegistry(registry)` | From a CIMXML `CimProfileRegistry` — the usual path |
| `RdfsSchemaIndex` | `RdfsSchemaIndex.builder().addProfile(…).build()` | Hand-built index for tests |
| `EndpointSchema` (`.index()`) | `EndpointSchemaLoader.loadFromEndpoint(url, timeout)` | From a live SPARQL endpoint — see [Endpoints](/cimvocabcheck/endpoints) |

```java
public SchemaIndex schemaIndex();   // the index this API was built with
```

## SPARQL validation

```java
public SparqlValidationResult validateSparql(String input);
public SparqlValidationResult validateSparql(String input, Collection<VersionIri> profiles);
public SparqlValidationResult validateSparql(String input, Map<Node, Collection<VersionIri>> graphScope);
public static SparqlValidationResult checkSyntaxOnly(String input);   // no schema needed
```

`validateSparql` auto-detects query vs. SPARQL Update. `checkSyntaxOnly` is a static
schema-independent parse check.

### Profile scope

| Overload | Schema scope |
| --- | --- |
| `validateSparql(query)` | All profiles in the index |
| `validateSparql(query, Collection<VersionIri>)` | Only the supplied profiles |
| `validateSparql(query, Map<Node, Collection<VersionIri>>)` | Per-`GRAPH` block; graphs not in the map produce `GRAPH_NOT_CONFIGURED` |

The static helper `SparqlValidationApi.buildNamedGraphScope(...)` constructs the per-graph map from
[`namedGraphs`](/cimvocabcheck/configuration#namedgraphs) config.

## SHACL validation

```java
public ShaclValidationResult validateShacl(Graph shapesGraph);
public ShaclValidationResult validateShacl(Graph shapesGraph, Collection<VersionIri> profiles);
public static ShaclValidationResult checkShaclSyntaxOnly(Graph shapesGraph);
public List<EmbeddedSparql> extractShaclSparql(Graph shapesGraph);
public Collection<VersionIri> inferProfileScope(Graph shapesGraph);
```

## Result types

### `SparqlValidationResult`

```java
record SparqlValidationResult(String query, String queryPlan,
                              List<SparqlValidationAnnotation> annotations) {
    boolean isValid();                       // false if any ERROR
    boolean isValid(StrictnessLevel level);  // apply a strictness mapping first
}
```

### `SparqlValidationAnnotation`

```java
record SparqlValidationAnnotation(
    SparqlValidationSeverity severity,   // ERROR | WARN | INFO
    Integer line,                        // 1-based, or null
    Integer column,                      // 1-based, or null
    String message,                      // human-readable rendering
    SparqlValidationCode code,           // stable enum — see Validation checks
    Node term,                           // offending RDF term, or null
    List<VersionIri> selectedProfiles,
    List<VersionIri> foundInOtherProfiles,
    Node graph)                          // named-graph context, or null
```

The result serializes cleanly to JSON (this is exactly what the [CLI](/cimvocabcheck/cli)
`--format json` emits):

```json
{
  "query": "…",
  "queryPlan": "(project …)",
  "annotations": [
    {
      "severity": "ERROR",
      "line": 2,
      "column": 26,
      "message": "Class <…#DoesNotExist> does not exist in selected profiles [EQ/1.0].",
      "code": "UNKNOWN_CLASS",
      "term": "http://…#DoesNotExist",
      "selectedProfiles": ["http://…/EQ/1.0"],
      "foundInOtherProfiles": []
    }
  ]
}
```

### `ShaclValidationResult`

```java
record ShaclValidationResult(
    List<SparqlValidationAnnotation> shapeAnnotations,    // structural findings
    List<ShaclEmbeddedQueryResult> embeddedResults) {     // one per embedded SPARQL fragment
    boolean isValid();
    boolean isValid(StrictnessLevel level);
    int totalAnnotations();
}

record ShaclEmbeddedQueryResult(EmbeddedSparql embedded, SparqlValidationResult result) {}
```

`EmbeddedSparql` exposes `rawQuery()`, `renderedQuery()` (with prefixes prepended), and
`targetClasses()`.

## Dependency extraction

Each method has overloads taking an explicit profile scope, plus `getShacl…(Graph)` and
`getUpdate…(String)` variants.

```java
Collection<Node>       getClassDependencies(String query);
Collection<Node>       getPropertyDependencies(String query);
Collection<Node>       getGraphDependencies(String query);
Collection<VersionIri> getProfileDependencies(String query);

Collection<Node>       getShaclClassDependencies(Graph shapes);
Collection<Node>       getShaclPropertyDependencies(Graph shapes);
Collection<VersionIri> getShaclProfileDependencies(Graph shapes);
```

## Explain

```java
public QueryExplanation explain(String input);          // instance — injects configured prefixes
public static QueryExplanation explainStatic(String input);   // schema-independent
```

`QueryExplanation.render()` returns the formatted algebra plan — see
[Explain query](/cimvocabcheck/explain-query).

## See also

- [Library & tests](/cimvocabcheck/library-and-tests) — cookbook with full JUnit examples.
- [Validation checks](/cimvocabcheck/validation-checks) — the `SparqlValidationCode` values.
- [Known limitations](/cimvocabcheck/limitations) — what the checks deliberately skip.
