# CIMNotebook

[![Visual Studio Marketplace](https://vsmarketplacebadges.dev/version-short/soptim-ag.cimnotebook.svg)](https://marketplace.visualstudio.com/items?itemName=soptim-ag.cimnotebook)

Real-time SPARQL and SHACL validation against CIM/CGMES schema profiles, directly in VS Code.

Write a SPARQL query or SHACL shape and get immediate feedback: unknown classes and properties are underlined, syntax errors are highlighted, and semantic issues like domain/range mismatches are flagged as you type — all resolved against your actual RDFS profile files.

**Install** from the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=soptim-ag.cimnotebook) — search for **CIMNotebook** in the Extensions view, or run `code --install-extension soptim-ag.cimnotebook`.

> 📖 **Full documentation:** <https://opencgmes.soptim.de/cimnotebook/vscode> — including the
> [`opencgmes.jsonc` configuration reference](https://opencgmes.soptim.de/cimvocabcheck/configuration),
> the [validation check catalogue](https://opencgmes.soptim.de/cimvocabcheck/validation-checks),
> [SPARQL Notebook support](https://opencgmes.soptim.de/cimnotebook/sparql-notebooks), and
> [troubleshooting](https://opencgmes.soptim.de/cimnotebook/troubleshooting).

## Features

### Syntax highlighting

Grammar-based highlighting for **SPARQL** (`.rq`, `.sparql`) and **SHACL / Turtle** (`.ttl`, `.shacl`).

### Real-time diagnostics

Every open document is validated against the loaded CIM schema, and findings appear as squiggly underlines: unknown classes and properties, typos in the standard vocabularies (`rdf`, `rdfs`, `owl`, `sh`), domain/range mismatches, datatype conflicts, invalid enumeration values, and contradictory SHACL constraints (cardinality, value ranges, node kinds). The complete list of diagnostic codes and severities is in the
[validation check catalogue](https://opencgmes.soptim.de/cimvocabcheck/validation-checks).

### Hover documentation

Hover over any CIM term (e.g. `cim:ACLineSegment`) to see its full IRI, its `rdfs:label` and `rdfs:comment`, its `rdfs:domain` / `rdfs:range`, and the schema profile(s) it belongs to — read straight from the loaded schema.

### Auto-completion

Typing `:` after a prefix (e.g. `cim:`) suggests all classes and properties in the loaded schema. In object position after an enumeration-ranged property, the enumeration's members are suggested (e.g. `cim:WindGenUnitKind.offshore`). Typing after a standard-vocabulary prefix (`rdf:`, `rdfs:`, `owl:`, `sh:`) suggests that vocabulary's terms (e.g. `sh:minCount`, `sh:NodeShape`, `rdf:type`), so SHACL shapes and SPARQL queries complete the same way.

### Go-to-definition

Press `F12` or `Ctrl+Click` on any CIM IRI to jump directly to its declaration line in the source `.rdf` or `.ttl` profile file. A term declared in several profiles offers all of them, so you pick the profile you meant. When the schema comes from RDFArchitect there is no such file: the term is rendered from the loaded schema into a read-only document, and opening it also shows the term in the RDFArchitect panel — a class opens itself, an attribute or association opens the class declaring it.

### Workspace symbol search

Press `Ctrl+T` (`Cmd+T` on macOS) and type a CIM class or property name to find and navigate to any schema term across the workspace. Matching is partial and case-insensitive — `aclineseg` matches `ACLineSegment`.

### Explain Query

Right-click a SPARQL query (or run **CIMNotebook: Explain Query (Algebra Plan)** from the Command Palette) to see its Jena-style static algebra plan — original and optimized — in a read-only document beside the query. Nothing is executed; the plan is computed from the query text alone. See [Explain Query](https://opencgmes.soptim.de/cimvocabcheck/explain-query).

### RDFArchitect view

[RDFArchitect](https://github.com/SOPTIM/RDFArchitect) is SOPTIM's open-source web editor for RDFS schemas with CIM extensions. Run **CIMNotebook: Open RDFArchitect** to embed a running RDFArchitect instance in an editor panel and browse or edit schema diagrams without leaving VS Code, or **CIMNotebook: Open RDFArchitect in Browser** to open it in the system browser. Right-click a CIM term in a query or shape and choose **Open in RDFArchitect** to jump straight to that class and its package diagram (the class is looked up across the schemas loaded in the RDFArchitect session). Run **CIMNotebook: Send Schema to RDFArchitect** to import the workspace's configured schema files (from `opencgmes.jsonc`) into RDFArchitect as a read-only dataset and open it — no manual import needed; after that, **Open in RDFArchitect** finds every class of your profiles. RDFArchitect is not bundled — point the `cimnotebook.rdfArchitectUrl` setting at a local or hosted instance (e.g. `http://localhost:3000`).

### SPARQL Notebook support

CIMNotebook validates SPARQL **cells** inside [SPARQL Notebook](https://marketplace.visualstudio.com/items?itemName=Zazuko.sparql-notebook) documents, not just `.rq`/`.sparql` files. Each cell is validated independently, and a cell can declare its own schema with the SPARQL Notebook endpoint directive:

```sparql
# [endpoint=./schemas/cgmes-3.0/EquipmentCore.ttl]
SELECT * WHERE { ?s a cim:ACLineSegment }
```

The endpoint can be a **local schema file** (`.ttl`, `.rdf`, `.owl` — resolved relative to the notebook) or a **remote SPARQL endpoint** (`https://…`) holding the CGMES profiles in per-profile named graphs; without a directive, the cell falls back to the workspace schema. Diagnostics, hover, completion, and go-to-definition are all endpoint-aware. See [SPARQL Notebooks](https://opencgmes.soptim.de/cimnotebook/sparql-notebooks) for the full behaviour and known limitations.

## Requirements

- **Java 21 or later** must be available on your system. The extension launches the language server as a Java process.
- **VS Code 1.75** or later.

## Quick start

1. **Open a SPARQL or SHACL file.** Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file — the extension activates and syntax errors are flagged immediately.

2. **Point CIMNotebook at your CGMES profiles.** There is no bundled default schema, so without one validation is syntax-only. Run **CIMNotebook: Create Config File** from the Command Palette (or write the file yourself) to create an `opencgmes.jsonc`:

    ```json
    {
        "cimvocabcheck": {
            "schemasDirectory": "schemas/cgmes-3.0"
        }
    }
    ```

    All settings live under the `"cimvocabcheck"` section; the file is discovered by walking up from each open file (nearest one wins), and comments are allowed. Alternatively, a query can name its own schema with a `# [endpoint=...]` directive (see above).

3. **Edit.** The schema loads in the background — a notification confirms when it is ready — and diagnostics, hover, completion, and navigation light up. The extension watches `opencgmes.jsonc` and reloads the schema automatically whenever it changes.

The `opencgmes.jsonc` format (`schemas`/`schemasDirectory`, `strictness`, `namedGraphs`, `prefixes`, `standardVocabulary`) is documented canonically at
**<https://opencgmes.soptim.de/cimvocabcheck/configuration>**.

## Extension settings

These editor-specific settings live in VS Code's settings. Schema configuration itself lives in `opencgmes.jsonc`, not here.

| Setting                       | Default     | Description                                                                                      |
| ----------------------------- | ----------- | ------------------------------------------------------------------------------------------------ |
| `cimnotebook.serverJar`       | _(bundled)_ | Absolute path to `cimvocabcheck-lsp.jar`. Leave empty to use the JAR bundled with the extension. |
| `cimnotebook.javaExecutable`  | `java`      | Java executable used to launch the language server. Must be Java 21 or later.                    |
| `cimnotebook.javaArgs`        | `[]`        | Extra JVM arguments passed before `-jar`, e.g. `["-Xmx512m"]`.                                   |
| `cimnotebook.trace.server`    | `off`       | LSP message tracing. Set to `messages` or `verbose` to debug communication with the server.      |
| `cimnotebook.rdfArchitectUrl` | _(unset)_   | URL of a running RDFArchitect instance for the **Open RDFArchitect** commands.                   |

## Commands

| Command                                               | Description                                                                                    |
| ----------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **CIMNotebook: Show Output**                          | Opens the CIMNotebook output channel, useful for diagnosing startup and schema loading issues. |
| **CIMNotebook: Explain Query (Algebra Plan)**         | Shows the static SPARQL algebra plan for the current query.                                    |
| **CIMNotebook: Create Config File (opencgmes.jsonc)** | Scaffolds an `opencgmes.jsonc` configuration file.                                             |
| **CIMNotebook: Open RDFArchitect**                    | Embeds the configured RDFArchitect instance in an editor panel.                                |
| **CIMNotebook: Open RDFArchitect in Browser**         | Opens the configured RDFArchitect instance in the system browser.                              |
| **Open in RDFArchitect** _(editor context menu)_      | Opens the schema class under the cursor in RDFArchitect.                                       |
| **CIMNotebook: Send Schema to RDFArchitect**          | Imports the workspace schema into RDFArchitect as a read-only dataset and opens it.            |

## Troubleshooting

**No diagnostics at all** — with no schema configured, CIMNotebook reports only syntax errors; add an `opencgmes.jsonc` or a `# [endpoint=...]` directive for full validation. If even syntax errors are missing, the server likely did not start: open **CIMNotebook: Show Output** and confirm Java 21+ is available (set `cimnotebook.javaExecutable` to a Java 21+ path if needed).

**"Schema load failed"** — open **CIMNotebook: Show Output** to see the full error. Common causes: an incorrect path in `schemasDirectory`/`schemas`, or a malformed RDF file.

**No diagnostics on a `.ttl`/SHACL file (but SPARQL works)** — another RDF/Turtle extension (or a `files.associations` entry) has claimed `.ttl`. CIMNotebook also matches `.ttl`/`.shacl` files by path, so make sure you are on an up-to-date build, or force the language mode to **SHACL** via the status-bar language indicator.

More symptoms and fixes: <https://opencgmes.soptim.de/cimnotebook/troubleshooting>.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The bundled language server includes W3C standard vocabularies (`rdf`, `rdfs`, `owl`, `sh`),
used for standard-vocabulary term checking and redistributed under the W3C Software and
Document License. © World Wide Web Consortium.
