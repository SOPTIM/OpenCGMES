---
title: IntelliJ
sidebar_position: 3
---

import JetBrainsInstall from '@site/src/components/MarketplaceInstall/JetBrainsInstall';

# CIMNotebook for IntelliJ

The IntelliJ plugin gives you real-time SPARQL and SHACL validation against CIM / CGMES schema
profiles directly in IntelliJ-platform IDEs. It is a thin client around
[CIMLangServer](/cimvocabcheck/language-server), wired into the IDE through the
[LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) LSP client.

## Requirements

- **IntelliJ IDEA (or any IntelliJ-platform IDE) 2024.2 or later.** The plugin launches the
  language server on the IDE's bundled Java runtime, which is Java 21+ from 2024.2 onward.
- **[LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij)** — a required dependency,
  installed automatically from the Marketplace.
- **Java 21 or later.** By default the IDE's own runtime is used; you can override this in settings.

## Install

Install **CIMNotebook** from the
[**JetBrains Marketplace**](https://plugins.jetbrains.com/plugin/32789-cimnotebook) — inside the IDE
open **Settings → Plugins → Marketplace**, search for **CIMNotebook**, and click **Install**. LSP4IJ
is a required dependency, and IntelliJ installs it automatically as part of a Marketplace install.

<JetBrainsInstall pluginId={32789} />

:::warning Installing from disk
If you install CIMNotebook from a downloaded `.zip` (**Install Plugin from Disk**), IntelliJ does
**not** resolve Marketplace dependencies — install **LSP4IJ** manually first (**Settings → Plugins
→ Marketplace** → search "LSP4IJ").
:::

After install, point CIMNotebook at your CGMES profiles via an
[`opencgmes.jsonc`](/cimvocabcheck/configuration) (run **Tools → CIMNotebook: Create Config File** to
scaffold one) or a `# [endpoint=...]` directive in the query. Without a schema, validation is
syntax-only — there is no bundled default schema. Then open any `.rq`, `.sparql`, `.ttl`, or
`.shacl` file: the server starts, loads the schema in the background, and begins validating.

## Features

### Syntax highlighting

Lexer-based highlighting for **SPARQL** (`.rq`, `.sparql`) and **SHACL / Turtle** (`.ttl`,
`.shacl`).

:::note The `.ttl` extension
The SHACL file type deliberately claims the generic `.ttl` extension, since ENTSO-E and most tooling
ship SHACL shapes as plain Turtle. If another installed plugin already owns `.ttl`, add the
association under **Settings → Editor → File Types → SHACL**.
:::

### Real-time diagnostics

Every open document is validated against the loaded schema; findings appear as inline underlines —
unknown classes and properties, syntax errors, domain/range mismatches, datatype conflicts, and
invalid SHACL cardinalities. The full list of codes and severities is on the
[Validation checks](/cimvocabcheck/validation-checks) page.

![CIMNotebook diagnostics on a SPARQL query in IntelliJ](/img/cimnotebook/intellij-diagnostics.png)

### Hover documentation

Hover over any CIM term (e.g. `cim:ACLineSegment`) to see its full IRI, its `rdfs:label` and
`rdfs:comment`, and its `rdfs:domain` / `rdfs:range` and declaring profile(s) — read straight from
the loaded schema.

### Auto-completion

Typing `:` after a prefix (e.g. `cim:`) triggers completion suggestions for all classes and
properties in the loaded schema. In object position after an enumeration-ranged property, the
enumeration's members are suggested (e.g. `cim:WindGenUnitKind.offshore`).

![CIMNotebook completion list after typing cim: in IntelliJ](/img/cimnotebook/intellij-completion.png)

### Go to definition

`Ctrl+Click` / `Cmd+Click` (or `Ctrl+B` / `Cmd+B`) on any CIM IRI jumps to its declaration line in
the source `.rdf` or `.ttl` profile file.

### Workspace symbol search

Use **Go to Symbol** (`Ctrl+Alt+Shift+N` on Windows/Linux, `Cmd+Option+O` on macOS) and type a CIM
class or property name to navigate to any schema term. Matching is partial and case-insensitive —
`aclineseg` matches `ACLineSegment`.

### RDFArchitect tool window

[RDFArchitect](https://github.com/SOPTIM/RDFArchitect) is SOPTIM's open-source web editor for RDFS
schemas with CIM extensions. The **RDFArchitect** tool window (also reachable via **Tools →
CIMNotebook: Open RDFArchitect**) embeds a running RDFArchitect instance in the IDE's built-in
browser (JCEF), so you can browse and edit the schema diagrams next to your queries. The tool
window's title bar has **Reload** and **Open in Browser** actions; if the IDE runtime does not
support JCEF, the tool window offers the system browser instead.

RDFArchitect is not bundled — point the **RDFArchitect URL** setting at a local
[docker-compose](https://github.com/SOPTIM/RDFArchitect#quickstart) instance (e.g.
`http://localhost:3000`) or a hosted deployment.

Right-click a CIM term in a SPARQL query or SHACL shape and choose **Open in RDFArchitect** to jump
straight to it: the term under the caret is resolved to its full IRI by the language server, and
RDFArchitect opens the matching model element (`/mainpage?class=<iri>`). It works for every kind of
term a query names — a class such as `cim:ACLineSegment` opens that class and its package diagram,
while an attribute, association or enum entry such as `cim:ACLineSegment.r` opens the class that
**declares** it and highlights that row in the class editor. Terms are looked up across the schemas
loaded in the RDFArchitect session, so import your profiles there once (or load a shared snapshot)
and the jump works from then on.

:::note
Because a property is declared once, jumping to an inherited attribute opens the superclass that
declares it — `cim:Conductor.length` opens `Conductor`, even when you were reading `ACLineSegment`.
Jumping to a property requires an RDFArchitect that supports property deep links (released after
1.2.0); on an older instance the term reports "Not found" and classes keep working.
:::

The import itself can be automated too: **CIMNotebook: Send Schema to RDFArchitect** asks
the language server for the workspace's configured schema files (the `opencgmes.jsonc`
`schemas`/`schemasDirectory`), imports them into RDFArchitect as a **read-only** dataset, and
opens the result as a snapshot in the tool window. The dataset is named after the config file's
directory. After that, **Open in RDFArchitect** finds every term of your profiles without any
manual import. It is available from **Tools** and from the RDFArchitect tool window's toolbar.

You are not expected to remember to run it. When the tool window opens, CIMNotebook checks what
this project last sent to that instance and offers to do it for you:

| Situation                                                          | What you get                        |
| ------------------------------------------------------------------ | ----------------------------------- |
| nothing sent yet, or the instance restarted and lost the snapshot   | *"…schema is not in RDFArchitect yet. Import it?"* |
| the schema files changed since the last send                        | *"…schema changed since it was sent. Update it?"*  |
| the schema is there and unchanged                                   | nothing                              |

The dialog's third button, **Never for This Project**, is remembered in the project's properties,
so a project you always import by hand stays quiet. When the offer follows an **Open in
RDFArchitect** that could not find its term, the import lands on that term afterwards.

Re-sending is safe: every send runs in a fresh RDFArchitect session, so the dataset is rebuilt
from scratch rather than merged into the previous one. Note that the changed-schema check compares
the *files on disk* with what was last sent — edits you make inside RDFArchitect are not detected
(and the imported dataset is read-only precisely so it stays a copy of your profiles).

## Settings

Under **Settings / Preferences → Tools → CIMNotebook**. Schema configuration itself lives in
[`opencgmes.jsonc`](/cimvocabcheck/configuration), not here.

| Setting             | Default         | Description                                                                                                    |
| ------------------- | --------------- | ------------------------------------------------------------------------------------------------------------- |
| **Server JAR**      | _(bundled)_     | Absolute path to `cimvocabcheck-lsp.jar`. Leave empty to use the JAR bundled with the plugin.                 |
| **Java executable** | _(IDE runtime)_ | Java executable used to launch the language server. Must be Java 21+. Leave empty to use the IDE's own runtime. |
| **JVM arguments**   | _(none)_        | Extra JVM arguments passed before `-jar`, e.g. `-Xmx512m`.                                                     |
| **RDFArchitect URL** | _(unset)_      | URL of a running RDFArchitect instance shown in the RDFArchitect tool window.                                  |

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

## Troubleshooting

Common IntelliJ issues — no diagnostics, Java not found, the `.ttl` file-type conflict, and the
server failing to start (use LSP4IJ's **Language Servers** tool window) — are collected on the
[Troubleshooting](/cimnotebook/troubleshooting) page.
