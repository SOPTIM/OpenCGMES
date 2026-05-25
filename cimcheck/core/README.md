# CIMcheck - Core

Static SPARQL and SHACL validation against RDFS / CIM profile schemas for OpenCGMES.

## What it does

`cimcheck-core` answers the question *"does this query (or shapes graph) make sense
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
mvn -q -pl cimcheck/core exec:java
```

Validates [`src/main/resources/examples/example-query.rq`](src/main/resources/examples/example-query.rq)
against all CGMES 3.0 RDFS profiles. Edit that file and re-run to try your own query.

```
=================================================================
 CIMcheck -- static SPARQL query validation
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
mvn -q -pl cimcheck/core exec:java \
    -Dexec.args="path/to/rdfs-folder path/to/query.rq"
```

### SHACL example

```bash
mvn -q -pl cimcheck/core exec:java \
    -Dexec.mainClass=de.soptim.opencgmes.cimcheck.core.examples.ShaclValidationExample
```

Validates [`src/main/resources/examples/example-shapes.ttl`](src/main/resources/examples/example-shapes.ttl)
against all CGMES 3.0 RDFS profiles. Edit that file and re-run to try your own shapes.

```
=================================================================
 CIMcheck -- static SHACL shapes graph validation
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

> **Note:** source positions are unavailable in the command-line tool because it operates
> on an RDF graph, not on raw text. The VS Code extension resolves positions into the
> original source file for all annotation types.

---

## VS Code extension

The `cimcheck/vscode` module ships a VS Code extension that runs the language
server and shows validation diagnostics (squiggly underlines) directly in the editor as
you type — for both SPARQL query files and SHACL Turtle files.

**Supported file types:**

| Extension | Language |
| --- | --- |
| `.rq`, `.sparql` | SPARQL query / update |
| `.ttl`, `.shacl` | Turtle / SHACL shapes |

Validation is debounced (300 ms after the last keystroke) and automatically re-runs on
all open files when the config file changes.

### Editor features

#### Diagnostics

Squiggly underlines appear inline as you type. Hovering over an underlined term opens a
tooltip that names the exact validation code (`UNKNOWN_CLASS`, `PROPERTY_NOT_ALLOWED_FOR_CLASS`,
etc.) and links the relevant profile IRI.

#### Hover documentation

Hovering over any CIM class or property IRI (in both `prefix:local` and `<full-IRI>` form)
shows a Markdown tooltip with:

- The abbreviated IRI and its `rdfs:label` (when the label differs from the local name)
- The `rdfs:comment` description from the schema
- A summary table of `rdfs:domain`, `rdfs:range`, and the declaring profile(s)

Works for terms declared in any profile loaded by `.cgmes/validation.json`.

#### Code completion

Typing a declared prefix followed by `:` triggers CIM-aware completion. As you continue
typing the local name, the list narrows to matching terms:

```sparql
PREFIX cim: <http://iec.ch/TC57/CIM100#>

SELECT * WHERE {
  ?s a cim:ACL  # → completes to cim:ACLineSegment, cim:ACDCConverter, …
     cim:ACLineSegment.r ?r .  # → property completions in predicate position
}
```

Context-aware behaviour:

| Position | Completions shown |
| --- | --- |
| After `a`, `rdf:type`, `sh:targetClass`, `sh:class`, `rdfs:subClassOf`, `sh:datatype` | Classes only |
| Predicate or any other position | Classes **and** properties |

Each completion item includes the `rdfs:label` (as _detail_) and `rdfs:comment` (as
_documentation_) from the loaded schema, shown in the VS Code completion pop-up.

Completion only fires when a declared namespace prefix is typed — open-ended typing without
a prefix shows nothing, to avoid flooding the list. Trigger the list manually with
`Ctrl+Space` after the colon.

### Prerequisites

