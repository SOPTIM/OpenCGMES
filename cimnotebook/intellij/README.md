<!--
    Copyright (c) 2026 SOPTIM AG

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    SPDX-License-Identifier: Apache-2.0
-->
# CIMNotebook for IntelliJ

Real-time SPARQL and SHACL validation against CIM/CGMES schema profiles, directly in IntelliJ-based IDEs.

Write a SPARQL query or SHACL shape and get immediate feedback: unknown classes and properties are underlined, syntax errors are highlighted, and semantic issues like domain/range mismatches are flagged as you type — all resolved against your actual RDFS profile files.

The plugin is a thin client around the CIMLangServer (`cimvocabcheck-lsp`), wired into the IDE through the [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) LSP client.

> 📖 **Full documentation:** <https://opencgmes.soptim.de/cimnotebook/intellij> — including the
> [`opencgmes.jsonc` configuration reference](https://opencgmes.soptim.de/cimvocabcheck/configuration),
> the [validation check catalogue](https://opencgmes.soptim.de/cimvocabcheck/validation-checks), and
> [troubleshooting](https://opencgmes.soptim.de/cimnotebook/troubleshooting).

## Features

### Syntax highlighting

Lexer-based highlighting for **SPARQL** (`.rq`, `.sparql`) and **SHACL / Turtle** (`.ttl`, `.shacl`).

> The SHACL file type deliberately claims the generic `.ttl` extension, since ENTSO-E and most tooling ship SHACL shapes as plain Turtle. If another installed plugin already owns `.ttl`, add the association under **Settings → Editor → File Types → SHACL**.

### Real-time diagnostics

Every open document is validated against the loaded CIM schema; findings appear as inline underlines: unknown classes and properties, typos in the standard vocabularies (`rdf`, `rdfs`, `owl`, `sh`), domain/range mismatches, datatype conflicts, invalid enumeration values, and contradictory SHACL constraints (cardinality, value ranges, node kinds). The complete list of diagnostic codes and severities is in the
[validation check catalogue](https://opencgmes.soptim.de/cimvocabcheck/validation-checks).

### Hover documentation

Hover over any CIM term (e.g. `cim:ACLineSegment`) to see its full IRI, its `rdfs:label` and `rdfs:comment`, its `rdfs:domain` / `rdfs:range`, and the schema profile(s) it belongs to — read straight from the loaded schema.

### Auto-completion

Typing `:` after a prefix (e.g. `cim:`) triggers completion suggestions for all classes and properties in the loaded schema. In object position after an enumeration-ranged property, the enumeration's members are suggested (e.g. `cim:WindGenUnitKind.offshore`). Typing after a standard-vocabulary prefix (`rdf:`, `rdfs:`, `owl:`, `sh:`) suggests that vocabulary's terms (e.g. `sh:minCount`, `sh:NodeShape`, `rdf:type`).

### Go to definition

`Ctrl+Click` / `Cmd+Click` (or `Ctrl+B` / `Cmd+B`) on any CIM IRI jumps to its declaration line in the source `.rdf` or `.ttl` profile file.

### Workspace symbol search

Use **Go to Symbol** (`Ctrl+Alt+Shift+N` on Windows/Linux, `Cmd+Option+O` on macOS) and type a CIM class or property name to navigate to any schema term. Matching is partial and case-insensitive — `aclineseg` matches `ACLineSegment`.

### Explain Query

Right-click a SPARQL query and choose **Explain Query (Algebra Plan)** to see its Jena-style static algebra plan — original and optimized. Nothing is executed; the plan is computed from the query text alone. See [Explain Query](https://opencgmes.soptim.de/cimvocabcheck/explain-query).

> SPARQL Notebook cell validation is currently a VS Code-only feature — see the
> [feature overview](https://opencgmes.soptim.de/cimnotebook/overview) for per-editor parity.

## Requirements

- **IntelliJ IDEA (or any IntelliJ-platform IDE) 2024.2 or later.** The plugin launches the language server on the IDE's bundled Java runtime, which is Java 21+ from 2024.2 onward.
- **[LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij)** — a required dependency, installed automatically from the Marketplace.
- **Java 21 or later.** By default the IDE's own runtime is used; you can override this in settings.

## Quick start

1. **Install the plugin.** Install **CIMNotebook** from the Marketplace (Settings → Plugins → Marketplace). LSP4IJ is a required dependency and IntelliJ installs it automatically as part of a Marketplace install.

    > If you install CIMNotebook from a downloaded `.zip` instead (Install Plugin from Disk), IntelliJ does **not** resolve Marketplace dependencies — install **LSP4IJ** manually first (Settings → Plugins → Marketplace → search "LSP4IJ").

2. **Point CIMNotebook at your CGMES profiles.** There is no bundled default schema, so without one validation is syntax-only. Run **Tools → CIMNotebook: Create Config File** (or write the file yourself) to create an `opencgmes.jsonc`:

    ```json
    {
      "cimvocabcheck": {
        "schemasDirectory": "schemas/cgmes-3.0"
      }
    }
    ```

    All settings live under the `"cimvocabcheck"` section; the file is discovered by walking up from each open file (nearest one wins), and comments are allowed. Alternatively, a query can name its own schema with a `# [endpoint=...]` directive.

3. **Open a SPARQL or SHACL file.** Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file: the server starts, loads the schema in the background, and begins validating. The file is watched and the schema reloads automatically whenever `opencgmes.jsonc` changes.

The `opencgmes.jsonc` format (`schemas`/`schemasDirectory`, `strictness`, `namedGraphs`, `prefixes`, `standardVocabulary`) is documented canonically at
**<https://opencgmes.soptim.de/cimvocabcheck/configuration>**.

## Settings

Under **Settings / Preferences → Tools → CIMNotebook**. Schema configuration itself lives in `opencgmes.jsonc`, not here.

| Setting | Default | Description |
|---------|---------|-------------|
| **Server JAR** | _(bundled)_ | Absolute path to `cimvocabcheck-lsp.jar`. Leave empty to use the JAR bundled with the plugin. |
| **Java executable** | _(IDE runtime)_ | Java executable used to launch the language server. Must be Java 21+. Leave empty to use the IDE's own runtime. |
| **JVM arguments** | _(none)_ | Extra JVM arguments passed before `-jar`, e.g. `-Xmx512m`. |

## Troubleshooting

**No syntax highlighting / file not recognised** — confirm the file extension is one of `.rq`, `.sparql`, `.ttl`, `.shacl`. If another plugin already claimed `.ttl`, add the association under **Settings → Editor → File Types → SHACL**.

**No diagnostics appearing** — with no schema configured, CIMNotebook reports only syntax errors; add an `opencgmes.jsonc` or a `# [endpoint=...]` directive for full validation. If even syntax errors are missing, the server likely did not start — make sure LSP4IJ is enabled and open the **Language Servers** tool window (provided by LSP4IJ) to see CIMLangServer's status and message log.

**Server fails to start, or "Schema load failed"** — usually a Java problem. Set **Settings → Tools → CIMNotebook → Java executable** to the full path of a Java 21+ executable. The LSP4IJ **Language Servers** console shows the full error.

More symptoms and fixes: <https://opencgmes.soptim.de/cimnotebook/troubleshooting>.

## Building from source

The plugin bundles the language server JAR, which is built by the `cimvocabcheck-lsp` Maven module:

```bash
# 1. Build the language server fat JAR
mvn -f ../../cimvocabcheck/lsp/pom.xml package -DskipTests

# 2. Build the plugin (copies the JAR into the plugin and zips it)
./gradlew buildPlugin

# 3. (Optional) Run the IntelliJ Plugin Verifier
./gradlew verifyPlugin
```

The resulting plugin zip is written to `build/distributions/`.

## License

Apache License 2.0 — see [LICENSE](../../LICENSE).

The bundled language server includes W3C standard vocabularies (`rdf`, `rdfs`, `owl`, `sh`),
used for standard-vocabulary term checking and redistributed under the W3C Software and
Document License. © World Wide Web Consortium.
