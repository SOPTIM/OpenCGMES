<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements. See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# CIMNotebook for IntelliJ

Real-time SPARQL and SHACL validation against CIM/CGMES schema profiles, directly in IntelliJ-based IDEs.

Write a SPARQL query or SHACL shape and get immediate feedback: unknown classes and properties are underlined, syntax errors are highlighted, and semantic issues like domain/range mismatches are flagged as you type — all resolved against your actual RDFS profile files.

The plugin is a thin client around the CIMLangServer (`cimvocabcheck-lsp`), wired into the IDE through the [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) LSP client.

> 📖 **Full documentation:** <https://opencgmes.soptim.de/cimnotebook/intellij> — features, the
> [`opencgmes.json` configuration reference](https://opencgmes.soptim.de/cimvocabcheck/configuration),
> and the [validation check catalogue](https://opencgmes.soptim.de/cimvocabcheck/validation-checks).

## Requirements

- **IntelliJ IDEA (or any IntelliJ-platform IDE) 2024.2 or later.** The plugin launches the language server on the IDE's bundled Java runtime, which is Java 21+ from 2024.2 onward.
- **[LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij)** — a required dependency, installed automatically from the Marketplace.
- **Java 21 or later.** By default the IDE's own runtime is used; you can override this in settings.

## Getting started

### 1. Install the plugin (and LSP4IJ)

Install **CIMNotebook** from the Marketplace (Settings → Plugins → Marketplace). LSP4IJ is a required dependency and IntelliJ installs it automatically as part of a Marketplace install.

> If you install CIMNotebook from a downloaded `.zip` instead (Install Plugin from Disk), IntelliJ does **not** resolve Marketplace dependencies — install **LSP4IJ** manually first (Settings → Plugins → Marketplace → search "LSP4IJ").

### 2. Configure a schema

For schema-based validation, point CIMNotebook at your CGMES profiles — via an `opencgmes.json`
or a `# [endpoint=...]` directive in the query. There is no bundled default schema, so without
one of those CIMNotebook checks syntax only. Run **Tools → CIMNotebook: Create Config File** to
scaffold an `opencgmes.json`, or create it by hand.

All settings live under a `"cimvocabcheck"` section. CIMNotebook discovers the nearest `opencgmes.json`
by walking up from each file, and JSON comments are allowed. To use your own RDFS profiles:

```json
{
  "cimvocabcheck": {
    "schemasDirectory": "schemas/cgmes-3.0"
  }
}
```

The file is watched and the schema reloads automatically whenever it changes.

### 3. Open a SPARQL or SHACL file

Open any `.rq`, `.sparql`, `.ttl`, or `.shacl` file. The server starts, loads the schema in the background, and begins validating.

## Features

### Syntax highlighting

Lexer-based highlighting for:

- **SPARQL** — `.rq`, `.sparql`
- **SHACL / Turtle** — `.ttl`, `.shacl`

> The SHACL file type deliberately claims the generic `.ttl` extension, since ENTSO-E and most tooling ship SHACL shapes as plain Turtle. If another installed plugin already owns `.ttl`, add the association under **Settings → Editor → File Types → SHACL**.

### Real-time diagnostics

Every open document is validated against the loaded CIM schema; findings appear as inline underlines:

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

### Go to definition

`Ctrl+Click` / `Cmd+Click` (or `Ctrl+B` / `Cmd+B`) on any CIM IRI jumps to its declaration line in the source `.rdf` or `.ttl` profile file.

### Workspace symbol search

Use **Go to Symbol** (`Ctrl+Alt+Shift+N` on Windows/Linux, `Cmd+Option+O` on macOS) and type a CIM class or property name to navigate to any schema term. Supports partial, case-insensitive matching (e.g. `aclineseg` matches `ACLineSegment`).

## Settings

Under **Settings / Preferences → Tools → CIMNotebook**:

| Setting | Default | Description |
|---------|---------|-------------|
| **Server JAR** | _(bundled)_ | Absolute path to `cimvocabcheck-lsp.jar`. Leave empty to use the JAR bundled with the plugin. |
| **Java executable** | _(IDE runtime)_ | Java executable used to launch the language server. Must be Java 21+. Leave empty to use the IDE's own runtime. |
| **JVM arguments** | _(none)_ | Extra JVM arguments passed before `-jar`, e.g. `-Xmx512m`. |

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

## Troubleshooting

**No syntax highlighting / file not recognised**
Confirm the file extension is one of `.rq`, `.sparql`, `.ttl`, `.shacl`. If another plugin already claimed `.ttl`, add the association under **Settings → Editor → File Types → SHACL**.

**No diagnostics appearing**
With no schema configured, CIMNotebook reports only syntax errors — add an `opencgmes.json` with `schemas`, or a `# [endpoint=...]` directive, for full validation. If even syntax errors are missing, the server likely did not start — make sure LSP4IJ is enabled. Open the **Language Servers** tool window (provided by LSP4IJ) to see CIMLangServer's status and message log — this is the IntelliJ equivalent of an LSP output channel and the best place to diagnose startup and schema-loading issues.

**Server fails to start, or "Schema load failed"**
Usually a Java problem. Set **Settings → Tools → CIMNotebook → Java executable** to the full path of a Java 21+ executable, e.g. `/usr/lib/jvm/java-21/bin/java`. The LSP4IJ **Language Servers** console shows the full error.

## Known limitations

**Open vocabularies are not term-checked.** Terms in the *closed* standard vocabularies (`rdf`,
`rdfs`, `owl`, `sh`) **are** validated against the official W3C vocabularies — a typo like
`rdfs:Classs` or `sh:minCountt` is flagged as `UNKNOWN_VOCABULARY_TERM` (set
`"standardVocabulary": "ignore"` in `opencgmes.json` to turn this off). Terms in *open*
annotation/datatype namespaces (`xsd`, `dcterms`, `dc`, `skos`, `dcat`, and the IEC extension
namespaces) are always accepted without inspection, since these are open vocabularies the schema
index has no closed list for.

## Building from source

The plugin bundles the language server JAR, which is built by the `cimvocabcheck-lsp` Maven module:

```bash
# 1. Build the language server fat JAR
mvn -f ../lsp/pom.xml package -DskipTests

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