| Tool | Minimum version |
| --- | --- |
| Java | 21 |
| Maven | 3.9 |
| Node.js | 20 |
| `vsce` (optional, for packaging) | any recent |

### Building the VSIX

```bash
# 1. Build the Java language server JAR (from the repo root)
mvn install -DskipTests

# 2. Build the VS Code extension
cd cimcheck/vscode
npm install
npm run copy-jar    # copies cimcheck-lsp.jar into server/
npm run bundle      # compiles TypeScript and bundles with esbuild
npx vsce package    # produces cimcheck-<version>.vsix
```

The `npm run copy-jar` step copies `cimcheck/lsp/target/cimcheck-lsp.jar`
into `cimcheck/vscode/server/`. The JAR is bundled inside the VSIX so users do
not need to build Java themselves.

### Installing

**From a VSIX file** (most common):

```bash
code --install-extension cimcheck-X.Y.Z.vsix
```

Or open VS Code → Extensions panel → `⋯` menu → **Install from VSIX…**

### Configuring your workspace

Create `.cgmes/validation.json` in your workspace root. The server discovers this file
automatically by walking up the directory tree from each open file.

**Option A — point at a directory of RDFS files** (`.rdf`, `.ttl`, `.owl`):

```json
{
  "schemasDirectory": ".cgmes/schemas"
}
```

**Option B — list individual schema files:**

```json
{
  "schemas": [
    ".cgmes/61970-600-2_Equipment-AP-Voc-RDFS2020.rdf",
    ".cgmes/61970-600-2_Topology-AP-Voc-RDFS2020.rdf"
  ]
}
```

**Option C — also map named graphs to specific profiles:**

```json
{
  "schemasDirectory": ".cgmes/schemas",
  "namedGraphs": {
    "urn:uuid:my-equipment-graph": "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0",
    "urn:uuid:my-topology-graph":  "http://iec.ch/TC57/ns/CIM/Topology-EU/3.0"
  }
}
```

When `namedGraphs` is configured, terms inside a `GRAPH <urn:uuid:my-equipment-graph> {}`
block are validated against only the mapped profile instead of all profiles. Graphs not in
the map produce a `GRAPH_NOT_CONFIGURED` information-level diagnostic.

If no config file is found, a single information-level diagnostic is shown at the top of
each file asking you to create one.

### VS Code settings

| Setting | Default | Description |
| --- | --- | --- |
| `cimcheck.serverJar` | *(bundled)* | Absolute path to a custom `cimcheck-lsp.jar`. Leave empty to use the JAR bundled inside the extension. |
| `cimcheck.javaExecutable` | `java` | Java executable used to launch the server. Must be Java 21 or later. |
| `cimcheck.javaArgs` | `[]` | Extra JVM arguments, e.g. `["-Xmx512m"]`. |
| `cimcheck.trace.server` | `off` | LSP trace level: `off`, `messages`, or `verbose`. Useful for debugging. |

### Strictness mode

Set `"strictness"` in `.cgmes/validation.json` to control how the validator reports findings:

| Level | Behaviour |
| --- | --- |
| `permissive` | Structural errors only (`SYNTAX_ERROR`, `UNKNOWN_CLASS`, `UNKNOWN_PROPERTY`, `INVALID_CARDINALITY`) |
| `default` | All findings as-is (errors are errors, warnings are warnings) |
| `strict` | Warnings promoted to errors — suitable for CI |
| `pedantic` | Warnings and infos promoted to errors |

```json
{
  "schemasDirectory": ".cgmes/schemas",
  "strictness": "strict"
}
```

### Troubleshooting

If the extension is installed but no diagnostics appear:

1. Run **CIMcheck: Show Output** from the Command Palette to open the
   output channel — startup errors and schema-load failures are logged there.
2. Check that Java 21+ is on `PATH`, or set `cimcheck.javaExecutable` explicitly.
3. Verify that `.cgmes/validation.json` exists and points to valid schema files.
4. Set `cimcheck.trace.server` to `messages` to see raw LSP traffic.

