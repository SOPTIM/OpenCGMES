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

## Try it

A self-contained, runnable example lives in
[`SparqlValidationExample`](src/main/java/de/soptim/opencgmes/sparql/validation/examples/SparqlValidationExample.java).
It reads two files from [`src/main/resources/examples/`](src/main/resources/examples/) — a
small CIM/RDFS schema and a SPARQL query — validates the query against the schema, and prints
the result. No network, no arguments.

```bash
mvn -q install -DskipTests             # once, so cimxml is in your local repo
mvn -q -pl sparql-validation exec:java
```

Output:

```
==============================================================
 OpenCGMES — static SPARQL query validation
==============================================================
 schema : examples/cim-mini-schema.rdf
 profile: http://example.org/cim-mini/EquipmentProfile/1.0  (6 classes, 3 properties)
 query  : examples/example-query.rq

----- query --------------------------------------------------
  ...
  9 |         cim:ACLineSegment.resistance ?r .
 ...
 12 |         cim:ACLineSegment.r ?value .

----- result -------------------------------------------------
 valid: false

 [ERROR] UNKNOWN_PROPERTY  —  line 9, col 9
   Property <...#ACLineSegment.resistance> does not exist in selected profile [...].

 [ERROR] PROPERTY_NOT_ALLOWED_FOR_CLASS  —  line 12, col 9
   Property <...#ACLineSegment.r> is not allowed on Variable ?term typed as
   [<...#Terminal>]; expected one of [<...#ACLineSegment>].
```

Swap in your own schema/query by editing the two files under
`src/main/resources/examples/` — or point the API at a real ENTSO-E profile;
see [`testing/entsoe/`](testing/entsoe/).

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

`fromCimRegistry` overlays the registry's `PropertyInfo` map (domain class from
`rdfs:domain`, range from `cims:dataType → cim:Primitive → xsd:*`) on top of the
generic RDFS scan, so every Phase 3 check (`PROPERTY_NOT_ALLOWED_FOR_CLASS`,
`QUERY_IMPLIED_TYPE`, `DATATYPE_MISMATCH`) fires against real CIM profiles
without any extra wiring on the caller's side.

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

## SHACL support (Phase 2)

The same engine validates SPARQL fragments embedded in SHACL shapes graphs.
The extractor recognises:

* `sh:SPARQLTarget` — `sh:select` queries that pick focus nodes.
* `sh:SPARQLConstraint` — `sh:select` queries that return violations.
* `sh:SPARQLAskValidator` — `sh:ask` queries used inside constraint components.
* `sh:SPARQLSelectValidator` — `sh:select` queries used inside constraint components.
* `sh:SPARQLRule` — `sh:construct` queries used as SHACL rules.

SHACL-declared prefixes (`sh:prefixes → sh:declare → sh:prefix/sh:namespace`) are
resolved and prepended to each query before parsing, so a query written as

```turtle
ex:S sh:sparql [
    sh:prefixes ex: ;
    sh:select "SELECT $this WHERE { $this a cim:ACLineSegment }"
] .
ex: sh:declare [ sh:prefix "cim" ; sh:namespace "http://iec.ch/TC57/CIM100#"^^xsd:anyURI ] .
```

is analyzed as if the user had written `PREFIX cim: <http://iec.ch/TC57/CIM100#>` at the
top of the query.

```java
Graph shapes = RDFDataMgr.loadGraph("my-shapes.ttl");

ShaclValidationResult r = api.validateShacl(shapes);
ShaclValidationResult rScoped = api.validateShacl(shapes, List.of(VersionIri.of("…/EQ/1")));

// "Which profiles does this shape file actually need?"
Collection<VersionIri> profiles = api.getShaclProfileDependencies(shapes);
Collection<Node>       classes  = api.getShaclClassDependencies(shapes);
Collection<Node>       props    = api.getShaclPropertyDependencies(shapes);

// Raw access to the extracted fragments.
List<EmbeddedSparql> fragments = api.extractShaclSparql(shapes);
```

ENTSO-E `application-profiles-library` shapes do not use named graphs, so a single
profile scope applies to the whole shapes graph — see [`testing/entsoe/`](testing/entsoe/)
for how to wire that library in as a drop-in test fixture.

## Semantic checks (Phase 3)

On top of the existence checks the validator runs four additional rules whenever the
underlying `SchemaIndex` carries the relevant relations (`rdfs:domain`, `rdfs:range`,
`rdfs:subClassOf`):

| Code                                | Severity | Triggers when                                                                                  |
| ----------------------------------- | -------- | ---------------------------------------------------------------------------------------------- |
| `PROPERTY_NOT_ALLOWED_FOR_CLASS`    | ERROR    | A property is used on a subject whose declared `rdf:type` is not a subclass of any domain.     |
| `PROPERTY_NOT_ALLOWED_FOR_CLASS`    | ERROR    | A property-path segment's range is disjoint from the next segment's domain (chain check).      |
| `QUERY_IMPLIED_TYPE`                | INFO     | Subject has no explicit `rdf:type` and the property has exactly one domain — type implied.     |
| `DATATYPE_MISMATCH`                 | WARN     | Literal object's datatype is not compatible with the property's `rdfs:range` datatype.         |

### Lenience policy

These checks bail out silently whenever the schema is silent:

* No `rdfs:domain` declared → no `PROPERTY_NOT_ALLOWED_FOR_CLASS` and no `QUERY_IMPLIED_TYPE`.
* No `rdfs:range` declared → no `DATATYPE_MISMATCH`.
* Range is a class (not an XSD datatype) → no `DATATYPE_MISMATCH` (the validator does not
  reason about IRI-reference shape in Phase 3).
* Path component uses inverse / alt / mod / neg → chain check is skipped for that component.

Datatype compatibility is bucketed: every numeric XSD type counts as one bucket, every
string-ish XSD type as another. Exact-match within `xsd:int` vs `xsd:short` is deliberately
ignored. `rdf:langString` is compatible with `xsd:string`.

`rdfs:subClassOf` traversal is transitive and cycle-safe; it operates across the union of
profiles in the current validation scope (so a subclass declared in profile A and a parent
in profile B still resolve when both are in scope).

## Current limitations

* Variable predicates (`?s ?p ?o`) and variable class IRIs (`?s a ?c`) emit an
  `UNSUPPORTED_DYNAMIC_PROPERTY` warning instead of a hard error.
* `SERVICE { ... }` blocks are skipped — the remote endpoint has its own
  schema we cannot inspect.
* Line/column information is best-effort: the locator searches the original
  query string for the offending term in three forms — `<full-IRI>`, then any
  `prefix:local` resolved from the query's `PrefixMapping`, then the `a`
  shorthand when the term is `rdf:type`. The earliest match wins. False
  positives inside string literals are possible but rare.
* The query plan is produced via Jena's SSE serialization. It is stable across
  runs but not identical to `arq.query --explain` (which adds optimizer
  intermediate steps).

## Roadmap

Phases 1–3 are landed. Known follow-ups that are explicitly out of scope today:

* **Inverse / alt / mod path chain semantics.** Today only pure forward
  `p1/p2/p3` sequences participate in chain compatibility checks.
* **Sub-byte numeric precision.** All XSD numeric types are bucketed as one; a future
  pass could narrow this.
* **`a` keyword false-positives inside string literals.** The source locator is
  textual; an `a` token inside a quoted SPARQL literal could be mis-matched.
  Acceptable for best-effort `line`/`column` reporting; tighten if needed.
