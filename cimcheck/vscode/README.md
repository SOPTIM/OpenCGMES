# CIMcheck

Real-time SPARQL and SHACL validation against CIM/CGMES schema profiles, directly in VS Code.

Write a SPARQL query or SHACL shape and get immediate feedback: unknown classes and properties are underlined, syntax errors are highlighted, and semantic issues like domain/range mismatches are flagged as you type — all resolved against your actual RDFS profile files.

## Features

### Syntax highlighting

Provides grammar-based syntax highlighting for:

- **SPARQL** — `.rq`, `.sparql`
- **SHACL / Turtle** — `.ttl`, `.shacl`

### Real-time diagnostics

The language server validates every open document against the loaded CIM schema and reports findings as squiggly underlines:

| Code | Severity | Description |
|------|----------|-------------|
| `SYNTAX_ERROR` | Error | The document is not syntactically valid SPARQL or Turtle |
| `UNKNOWN_CLASS` | Error | Class IRI not found in the configured schema profiles |
| `UNKNOWN_PROPERTY` | Error | Property IRI not found in the configured schema profiles |
| `TERM_EXISTS_IN_OTHER_PROFILE` | Hint | Term exists, but in a profile not selected for this graph |
| `GRAPH_NOT_CONFIGURED` | Info | A named graph is used but has no profile mapping configured |
| `UNSUPPORTED_DYNAMIC_PROPERTY` | Warning | Variable predicate — cannot be validated statically |
| `QUERY_IMPLIED_TYPE` | Hint | Subject type is implied by property domain, not stated explicitly |
| `DATATYPE_MISMATCH` | Warning | Literal datatype conflicts with the property's `rdfs:range` |
| `PROPERTY_NOT_ALLOWED_FOR_CLASS` | Warning | Property used on a class that is not in its `rdfs:domain` |
| `NODE_KIND_INCOMPATIBLE_WITH_RANGE` | Warning | SHACL `sh:nodeKind` conflicts with the property's `rdfs:range` |
| `INVALID_CARDINALITY` | Warning | SHACL `sh:minCount` exceeds `sh:maxCount` |

### Hover documentation

Hover over any CIM term (e.g. `cim:ACLineSegment`) to see its full IRI and the schema profile it belongs to.

### Auto-completion

Typing `:` after a prefix (e.g. `cim:`) triggers completion suggestions for all classes and properties in the loaded schema. Typing after a standard-vocabulary prefix (`rdf:`, `rdfs:`, `owl:`, `sh:`) suggests that vocabulary's terms (e.g. `sh:minCount`, `sh:NodeShape`, `rdf:type`), so SHACL shapes and SPARQL queries complete the same way.

### Go-to-definition

Press `F12` or `Ctrl+Click` on any CIM IRI to jump directly to its declaration line in the source `.rdf` or `.ttl` profile file.

### Workspace symbol search

Press `Ctrl+T` (or `Cmd+T` on macOS) and type a CIM class or property name to find and navigate to any schema term across the workspace. Supports partial, case-insensitive matching (e.g. `aclineseg` matches `ACLineSegment`).

### SPARQL Notebook support

