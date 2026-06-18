# CIMNotebook

Real-time SPARQL and SHACL validation against CIM/CGMES schema profiles, directly in VS Code.

Write a SPARQL query or SHACL shape and get immediate feedback: unknown classes and properties are underlined, syntax errors are highlighted, and semantic issues like domain/range mismatches are flagged as you type — all resolved against your actual RDFS profile files.

> 📖 **Full documentation:** <https://opencgmes.soptim.de/cimnotebook/vscode> — features, the
> [`opencgmes.json` configuration reference](https://opencgmes.soptim.de/cimvocabcheck/configuration),
> the [validation check catalogue](https://opencgmes.soptim.de/cimvocabcheck/validation-checks), and
> [SPARQL Notebook support](https://opencgmes.soptim.de/cimnotebook/sparql-notebooks).

## Features

### Syntax highlighting

Provides grammar-based syntax highlighting for:

- **SPARQL** — `.rq`, `.sparql`
- **SHACL / Turtle** — `.ttl`, `.shacl`

### Real-time diagnostics

The language server validates every open document against the loaded CIM schema and reports findings as squiggly underlines:

| Code                                | Severity | Description                                                       |
| ----------------------------------- | -------- | ----------------------------------------------------------------- |
| `SYNTAX_ERROR`                      | Error    | The document is not syntactically valid SPARQL or Turtle          |
| `UNKNOWN_CLASS`                     | Error    | Class IRI not found in the configured schema profiles             |
| `UNKNOWN_PROPERTY`                  | Error    | Property IRI not found in the configured schema profiles          |
| `TERM_EXISTS_IN_OTHER_PROFILE`      | Hint     | Term exists, but in a profile not selected for this graph         |
| `GRAPH_NOT_CONFIGURED`              | Info     | A named graph is used but has no profile mapping configured       |
| `UNSUPPORTED_DYNAMIC_PROPERTY`      | Warning  | Variable predicate — cannot be validated statically               |
| `QUERY_IMPLIED_TYPE`                | Hint     | Subject type is implied by property domain, not stated explicitly |
| `DATATYPE_MISMATCH`                 | Warning  | Literal datatype conflicts with the property's `rdfs:range`       |
| `PROPERTY_NOT_ALLOWED_FOR_CLASS`    | Warning  | Property used on a class that is not in its `rdfs:domain`         |
| `NODE_KIND_INCOMPATIBLE_WITH_RANGE` | Warning  | SHACL `sh:nodeKind` conflicts with the property's `rdfs:range`    |
| `INVALID_CARDINALITY`               | Warning  | SHACL `sh:minCount` exceeds `sh:maxCount`                         |

### Hover documentation

Hover over any CIM term (e.g. `cim:ACLineSegment`) to see its full IRI and the schema profile it belongs to.

### Auto-completion

Typing `:` after a prefix (e.g. `cim:`) triggers completion suggestions for all classes and properties in the loaded schema. Typing after a standard-vocabulary prefix (`rdf:`, `rdfs:`, `owl:`, `sh:`) suggests that vocabulary's terms (e.g. `sh:minCount`, `sh:NodeShape`, `rdf:type`), so SHACL shapes and SPARQL queries complete the same way.

### Go-to-definition

Press `F12` or `Ctrl+Click` on any CIM IRI to jump directly to its declaration line in the source `.rdf` or `.ttl` profile file.

### Workspace symbol search

Press `Ctrl+T` (or `Cmd+T` on macOS) and type a CIM class or property name to find and navigate to any schema term across the workspace. Supports partial, case-insensitive matching (e.g. `aclineseg` matches `ACLineSegment`).

### SPARQL Notebook support

CIMNotebook validates SPARQL **cells** inside [SPARQL Notebook](https://marketplace.visualstudio.com/items?itemName=Zazuko.sparql-notebook) documents, not just `.rq`/`.sparql` files. Each cell is validated independently as you edit it.

A cell can point at the schema to validate against with the SPARQL Notebook endpoint directive:

```sparql
# [endpoint=./schemas/cgmes-3.0/EquipmentCore.ttl]
SELECT * WHERE { ?s a cim:ACLineSegment }
```

- **Local file endpoint** (`./relative/path.ttl`, `.rdf`, `.owl`) — the file is loaded as the schema for that cell. Relative paths resolve against the notebook's own directory.
- **Remote SPARQL endpoint** (`https://…`) — CIMNotebook loads the schema from the endpoint itself. It enumerates the named graphs that hold the CGMES profiles and reads them into the schema index. The schema is fetched in the background; diagnostics appear once it has loaded.
- **No directive** — the cell falls back to the workspace schema (the nearest `opencgmes.json`), exactly like a `.rq` file; with no schema configured, it is checked syntax-only.

The endpoint names _where the CGMES schema lives_ (e.g. an Apache Jena Fuseki server with the RDFS profiles loaded **in per-profile named graphs**); CIMNotebook validates against that schema and never queries live instance data.

## Requirements

- **Java 21 or later** must be available on your system. The extension launches the language server as a Java process.
- **VS Code 1.75** or later.

## Getting started

### 1. Open a SPARQL or SHACL file

Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file. Syntax errors are flagged immediately.
For schema-based validation, point CIMNotebook at your CGMES profiles — via an `opencgmes.json`
(below) or a `# [endpoint=...]` directive in the query. There is no bundled default schema, so
without one of those, validation is syntax-only.

### 2. Configure a schema with `opencgmes.json`

Run **CIMNotebook: Create Config File** from the Command Palette (or write the file yourself).
All settings live under a `"cimvocabcheck"` section; the file is discovered by walking up from each
file (nearest one wins), and comments are allowed. Point it at your RDFS profiles:

```json
{
    "cimvocabcheck": {
        "schemasDirectory": "schemas/cgmes-3.0"
    }
}
```

The extension watches `opencgmes.json` and reloads the schema automatically whenever it changes.

### 3. Open a SPARQL or SHACL file

Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file. The extension activates, loads the schema in the background, and begins validating. A notification confirms when the schema has loaded successfully.

## Configuration

| Setting                      | Default     | Description                                                                                      |
| ---------------------------- | ----------- | ------------------------------------------------------------------------------------------------ |
| `cimnotebook.serverJar`      | _(bundled)_ | Absolute path to `cimvocabcheck-lsp.jar`. Leave empty to use the JAR bundled with the extension. |
| `cimnotebook.javaExecutable` | `java`      | Java executable used to launch the language server. Must be Java 21 or later.                    |
| `cimnotebook.javaArgs`       | `[]`        | Extra JVM arguments passed before `-jar`, e.g. `["-Xmx512m"]`.                                   |
| `cimnotebook.trace.server`   | `off`       | LSP message tracing. Set to `messages` or `verbose` to debug communication with the server.      |

## Validation configuration reference

All validation settings live in an `opencgmes.json` file under a `"cimvocabcheck"` section
(`schemas`/`schemasDirectory`, `strictness`, `namedGraphs`, `prefixes`, `standardVocabulary`). The
file is discovered by walking up from each open file; with no schema configured, validation is
syntax-only.

```json
{
    "cimvocabcheck": {
        "schemasDirectory": "schemas/cgmes-3.0",
        "strictness": "default"
    }
}
```

See the full, canonical reference at
**<https://opencgmes.soptim.de/cimvocabcheck/configuration>**.

## Commands

| Command                      | Description                                                                                    |
| ---------------------------- | ---------------------------------------------------------------------------------------------- |
| **CIMNotebook: Show Output** | Opens the CIMNotebook output channel, useful for diagnosing startup and schema loading issues. |

## Troubleshooting

**The extension shows "Schema load failed"**
Open the CIMNotebook output channel (`CIMNotebook: Show Output`) to see the full error. Common causes: incorrect path in `schemasDirectory`/`schemas`, or a malformed RDF file.

**No diagnostics at all**
With no schema configured, CIMNotebook reports only syntax errors — add an `opencgmes.json` with `schemas`, or a `# [endpoint=...]` directive, for full validation. If even syntax errors are missing, it usually points to a server-launch problem — check **CIMNotebook: Show Output** and confirm Java 21+ is available.

**Java not found or wrong version**
Set `cimnotebook.javaExecutable` to the full path of a Java 21+ executable, e.g. `/usr/lib/jvm/java-21/bin/java`.

**No diagnostics appearing**
Check that the file extension is recognised (`.rq`, `.sparql`, `.ttl`, `.shacl`) and that the schema loaded successfully. The status notification "SPARQL Validation: Schema loaded successfully." confirms the schema is ready.

**No diagnostics on a SHACL/`.ttl` file (but SPARQL works)**
This usually means another installed RDF/Turtle extension (or a `files.associations` entry) has claimed `.ttl`, so the file's language mode is **Turtle** or **Plain Text** instead of **SHACL**. Check the language indicator in the bottom-right of the status bar. CIMNotebook now also matches `.ttl`/`.shacl` files by path, so it validates them regardless of language mode — but make sure you are running an up-to-date build of the extension. You can also force the mode by clicking the language indicator and selecting **SHACL**, or add `"files.associations": { "*.ttl": "shacl" }` to your settings.

## Known Limitations

**Notebook endpoint schemas**
Diagnostics, hover, auto-completion, and go-to-definition are all endpoint-aware: when a cell declares a `# [endpoint=...]`, they use that schema. Go-to-definition on an endpoint term opens a generated read-only Turtle "peek" of the term (fetched from the endpoint), since there is no local file. A schema loaded from an endpoint is cached for the session — edit `opencgmes.json` (which triggers a reload) or reload the window to re-fetch it.

**Remote endpoint schema layout**
Loading a schema from a remote SPARQL endpoint assumes the CGMES profiles are stored in **per-profile named graphs** (graphs that declare `rdfs:Class`/`owl:Ontology`); instance-data graphs are skipped. Endpoints that store the whole schema in the default graph, or mixed with instance data in one graph, are not supported.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The bundled language server includes W3C standard vocabularies (`rdf`, `rdfs`, `owl`, `sh`),
used for standard-vocabulary term checking and redistributed under the W3C Software and
Document License. © World Wide Web Consortium.
