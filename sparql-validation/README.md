# sparql-validation

Static SPARQL and SHACL validation against RDFS / CIM profile schemas for OpenCGMES.

## What it does

`sparql-validation` answers the question *"does this query (or shapes graph) make sense
for the schema I'm working with?"* — **without executing anything and without needing RDF
data**. It validates against RDFS profile files and catches mistakes at development time,
in unit tests, or in CI.

Inspired by [gdotv's "SPARQL Query Guardrails"](https://gdotv.com/blog/sparql-query-guardrails-etl-ready/).

| Concern | This library | SPARQL execution | SHACL validation |
| --- | :---: | :---: | :---: |
| Needs an RDF dataset | ❌ | ✅ | ✅ |
| Needs a schema (RDFS/profile) | ✅ | ❌ | ✅ (shapes) |
| Detects unknown class / property IRI | ✅ | ❌ (returns ∅) | ❌ |
| Detects domain / range mismatch | ✅ | ❌ | ❌ |
| Detects bad data values | ❌ | ❌ | ✅ |

## Quick start

Both examples require the ENTSO-E Application Profiles submodule:

```bash
git submodule update --init
mvn -q install -DskipTests
```

### SPARQL example

```bash
mvn -q -pl sparql-validation exec:java
```

Validates [`src/main/resources/examples/example-query.rq`](src/main/resources/examples/example-query.rq)
against all CGMES 3.0 RDFS profiles. Edit that file and re-run to try your own query.

```
=================================================================
 OpenCGMES -- static SPARQL query validation
=================================================================

--- schema profiles loaded from: .../CGMES/CurrentRelease/RDFS
  CoreEquipment-EU/3.0                                     210 cls  429 prop
  ...  (10 profiles total)

--- query: examples/example-query.rq (classpath)

--- validation result
  valid: false
  annotations: 3

  [ERROR] UNKNOWN_PROPERTY  -- line 19, col 11
    Property <...#DoesNotExist.property> does not exist in selected profiles [...]

  [ERROR] PROPERTY_NOT_ALLOWED_FOR_CLASS  -- line 23, col 9
    Property <...#ACLineSegment.r> is not allowed on Variable ?term typed as
    [<...#Terminal>]; expected one of [<...#ACLineSegment>].
```

To use a custom RDFS directory or query file:

```bash
mvn -q -pl sparql-validation exec:java \
    -Dexec.args="path/to/rdfs-folder path/to/query.rq"
```

### SHACL example

```bash
mvn -q -pl sparql-validation exec:java \
    -Dexec.mainClass=de.soptim.opencgmes.sparql.validation.examples.ShaclValidationExample
```

Validates [`src/main/resources/examples/example-shapes.ttl`](src/main/resources/examples/example-shapes.ttl)
against all CGMES 3.0 RDFS profiles. Edit that file and re-run to try your own shapes.

```
=================================================================
 OpenCGMES -- static SHACL shapes graph validation
=================================================================

--- shape-structure validation
  annotations: 2

  [ERROR] UNKNOWN_CLASS  -- (no source location)
    Shape sh:targetClass: class <...#DoesNotExist> does not exist in [...]

  [ERROR] UNKNOWN_PROPERTY  -- (no source location)
    Shape sh:path: property <...#ACLineSegment.typo> does not exist in [...]

--- embedded SPARQL fragments (2 found)

  -- Fragment 1 of 2  [SELECT]  target: [Terminal]
  valid: false  -- 1 annotation(s)
    [ERROR] PROPERTY_NOT_ALLOWED_FOR_CLASS  -- line 5, col 23
      Property <...#ACLineSegment.r> is not allowed on Variable ?this typed as
      [<...#Terminal>]; expected one of [<...#ACLineSegment>].

  -- Fragment 2 of 2  [SELECT]  target: [ACLineSegment]
  valid: true  (no problems)
```

## API

The entry point is [`SparqlValidationApi`](src/main/java/de/soptim/opencgmes/sparql/validation/SparqlValidationApi.java).
Construct it with any [`SchemaIndex`](src/main/java/de/soptim/opencgmes/sparql/validation/schema/SchemaIndex.java);
[`RdfsSchemaIndex`](src/main/java/de/soptim/opencgmes/sparql/validation/schema/RdfsSchemaIndex.java)
is the built-in implementation backed by RDFS files.

```java
RdfsSchemaIndex index = RdfsSchemaIndex.builder()
        .addProfile(PROFILE_EQ, List.of(CLASS_AC_LINE), List.of(PROP_R))
        .addProfile(PROFILE_TP, List.of(CLASS_VOLTAGE), List.of(PROP_NOMINAL_V))
        .build();

SparqlValidationApi api = new SparqlValidationApi(index);
```

To build the index directly from the `cimxml` module's registry:

```java
CimProfileRegistry registry = new CimProfileRegistryStd();
// ... register profiles ...
SparqlValidationApi api = new SparqlValidationApi(RdfsSchemaIndex.fromCimRegistry(registry));
```

### SPARQL validation

```java
// Auto-detects query vs. SPARQL Update; validates against all known profiles.
SparqlValidationResult r = api.validateSparql(queryText);

if (!r.isValid()) {
    r.annotations().forEach(a ->
        System.out.println("[" + a.severity() + "] " + a.code() + " -- " + a.message()));
}
```

Result shape (serializes cleanly to JSON):

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

### SHACL validation

```java
Graph shapes = RDFDataMgr.loadGraph("my-shapes.ttl");

// Validates shape structure (sh:targetClass, sh:class, sh:path) AND embedded SPARQL.
ShaclValidationResult r = api.validateShacl(shapes);

// Shape-level annotations (unknown class/property in shape positions).
r.shapeAnnotations().forEach(System.out::println);

// Per-fragment embedded SPARQL results.
for (ShaclEmbeddedQueryResult fr : r.embeddedResults()) {
    System.out.println("fragment: " + fr.embedded().rawQuery());
    fr.result().annotations().forEach(System.out::println);
}
```

`$this` inside a `sh:sparql` constraint is automatically typed as the enclosing shape's
`sh:targetClass` for the purpose of domain/range checks — no extra wiring needed.

### Dependency extraction

```java
// Which classes / properties / graphs does this query reference?
Collection<Node>       props    = api.getPropertyDependencies(query);
Collection<Node>       classes  = api.getClassDependencies(query);
Collection<Node>       graphs   = api.getGraphDependencies(query);

// Which profiles does the query actually need?
Collection<VersionIri> profiles = api.getProfileDependencies(query);

// Same for SPARQL Update and for SHACL shapes graphs.
Collection<Node>       shaclProps    = api.getShaclPropertyDependencies(shapes);
Collection<VersionIri> shaclProfiles = api.getShaclProfileDependencies(shapes);
```

### Profile scope

`validateSparql` overloads:

| Overload | Schema scope |
| --- | --- |
| `validateSparql(query)` | All profiles in the index |
| `validateSparql(query, Collection<VersionIri>)` | Only the supplied profiles |
| `validateSparql(query, Map<Node, Collection<VersionIri>>)` | Per-`GRAPH` block; graphs not in the map produce `GRAPH_NOT_CONFIGURED` |

`validateShacl` overloads:

| Overload | Schema scope |
| --- | --- |
| `validateShacl(shapes)` | All profiles in the index |
| `validateShacl(shapes, Collection<VersionIri>)` | Only the supplied profiles |

## Validation checks

### Existence checks

Every class and property IRI in the query or shapes graph is looked up in the schema index:

| Code | Severity | Triggers when |
| --- | --- | --- |
| `UNKNOWN_CLASS` | ERROR | Class IRI not found in the selected profiles |
| `UNKNOWN_PROPERTY` | ERROR | Property IRI not found in the selected profiles |
| `GRAPH_NOT_CONFIGURED` | WARN | `GRAPH <g>` used but `<g>` has no mapped profiles (named-graph scope only) |
| `UNSUPPORTED_DYNAMIC_PROPERTY` | WARN | Variable predicate or class — static check not possible |

### Semantic checks

When the schema index carries `rdfs:domain`, `rdfs:range`, or `rdfs:subClassOf` (as loaded
from real RDFS files via `RdfsSchemaIndex.fromCimRegistry`), four additional checks run:

| Code | Severity | Triggers when |
| --- | --- | --- |
| `PROPERTY_NOT_ALLOWED_FOR_CLASS` | ERROR | Subject's `rdf:type` is not a subclass of any `rdfs:domain` of the property |
| `PROPERTY_NOT_ALLOWED_FOR_CLASS` | ERROR | Adjacent path chain segments have disjoint range / domain sets |
| `QUERY_IMPLIED_TYPE` | INFO | Subject has no `rdf:type` but the property has exactly one domain — implied |
| `DATATYPE_MISMATCH` | WARN | Literal object's datatype is incompatible with `rdfs:range` |

**Lenience policy** — a check is silently skipped when the schema is silent:

- No `rdfs:domain` declared → no domain or implied-type check.
- No `rdfs:range` declared, or range is a class (not a datatype) → no datatype check.
- Inverse / alternative / repetition path operators → path-chain check skipped for that segment.

`rdfs:subClassOf` traversal is transitive and cycle-safe across the union of all profiles in scope.

## Current limitations

- Variable predicates (`?s ?p ?o`) produce `UNSUPPORTED_DYNAMIC_PROPERTY` instead of per-triple checks.
- `SERVICE { }` blocks are skipped — the remote endpoint's schema is not available.
- Line/column numbers are best-effort: the locator searches the original text for the IRI in
  `<full>`, `prefix:local`, and `a` forms; false positives inside string literals are possible.
- Inverse, alternative, and repetition path operators are excluded from path-chain compatibility
  checks (only plain `p1/p2/…` forward chains are checked).
- Datatype precision within a bucket is not checked (all numeric XSD types are treated as one
  bucket; `rdf:langString` is compatible with `xsd:string`).

## Roadmap

- **Tighter path-chain checks.** Extend chain compatibility to inverse and alternative path
  operators.
- **SHACL constraint components beyond SPARQL.** `sh:minCount`, `sh:maxCount`, `sh:pattern`,
  etc. are currently not analysed — only the SPARQL-based constraint forms are.
- **Source locations for shape-structure annotations.** `sh:targetClass` / `sh:path`
  annotations currently have no line/column because the shapes graph is parsed as an RDF
  graph, not as text.