CIMcheck validates SPARQL **cells** inside [SPARQL Notebook](https://marketplace.visualstudio.com/items?itemName=Zazuko.sparql-notebook) documents, not just `.rq`/`.sparql` files. Each cell is validated independently as you edit it.

A cell can point at the schema to validate against with the SPARQL Notebook endpoint directive:

```sparql
# [endpoint=./schemas/cgmes-3.0/EquipmentCore.ttl]
SELECT * WHERE { ?s a cim:ACLineSegment }
```

- **Local file endpoint** (`./relative/path.ttl`, `.rdf`, `.owl`) — the file is loaded as the schema for that cell. Relative paths resolve against the notebook's own directory.
- **Remote SPARQL endpoint** (`https://…`) — CIMcheck loads the schema from the endpoint itself. It enumerates the named graphs that hold the CGMES profiles and reads them into the schema index. The schema is fetched in the background; diagnostics appear once it has loaded.
- **No directive** — the cell falls back to the workspace schema (the nearest `opencgmes.json`, or the bundled CGMES 3.0 default), exactly like a `.rq` file.

The endpoint names *where the CGMES schema lives* (e.g. an Apache Jena Fuseki server with the RDFS profiles loaded **in per-profile named graphs**); CIMcheck validates against that schema and never queries live instance data.

## Requirements

- **Java 21 or later** must be available on your system. The extension launches the language server as a Java process.
- **VS Code 1.75** or later.

## Getting started

### 1. Open a SPARQL or SHACL file — that's it

No configuration is required. CIMcheck validates against the **CGMES 3.0 RDFS profiles
bundled with the extension** (from the ENTSO-E
[Application Profiles Library](https://github.com/entsoe/application-profiles-library),
Apache-2.0). Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file and diagnostics appear.

### 2. (Optional) Customise with `opencgmes.json`

To use your own profiles or tune validation, run **CIMcheck: Create Config File** from the
Command Palette (or write the file yourself). All settings live under a `"cimcheck"` section;
the file is discovered by walking up from each file (nearest one wins), and comments are
allowed. To use your own RDFS profiles instead of the bundled ones:

```json
{
  "cimcheck": {
    "schemasDirectory": "schemas/cgmes-3.0"
  }
}
```

The extension watches `opencgmes.json` and reloads the schema automatically whenever it changes.

### 3. Open a SPARQL or SHACL file

Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file. The extension activates, loads the schema in the background, and begins validating. A notification confirms when the schema has loaded successfully.

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `cimcheck.serverJar` | _(bundled)_ | Absolute path to `cimcheck-lsp.jar`. Leave empty to use the JAR bundled with the extension. |
| `cimcheck.javaExecutable` | `java` | Java executable used to launch the language server. Must be Java 21 or later. |
| `cimcheck.javaArgs` | `[]` | Extra JVM arguments passed before `-jar`, e.g. `["-Xmx512m"]`. |
| `cimcheck.trace.server` | `off` | LSP message tracing. Set to `messages` or `verbose` to debug communication with the server. |

## Validation configuration reference

The `opencgmes.json` file nests all CIMcheck settings under a `"cimcheck"` section:

```json
{
  "cimcheck": {
    "schemasDirectory": "path/to/profiles",
    "schemas": ["path/to/Profile.rdf"],
    "strictness": "default",
    "namedGraphs": {
      "EQ": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
      "TP": ["http://iec.ch/TC57/ns/CIM/Topology-EU/3.0"]
    },
    "prefixes": {
      "rdf":  "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
      "cim":  "http://iec.ch/TC57/CIM100#"
    }
  }
}
```

All fields are optional. When neither `schemasDirectory` nor `schemas` is set, the bundled
CGMES 3.0 profiles are used.

### `strictness`

Controls which findings are reported and how severities are mapped:

| Level | Behaviour |
|-------|-----------|
| `permissive` | Only syntax errors and unknown-term findings. Semantic checks and hints are suppressed. Useful for exploratory queries against an incomplete schema. |
| `default` | All checks, original severities. |
| `strict` | All checks; warnings are promoted to errors. Recommended for CI. |
| `pedantic` | All checks; warnings and hints are promoted to errors. |

### `namedGraphs`

Maps named graph IRIs to one or more profile version IRIs. When set, terms inside a
`GRAPH <iri> {}` block are validated against the mapped profiles only, rather than all
loaded profiles. Graphs not listed here produce a `GRAPH_NOT_CONFIGURED` warning diagnostic.

When `namedGraphs` is **not** configured (the default), validation runs against all profiles
and no `GRAPH_NOT_CONFIGURED` diagnostics are emitted — useful when your queries use
`GRAPH` blocks without a fixed graph-to-profile mapping.

Each value is an **array** of profile IRIs, so a graph can span multiple profiles. The key
can be a full absolute IRI or a **short relative name** that matches how you write the graph
in the query: if your query says `FROM NAMED <EQ>` or `GRAPH <EQ> {}`, the config key
`"EQ"` will match it.

```json
"namedGraphs": {
  "EQ": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
  "TP": ["http://iec.ch/TC57/ns/CIM/Topology-EU/3.0"],
  "urn:uuid:my-ssh-graph": [
    "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0"
  ]
}
```

### `prefixes`

Default PREFIX declarations automatically injected into every SPARQL query or update that
does not already declare them. When this field is **absent**, the built-in set is used:

| Prefix | Namespace |
|--------|-----------|
| `rdf`  | `http://www.w3.org/1999/02/22-rdf-syntax-ns#` |
| `rdfs` | `http://www.w3.org/2000/01/rdf-schema#` |
| `owl`  | `http://www.w3.org/2002/07/owl#` |
| `xsd`  | `http://www.w3.org/2001/XMLSchema#` |
| `sh`   | `http://www.w3.org/ns/shacl#` |
| `cim`  | `http://iec.ch/TC57/CIM100#` |
| `md`   | `http://iec.ch/TC57/61970-552/ModelDescription/1#` |

Setting `"prefixes"` to an explicit object **replaces** the built-in set entirely. Use an
empty object `{}` to disable all automatic prefix injection.

Prefixes already declared inside the query file are never overwritten.

### `standardVocabulary`

Controls checking of terms in the closed standard vocabularies (`rdf`, `rdfs`, `owl`, `sh`):

| Value | Behaviour |
|-------|-----------|
| `check` (default) | Terms are validated against the official W3C vocabularies. Typos such as `rdf:typ` or `sh:minCountt` are reported as `UNKNOWN_VOCABULARY_TERM` errors. |
| `ignore` | Terms in these namespaces are accepted without inspection (legacy behaviour). |

Open annotation/datatype namespaces (`xsd`, `dcterms`, `dc`, `skos`, `dcat`, and the IEC
extension namespaces) are always accepted regardless of this setting.

## Commands

| Command | Description |
|---------|-------------|
| **CIMcheck: Show Output** | Opens the CIMcheck output channel, useful for diagnosing startup and schema loading issues. |

## Troubleshooting

**The extension shows "Schema load failed"**
Open the CIMcheck output channel (`CIMcheck: Show Output`) to see the full error. Common causes: incorrect path in `schemasDirectory`/`schemas`, or a malformed RDF file.

**No diagnostics at all**
Validation needs no config (it uses the bundled CGMES 3.0 schemas), so this usually points to a server-launch problem — check **CIMcheck: Show Output** and confirm Java 21+ is available.

**Java not found or wrong version**
Set `cimcheck.javaExecutable` to the full path of a Java 21+ executable, e.g. `/usr/lib/jvm/java-21/bin/java`.

**No diagnostics appearing**
Check that the file extension is recognised (`.rq`, `.sparql`, `.ttl`, `.shacl`) and that the schema loaded successfully. The status notification "SPARQL Validation: Schema loaded successfully." confirms the schema is ready.

**No diagnostics on a SHACL/`.ttl` file (but SPARQL works)**
This usually means another installed RDF/Turtle extension (or a `files.associations` entry) has claimed `.ttl`, so the file's language mode is **Turtle** or **Plain Text** instead of **SHACL**. Check the language indicator in the bottom-right of the status bar. CIMcheck now also matches `.ttl`/`.shacl` files by path, so it validates them regardless of language mode — but make sure you are running an up-to-date build of the extension. You can also force the mode by clicking the language indicator and selecting **SHACL**, or add `"files.associations": { "*.ttl": "shacl" }` to your settings.

## Known Limitations

**Notebook endpoint schemas: diagnostics only**
Inside notebook cells, hover, auto-completion, and go-to-definition currently use the workspace schema (the nearest `opencgmes.json`, or the bundled default), not the per-cell `# [endpoint=...]` schema. Diagnostics (squiggles) are fully endpoint-aware. A schema loaded from an endpoint is cached for the session — edit `opencgmes.json` (which triggers a reload) or reload the window to re-fetch it.

**Remote endpoint schema layout**
Loading a schema from a remote SPARQL endpoint assumes the CGMES profiles are stored in **per-profile named graphs** (graphs that declare `rdfs:Class`/`owl:Ontology`); instance-data graphs are skipped. Endpoints that store the whole schema in the default graph, or mixed with instance data in one graph, are not supported.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
