---
title: VS Code
sidebar_position: 2
---

# CIMNotebook for VS Code

The VS Code extension gives you real-time SPARQL and SHACL validation against CIM / CGMES schema
profiles directly in the editor. It is a thin client around
[CIMLangServer](/cimvocabcheck/language-server): the extension registers the file types and
settings, and the server provides every diagnostic, hover, completion, and definition.

## Requirements

- **Java 21 or later** on your `PATH` (or configured via `cimnotebook.javaExecutable`). The
  extension launches the language server as a Java process.
- **VS Code 1.75** or later.

## Install

Install from a packaged `.vsix`:

```bash
code --install-extension cimnotebook-<version>.vsix
```

Or from the VS Code UI: open the **Extensions** view, click the `...` menu → **Install from
VSIX…**, and pick the file. Reload the window if prompted.

:::tip First run
There is no bundled default schema. Open a `.rq`, `.sparql`, `.ttl`, or `.shacl` file to activate
the extension, then point it at your profiles with an
[`opencgmes.json`](/cimvocabcheck/configuration) — run **CIMNotebook: Create Config File** from the
Command Palette to scaffold one. Without a schema, validation is syntax-only.
:::

## Features

### Syntax highlighting

Grammar-based highlighting for **SPARQL** (`.rq`, `.sparql`) and **SHACL / Turtle** (`.ttl`,
`.shacl`).

### Real-time diagnostics

Every open document is validated against the loaded schema and findings appear as squiggly
underlines — unknown classes and properties, syntax errors, domain/range mismatches, datatype
conflicts, and invalid SHACL cardinalities. The complete list of codes and severities is on the
[Validation checks](/cimvocabcheck/validation-checks) page.

![CIMNotebook diagnostics on a SPARQL query in VS Code](/img/cimnotebook/vscode-diagnostics.png)
{/* TODO image: squiggly underlines for UNKNOWN_PROPERTY + hover tooltip showing the diagnostic message */}

### Hover documentation

Hover over any CIM term (e.g. `cim:ACLineSegment`) to see its full IRI and the schema profile it
belongs to.

![CIMNotebook hover tooltip showing a CIM term's IRI and profile in VS Code](/img/cimnotebook/vscode-hover.png)
{/* TODO image: hover card over cim:ACLineSegment with full IRI + source profile name */}

### Auto-completion

Typing `:` after a prefix (e.g. `cim:`) suggests all classes and properties in the loaded schema.
Typing after a standard-vocabulary prefix (`rdf:`, `rdfs:`, `owl:`, `sh:`) suggests that
vocabulary's terms (e.g. `sh:minCount`, `sh:NodeShape`, `rdf:type`), so SHACL shapes and SPARQL
queries complete the same way.

![CIMNotebook completion list after typing cim: in VS Code](/img/cimnotebook/vscode-completion.png)
{/* TODO image: completion popup listing CIM classes/properties after typing "cim:" */}

### Go-to-definition

Press `F12` or `Ctrl+Click` on any CIM IRI to jump directly to its declaration line in the source
`.rdf` or `.ttl` profile file.

### Workspace symbol search

Press `Ctrl+T` (`Cmd+T` on macOS) and type a CIM class or property name to navigate to any schema
term across the workspace. Matching is partial and case-insensitive — `aclineseg` matches
`ACLineSegment`.

### SPARQL Notebook support

CIMNotebook validates SPARQL **cells** inside
[SPARQL Notebook](https://marketplace.visualstudio.com/items?itemName=Zazuko.sparql-notebook)
documents, not just `.rq` / `.sparql` files. Each cell is validated independently, and a cell can
declare its own schema with a `# [endpoint=...]` directive. See
[SPARQL Notebooks](/cimnotebook/sparql-notebooks) for the full behaviour.

## Settings

These editor-specific settings live in VS Code's settings (`settings.json` or the Settings UI).
Schema configuration itself lives in [`opencgmes.json`](/cimvocabcheck/configuration), not here.

| Setting                      | Default     | Description                                                                                      |
| ---------------------------- | ----------- | ----------------------------------------------------------------------------------------------- |
| `cimnotebook.serverJar`      | _(bundled)_ | Absolute path to `cimvocabcheck-lsp.jar`. Leave empty to use the JAR bundled with the extension. |
| `cimnotebook.javaExecutable` | `java`      | Java executable used to launch the language server. Must be Java 21 or later.                    |
| `cimnotebook.javaArgs`       | `[]`        | Extra JVM arguments passed before `-jar`, e.g. `["-Xmx512m"]`.                                   |
| `cimnotebook.trace.server`   | `off`       | LSP message tracing. Set to `messages` or `verbose` to debug communication with the server.      |

:::note Applying changes
Changing a server-launch setting (`serverJar`, `javaExecutable`, `javaArgs`) requires a window
reload — VS Code prompts you to reload when one changes.
:::

The **CIMNotebook: Show Output** command opens the extension's output channel, the first place to
look when diagnosing startup or schema-loading issues.

## Building the VSIX

The extension bundles the language server JAR, which is produced by the `cimvocabcheck-lsp` Maven
module. From a checkout:

```bash
# 1. Build the language server fat JAR
mvn -f cimvocabcheck/lsp/pom.xml package -DskipTests

# 2. Build and package the extension into a .vsix
cd cimnotebook/vscode
npm install
npm run package
```

The resulting `cimnotebook-<version>.vsix` can be installed with
`code --install-extension`.

## Troubleshooting

Common VS Code issues — no diagnostics, Java not found, the `.ttl` language-mode conflict, schema
load failures — are collected on the [Troubleshooting](/cimnotebook/troubleshooting) page.
