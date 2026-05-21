# sparql-validation

Static SPARQL query validation against RDFS / CIM profile schemas for OpenCGMES.

## Purpose

`sparql-validation` is a **SPARQL query guardrails library** — it answers
the question *"does this query make sense for the schema(s) I'm working with?"*
**without executing the query and without requiring any RDF data**. It is meant
to run inside unit tests, build pipelines, IDE/query-editor integrations, and
CI gates.

Inspired by [gdotv's "SPARQL Query Guardrails"](https://gdotv.com/blog/sparql-query-guardrails-etl-ready/):
even syntactically valid SPARQL can refer to classes or properties that don't
exist in the target ontology, or use a property on a class that doesn't allow
it. Phase 1 catches the first two; Phase 3 will tackle the third.

### What this is and isn't

| Concern                              | Static SPARQL validation (this module) | SPARQL execution | SHACL validation |
| ------------------------------------ | :------------------------------------: | :--------------: | :--------------: |
| Needs an RDF dataset                 | ❌                                     | ✅              | ✅              |
| Needs a schema (RDFS/profile)        | ✅                                     | ❌              | ✅ (shapes)     |
| Detects unknown class / property IRI | ✅                                     | ❌ (returns ∅)  | ❌              |
| Detects bad data values              | ❌                                     | ❌              | ✅              |

## API

The entry point is [`SparqlValidationApi`](src/main/java/de/soptim/opencgmes/sparql/validation/SparqlValidationApi.java).
Construct it with any [`SchemaIndex`](src/main/java/de/soptim/opencgmes/sparql/validation/schema/SchemaIndex.java);
[`RdfsSchemaIndex`](src/main/java/de/soptim/opencgmes/sparql/validation/schema/RdfsSchemaIndex.java)
is the built-in implementation.

```java
RdfsSchemaIndex index = RdfsSchemaIndex.builder()
        .addProfile(VersionIri.of("urn:profile:EQ/1"), equipmentGraph)
        .addProfile(VersionIri.of("urn:profile:TP/1"), topologyGraph)
        .build();

SparqlValidationApi api = new SparqlValidationApi(index);

SparqlValidationResult r = api.validateSparql("""
        PREFIX cim: <http://iec.ch/TC57/CIM100#>
        SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . }
        """);

if (!r.isValid()) {
    r.annotations().forEach(System.out::println);
}
```

To reuse an existing CIM registry from the `cimxml` module:

```java
CimProfileRegistry registry = new CimProfileRegistryStd();
// ... register profiles ...
SparqlValidationApi api = new SparqlValidationApi(RdfsSchemaIndex.fromCimRegistry(registry));
```

### Three scope behaviours

| Overload                                                          | Schema scope used to validate                                      | `FROM` / `FROM NAMED` / `GRAPH` in the query |
| ----------------------------------------------------------------- | ------------------------------------------------------------------ | -------------------------------------------- |
| `validateSparql(query)`                                           | **All** profiles known to the `SchemaIndex`                        | Ignored for scoping                          |
| `validateSparql(query, Collection<VersionIri>)`                   | Only the supplied profiles                                         | Ignored for scoping                          |
| `validateSparql(query, Map<Node, Collection<VersionIri>>)`        | Per `GRAPH <g>` block, the profiles mapped to `<g>`. Outside named graphs, the union of all mapped profiles. | Validated; graphs **not** in the map produce `GRAPH_NOT_CONFIGURED`. |

### Output JSON shape

`SparqlValidationResult` is a `record` and serializes cleanly to JSON:

```json
{
  "query": "PREFIX cim: <http://iec.ch/TC57/CIM100#>\nSELECT * WHERE { ?x a cim:DoesNotExist . }",
  "queryPlan": "(project (?x) (bgp (triple ?x <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://iec.ch/TC57/CIM100#DoesNotExist>)))",
  "annotations": [
    {
      "severity": "ERROR",
      "line": 2,
      "column": 26,
      "message": "Class <http://iec.ch/TC57/CIM100#DoesNotExist> does not exist in selected profiles [...].",
      "code": "UNKNOWN_CLASS",
      "term": "http://iec.ch/TC57/CIM100#DoesNotExist",
      "selectedProfiles": ["http://example.org/profile/Equipment/1.0"],
      "foundInOtherProfiles": [],
      "graph": null
    }
  ]
}
```

### Dependency API

Beyond validation, the same analysis powers four dependency-extraction
methods — useful when wiring queries to the right `DisjointMultiUnion` of
graphs, when bundling SPARQL with the profiles it needs, or when auto-loading
test fixtures:

```java
Collection<Node>       props    = api.getPropertyDependencies(query);
Collection<Node>       classes  = api.getClassDependencies(query);
Collection<Node>       graphs   = api.getGraphDependencies(query);
Collection<VersionIri> profiles = api.getProfileDependencies(query);
```

Each of these has the same three scope overloads as `validateSparql`.

## Current limitations (Phase 1)

* Validates class and property existence only; no domain/range or
  property-on-class checks (planned for Phase 3).
* Variable predicates (`?s ?p ?o`) and variable class IRIs (`?s a ?c`)
  emit an `UNSUPPORTED_DYNAMIC_PROPERTY` warning instead of a hard error.
* `SERVICE { ... }` blocks are skipped — the remote endpoint has its own
  schema we cannot inspect.
* Line/column information is best-effort: the locator searches the original
  query string for the offending IRI in `<...>` form. Prefixed names are not
  yet matched (the `message` field always contains the full IRI).
* The query plan is produced via Jena's SSE serialization. It is stable across
  runs but not identical to `arq.query --explain` (which adds optimizer
  intermediate steps).

## Roadmap

### Phase 2 — SHACL support (planned)

* Analyze SPARQL embedded in `sh:SPARQLTarget` shapes.
* Add the [ENTSO-E Application Profiles Library](https://github.com/entsoe/application-profiles-library)
  shapes to the test suite.
* Use property/class dependency analysis to infer the relevant profiles for a
  shape that doesn't carry an explicit graph or profile annotation.

### Phase 3 — Advanced semantic checks (planned)

* `QUERY_IMPLIED_TYPE` info annotations for inferred `rdf:type`.
* `DATATYPE_MISMATCH` warnings for literal datatype incompatibilities.
* `PROPERTY_NOT_ALLOWED_FOR_CLASS` errors using `rdfs:domain` chains.
* Property path compatibility across consecutive segments.

The Phase 1 API is designed to extend cleanly: new codes already live in
[`SparqlValidationCode`](src/main/java/de/soptim/opencgmes/sparql/validation/SparqlValidationCode.java),
and the annotation record carries all structured fields these checks need.