---

## Language server

`cimcheck/lsp` is a standalone [LSP 3.17](https://microsoft.github.io/language-server-protocol/)
server that wraps the `cimcheck-core` library. It communicates over `stdio` and can be
integrated into any LSP-capable editor.

The server reads `.cgmes/validation.json` from the workspace root (walked up from the
workspace root path reported by the client on `initialize`), loads the configured RDFS
profiles once, and re-loads them whenever `validation.json` changes. All open documents
are revalidated after a schema reload.

To build the fat JAR only:

```bash
mvn -pl cimcheck/lsp package -DskipTests
# Output: cimcheck/lsp/target/cimcheck-lsp.jar
```

Launch it directly for integration testing:

```bash
java -jar cimcheck/lsp/target/cimcheck-lsp.jar
# Speaks LSP over stdin/stdout.
```

---

## CLI

`cimcheck/cli` ships a command-line tool for validating queries and shapes in CI pipelines.

```bash
mvn -pl cimcheck/cli package -DskipTests
# Output: cimcheck/cli/target/cimcheck.jar
```

Basic usage:

```bash
java -jar cimcheck.jar --help
java -jar cimcheck.jar --schema path/to/rdfs --strictness strict path/to/query.rq
```

The `--strictness` flag mirrors the VS Code setting.

---

## API

The entry point is [`SparqlValidationApi`](src/main/java/de/soptim/opencgmes/cimcheck/core/SparqlValidationApi.java).
Construct it with any [`SchemaIndex`](src/main/java/de/soptim/opencgmes/cimcheck/core/schema/SchemaIndex.java);
[`RdfsSchemaIndex`](src/main/java/de/soptim/opencgmes/cimcheck/core/schema/RdfsSchemaIndex.java)
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

Multiple `SELECT` queries separated by `;` in one file are automatically split and each
segment is validated independently with line offsets adjusted back to the original text.

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
`$PATH` is automatically substituted with the enclosing `sh:PropertyShape`'s `sh:path`
URI when the path is a simple IRI, so `$this $PATH ?value` patterns are validated
against the concrete property rather than treated as unresolvable variable predicates.

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
| `UNSUPPORTED_DYNAMIC_PROPERTY` | WARN | Variable predicate or class that cannot be statically resolved — check skipped for those triples |

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

### SHACL property-shape constraint checks

Two additional checks run on every SHACL property shape (any blank node with `sh:path`):

| Code | Severity | Triggers when |
| --- | --- | --- |
| `NODE_KIND_INCOMPATIBLE_WITH_RANGE` | WARN | `sh:nodeKind` forces a non-literal (`sh:IRI`, `sh:BlankNode`, `sh:BlankNodeOrIRI`) but `rdfs:range` is a datatype; or `sh:nodeKind sh:Literal` but `rdfs:range` is a class |
| `INVALID_CARDINALITY` | ERROR | `sh:minCount` exceeds `sh:maxCount` on the same property shape |

**Lenience policy** — a check is silently skipped:

- `NODE_KIND_INCOMPATIBLE_WITH_RANGE`: skipped when no `rdfs:range` is declared, when the path is not a plain URI (sequence, inverse, etc.), or when the range is a mix of datatypes and classes. Ambiguous node kinds (`sh:IRIOrLiteral`, `sh:BlankNodeOrLiteral`) are never flagged.
- `INVALID_CARDINALITY`: skipped when only one of `sh:minCount` / `sh:maxCount` is present.

## Known limitations

### Dynamic predicates and classes

When a triple pattern uses a variable as the predicate or `rdf:type` object (e.g. `?s ?p ?o`
or `?s a ?cls`), CIMcheck cannot perform per-triple existence or domain/range checks. It emits
a single `UNSUPPORTED_DYNAMIC_PROPERTY` warning (severity WARN) for the whole query to flag
that static validation is incomplete — it does **not** attempt to enumerate what the variable
might be.

A variable used as the **subject** is fine; the limitation is only for variable
*predicates* and *type-object* positions.

**Exception — SHACL `$PATH` variable:** SHACL embedded constraints commonly use
`$this $PATH ?value` where `$PATH` is a runtime placeholder for the enclosing
`sh:PropertyShape`'s `sh:path`. CIMcheck resolves this automatically: when a
`sh:SPARQLConstraint` is linked to a property shape via `sh:sparql` and that
shape has a simple URI `sh:path`, `$PATH` is substituted with the concrete
property URI before analysis. No `UNSUPPORTED_DYNAMIC_PROPERTY` warning is
emitted for successfully resolved `$PATH` patterns.

### SERVICE blocks

`SERVICE { }` federated-query blocks are silently skipped. CIMcheck has no access to the
remote endpoint's schema, so no class or property checks are performed inside the SERVICE
body. No diagnostic is emitted for this omission.

### Incomplete schema — missing `rdfs:domain` / `rdfs:range`

All semantic checks (domain, range, path-chain compatibility) follow a **silent-on-missing**
policy: if the schema index has no declared `rdfs:domain` for a property, no
`PROPERTY_NOT_ALLOWED_FOR_CLASS` or `QUERY_IMPLIED_TYPE` annotation is emitted for that
property. Likewise, if `rdfs:range` is absent, no `DATATYPE_MISMATCH` is emitted.

This means a query that uses a property against a completely wrong subject type will produce
no error if the profile files omit `rdfs:domain` for that property — a common situation with
CIM profiles that only partially annotate semantics. The existence check (`UNKNOWN_CLASS`,
`UNKNOWN_PROPERTY`) still fires regardless.

Additionally, `rdfs:subClassOf` traversal is transitive but limited to what the loaded
profiles declare. If a profile does not carry full subclass chains, a domain check may be
skipped even when the types are technically incompatible.

**Practical consequence for CIM users:** the CGMES RDFS files published by ENTSO-E declare
`rdfs:domain` and `rdfs:range` on most properties, so semantic checks are generally active.
Proprietary or partial profile files may suppress them silently.

### Path-chain checks

Forward property path chains (`ex:p1/ex:p2/ex:p3`) are checked for range–domain
compatibility between adjacent segments. These path operators are **not** checked in chain
context:

- Inverse paths (`^ex:p`)
- Alternative paths (`ex:p1 | ex:p2`)
- Zero-or-more / one-or-more / zero-or-one (`*`, `+`, `?`)

Each URI segment in these paths is still checked for **existence** (`UNKNOWN_PROPERTY`);
only the cross-segment compatibility (range of p1 must overlap domain of p2) is skipped.

### Datatype precision

Datatype range checks group XSD types into compatibility buckets. These groupings are
intentionally coarse:

- All numeric XSD types (`xsd:integer`, `xsd:float`, `xsd:decimal`, etc.) are treated as
  one bucket — using the wrong numeric subtype is not flagged.
- `rdf:langString` is treated as compatible with `xsd:string`.

Precision mismatches within a bucket (e.g. an `xsd:float` literal on a property that
declares `rdfs:range xsd:integer`) do not produce a `DATATYPE_MISMATCH`.

### Source positions

Line and column numbers are located by searching the original query text for the IRI in
three forms: `<full-IRI>`, `prefix:local`, and the `a` keyword (for `rdf:type`). The
locator picks the occurrence nearest to the subject node of the offending triple, using a
same-line heuristic then minimum character distance.

Edge cases where positions may be wrong or missing:
- An IRI that appears only inside a string literal or inside a comment is filtered out by a
  bracket/string-state tracker, but the tracker is a simple scan — complex multi-line strings
  can confuse it.
- IRI forms not matching any of the three patterns (e.g. an IRI assembled by `BIND` or
  `VALUES`) are reported as `(no source location)`.
- SHACL shape-level annotations always report `(no source location)` because the shapes
  graph is an RDF graph, not raw text.

### SHACL: structural checks vs. SPARQL-validated

CIMcheck performs **two distinct passes** over a SHACL shapes graph.

**Pass 1 — structural analysis** validates shape declarations against the schema:

| Predicate | What is checked |
| --- | --- |
| `sh:targetClass` | Class IRI must exist in the selected profiles |
| `sh:class` | Class IRI must exist in the selected profiles |
| `sh:path` | Every URI segment of the path (simple, sequence, inverse, alternative, zero/one/more) must be a known property; standard vocabulary terms (`rdf:type`, `rdfs:*`, `owl:*`, etc.) are exempt |
| `sh:nodeKind` + `rdfs:range` | `NODE_KIND_INCOMPATIBLE_WITH_RANGE` when node kind contradicts the schema range |
| `sh:minCount` + `sh:maxCount` | `INVALID_CARDINALITY` when min > max |

**Pass 2 — embedded SPARQL validation** extracts and fully validates any inline SPARQL:

| SHACL mechanism | Extraction point | `$this` typing |
| --- | --- | --- |
| `sh:sparql` → `sh:select` | `sh:SPARQLConstraint` | Shape's `sh:targetClass` |
| `sh:target` → `sh:select` | `sh:SPARQLTarget` | Shape's `sh:targetClass` |
| `sh:validator` → `sh:ask` | `sh:SPARQLAskValidator` | Shape's `sh:targetClass` |
| `sh:rule` → `sh:construct` | `sh:SPARQLRule` | Shape's `sh:targetClass` |

The extractor resolves `sh:prefixes → sh:declare → sh:prefix/sh:namespace` chains
automatically and passes the resulting prefix map into the SPARQL validator. If a
`sh:prefixes` target node carries no `sh:declare` blocks (a common omission in
published ENTSO-E SHACL files), the graph's own Turtle `@prefix` declarations are
used as a fallback, so prefixes declared at the file level are always available to
embedded queries.

**SHACL features that are not checked at all** (neither structurally nor via SPARQL):

| Feature | Reason not checked |
| --- | --- |
| `sh:datatype` | Redundant with range checks; not yet wired |
| `sh:pattern` / `sh:flags` | Regex evaluation requires data |
| `sh:in` | Value enumeration requires data |
| `sh:hasValue` | Specific-value check requires data |
| `sh:qualifiedValueShape` / `sh:qualifiedMinCount` / `sh:qualifiedMaxCount` | Requires data |
| `sh:equals` / `sh:disjoint` / `sh:lessThan` / `sh:lessThanOrEquals` | Cross-property comparison requires data |
| `sh:uniqueLang` | Requires data |
| `sh:minLength` / `sh:maxLength` | Requires data |
| `sh:closed` / `sh:ignoredProperties` | Open-world assumption; requires data |
| `sh:or` / `sh:and` / `sh:not` / `sh:xone` | Boolean logic over shapes not analysed |
| `sh:targetNode` | Specific-node target requires data |
| `sh:targetSubjectsOf` / `sh:targetObjectsOf` | Property-based target requires data |

## Roadmap

- **Tighter path-chain checks.** Extend range–domain compatibility to inverse and alternative
  path operators.
- **More SHACL structural checks.** Wire `sh:datatype` against `rdfs:range`; flag
  `sh:class` used alongside a datatype `rdfs:range`.
- **`SERVICE` schema hints.** Allow the config to supply an external schema for known
  federated endpoints so SERVICE blocks can be partially validated.
- **Completion in full-IRI position.** Currently completion only fires for `prefix:local`
  tokens. Extend it to `<http://…` literals so users without prefix declarations also get
  suggestions.
- **Go-to-definition.** Jump from a CIM IRI in a query or shape to the corresponding
  declaration in the RDFS profile file.
