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

Typing `:` after a prefix (e.g. `cim:`) triggers completion suggestions for all classes and properties in the loaded schema.

### Go-to-definition

Press `F12` or `Ctrl+Click` on any CIM IRI to jump directly to its declaration line in the source `.rdf` or `.ttl` profile file.

### Workspace symbol search

Press `Ctrl+T` (or `Cmd+T` on macOS) and type a CIM class or property name to find and navigate to any schema term across the workspace. Supports partial, case-insensitive matching (e.g. `aclineseg` matches `ACLineSegment`).

## Requirements

- **Java 21 or later** must be available on your system. The extension launches the language server as a Java process.
- **VS Code 1.75** or later.

## Getting started

### 1. Create a configuration file

Create a file at `.cgmes/validation.json` in your workspace root. This file tells the extension where your RDFS profile files are located.

**Example — directory of profiles:**

```json
{
  "schemasDirectory": "schemas/cgmes-3.0"
}
```

**Example — individual files:**

```json
{
  "schemas": [
    "schemas/EquipmentCore.rdf",
    "schemas/Topology.rdf"
  ]
}
```

The extension watches this file and reloads the schema automatically whenever it changes.

### 2. Open a SPARQL or SHACL file

Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file. The extension activates, loads the schema in the background, and begins validating. A notification confirms when the schema has loaded successfully.

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `cimcheck.serverJar` | _(bundled)_ | Absolute path to `cimcheck-lsp.jar`. Leave empty to use the JAR bundled with the extension. |
| `cimcheck.javaExecutable` | `java` | Java executable used to launch the language server. Must be Java 21 or later. |
| `cimcheck.javaArgs` | `[]` | Extra JVM arguments passed before `-jar`, e.g. `["-Xmx512m"]`. |
| `cimcheck.trace.server` | `off` | LSP message tracing. Set to `messages` or `verbose` to debug communication with the server. |

## Validation configuration reference

The `.cgmes/validation.json` file supports these fields:

```json
{
  "schemasDirectory": "path/to/profiles",
  "schemas": ["path/to/Profile.rdf"],
  "strictness": "default",
  "namedGraphs": {
    "EQ": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
    "TP": ["http://iec.ch/TC57/ns/CIM/Topology-EU/3.0"]
  }
}
```

Either `schemasDirectory` or `schemas` is required (or both).

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

## Commands

| Command | Description |
|---------|-------------|
| **CIMcheck: Show Output** | Opens the CIMcheck output channel, useful for diagnosing startup and schema loading issues. |

## Troubleshooting

**The extension shows "Schema load failed"**
Open the CIMcheck output channel (`CIMcheck: Show Output`) to see the full error. Common causes: incorrect path in `schemasDirectory`/`schemas`, or a malformed RDF file.

**"No .cgmes/validation.json found"**
The extension looks for this file in your workspace root. Create it with at least a `schemasDirectory` or `schemas` entry.

**Java not found or wrong version**
Set `cimcheck.javaExecutable` to the full path of a Java 21+ executable, e.g. `/usr/lib/jvm/java-21/bin/java`.

**No diagnostics appearing**
Check that the file extension is recognised (`.rq`, `.sparql`, `.ttl`, `.shacl`) and that the schema loaded successfully. The status notification "SPARQL Validation: Schema loaded successfully." confirms the schema is ready.

## Known Limitations

**Standard vocabulary terms are not validated**
Terms from well-known standard namespaces (`rdf:`, `rdfs:`, `owl:`, `xsd:`, `sh:`, `dcat:`, `dcterms:`, `skos:`, `cims:`, `cimuml:`) are silently accepted regardless of whether the exact term is defined in those vocabularies. A typo like `rdfs:Classs` will not be flagged. This is intentional — these vocabularies are not part of CIM profile files, so the schema index has no information about them.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
