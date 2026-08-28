---
title: VS Code
sidebar_position: 2
---

import VSCodeInstall from '@site/src/components/MarketplaceInstall/VSCodeInstall';

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

<VSCodeInstall />

Install from the
[**Visual Studio Marketplace**](https://marketplace.visualstudio.com/items?itemName=soptim-ag.cimnotebook):
open the **Extensions** view, search for **CIMNotebook**, and click **Install** — or from the
command line:

```bash
code --install-extension soptim-ag.cimnotebook
```

Alternatively, install from a packaged `.vsix`:

```bash
code --install-extension cimnotebook-<version>.vsix
```

Or from the VS Code UI: open the **Extensions** view, click the `...` menu → **Install from
VSIX…**, and pick the file. Reload the window if prompted.

:::tip First run
There is no bundled default schema. Open a `.rq`, `.sparql`, `.ttl`, or `.shacl` file to activate
the extension, then point it at your profiles with an
[`opencgmes.jsonc`](/cimvocabcheck/configuration) — run **CIMNotebook: Create Config File** from the
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

### Hover documentation

Hover over any CIM term (e.g. `cim:ACLineSegment`) to see its full IRI, its `rdfs:label` and
`rdfs:comment`, and its `rdfs:domain` / `rdfs:range` and declaring profile(s) — read straight from
the loaded schema.

![CIMNotebook hover tooltip showing a CIM term's IRI and profile in VS Code](/img/cimnotebook/vscode-hover.png)

### Auto-completion

Typing `:` after a prefix (e.g. `cim:`) suggests all classes and properties in the loaded schema.
In object position after an enumeration-ranged property, the enumeration's members are suggested
(e.g. `cim:WindGenUnitKind.offshore`). Typing after a standard-vocabulary prefix (`rdf:`, `rdfs:`,
`owl:`, `sh:`) suggests that vocabulary's terms (e.g. `sh:minCount`, `sh:NodeShape`, `rdf:type`), so
SHACL shapes and SPARQL queries complete the same way.

![CIMNotebook completion list after typing cim: in VS Code](/img/cimnotebook/vscode-completion.png)

### Go-to-definition

Press `F12` or `Ctrl+Click` on any CIM IRI to jump directly to its declaration line in the source
`.rdf` or `.ttl` profile file. For a schema loaded from a remote SPARQL endpoint, which has no
source file, the term's triples are fetched and opened as a generated read-only Turtle document
instead — one per profile, so each reads as that profile's definition rather than as a merge of all
of them.

A CIM term is usually declared in **several profiles**. All of them are offered: VS Code shows its
peek list, and you pick the profile you meant. The list is ordered by profile version IRI, so the
same one is on top every time.

When the schema comes from [RDFArchitect](#live-datasets) there are no schema files either, so the
term is rendered from the loaded schema into a read-only document — one per profile, as above — and
**opening it also shows the term in the RDFArchitect panel**: a class opens itself, an attribute,
association or enum entry opens the class that declares it, with the row highlighted. The panel
opens on the profile you picked, rather than on whichever graph RDFArchitect happens to find the
term in first.

### Workspace symbol search

Press `Ctrl+T` (`Cmd+T` on macOS) and type a CIM class or property name to navigate to any schema
term across the workspace. Matching is partial and case-insensitive — `aclineseg` matches
`ACLineSegment`.

### CIM Notebooks

CIMNotebook opens git-friendly **markdown notebooks** (`*.cimnb.md`, or any `*.md` via
_Open With…_) whose ` ```sparql ` / ` ```shacl ` code blocks become notebook cells,
and reads/writes Zazuko's `.sparqlbook` format for interop. The command
**CIMNotebook: Convert Notebook** switches a notebook between the two formats. Cells run
against SPARQL endpoints, local RDF/CIMXML files, or named connections — the cell's
status-bar button and **CIMNotebook: Set Cell Endpoint…** pick the target, and
**CIMNotebook: Set/Clear Connection Credentials…** manage basic-auth secrets. See
[CIM Notebooks](/cimnotebook/notebooks).

### Configuration sidebar

The **CIMNotebook** icon in the activity bar opens three native, collapsible sections for
the workspace's [`opencgmes.jsonc`](/cimvocabcheck/configuration): **Connections**,
**Validation** (strictness, standard-vocabulary check, schemas directory, schema files,
[RDFArchitect model](#rdfarchitect-view)), and **Notebook Execution** (query timeout,
max rows). Each row shows its current value —
click a row to edit it with a QuickPick or input box, use the `+` button in a section's
title bar (or on **Schema files**) to add an entry, and hover a row for inline edit /
remove / set-default / credentials actions. Adding a schema file or a connection's data
file offers fuzzy, search-as-you-type file matching over the workspace, with a
**Browse…** fallback for files outside it. Every edit patches only the changed value
through the same comment-preserving path edits as hand-editing, so the sidebar and manual
changes to the file coexist. Without a config file yet, the sections show a **Create
Config File** prompt instead. The sections follow the active document's _nearest_ config,
the same discovery validation uses — the **Connections** section header shows which
config file that is.

### RDFArchitect view

[RDFArchitect](https://github.com/SOPTIM/RDFArchitect) is SOPTIM's open-source web editor for RDFS
schemas with CIM extensions. The **CIMNotebook: Open RDFArchitect** command embeds a running
RDFArchitect instance in an editor panel, so you can browse and edit the schema diagrams without
leaving VS Code; **CIMNotebook: Open RDFArchitect in Browser** opens the same instance in your
system browser instead.

RDFArchitect is not bundled — point the `cimnotebook.rdfArchitectUrl` setting at a local
[docker-compose](https://github.com/SOPTIM/RDFArchitect#quickstart) instance (e.g.
`http://localhost:3000`) or a hosted deployment. The first invocation prompts for the URL and saves
it.

Right-click a CIM term in a SPARQL query or SHACL shape and choose **Open in RDFArchitect** to jump
straight to it: the term under the cursor is resolved to its full IRI by the language server, and
RDFArchitect opens the matching model element (`/mainpage?class=<iri>`). It works for every kind of
term a query names — a class such as `cim:ACLineSegment` opens that class and its package diagram,
while an attribute, association or enum entry such as `cim:ACLineSegment.r` opens the class that
**declares** it and highlights that row in the class editor. Terms are looked up across the schemas
loaded in the RDFArchitect session, so import your profiles there once (or load a shared snapshot)
and the jump works from then on.

When the schema itself comes from RDFArchitect — `rdfArchitect` in
[`opencgmes.jsonc`](/cimvocabcheck/configuration#rdfarchitect), or a `# [rdfarchitect=...]` directive
— plain `Ctrl+Click` does the same thing, no right-click needed, and both ask which profile to open
a term declared in several. The right-click action stays available everywhere, including for workspaces
whose schema comes from files.

:::note
Because a property is declared once, jumping to an inherited attribute opens the superclass that
declares it — `cim:Conductor.length` opens `Conductor`, even when you were reading `ACLineSegment`.
Jumping to a property requires an RDFArchitect that supports property deep links (released after
1.2.0); on an older instance the term reports "Not found" and classes keep working.
:::

The import itself can be automated too: **CIMNotebook: Send Schema to RDFArchitect** asks the
language server for the workspace's configured schema files (the `opencgmes.jsonc`
`schemas`/`schemasDirectory`), imports them into RDFArchitect as a dataset, and opens the result
in the panel. The dataset is named after the config file's
directory. After that, **Open in RDFArchitect** finds every term of your profiles without any
manual import. It sits in the editor's right-click menu next to **Open in RDFArchitect**, on the
panel's toolbar as **Send Schema**, and in the command palette.

When a session is connected (see below), the schema is imported into the panel's own session and
stays **editable** there — so changes you make to it are picked up live by validation. Without a
connection it is imported read-only and bridged in as a snapshot, as before.

You are not expected to remember to run it. When the panel opens, CIMNotebook checks what this
workspace last sent to that instance and offers to do it for you:

| Situation                                                          | What you get                        |
| ------------------------------------------------------------------ | ----------------------------------- |
| nothing sent yet, or what was sent is gone (the instance restarted) | *"…schema is not in RDFArchitect yet"* → **Import** |
| the schema files changed since the last send                        | *"…schema changed since it was sent"* → **Update**  |
| the schema is there and unchanged                                   | nothing                              |

Both prompts offer **Not now** and **Never for this workspace**; the latter is remembered in the
workspace state, so a workspace you always import by hand stays quiet. When the offer follows an
**Open in RDFArchitect** that could not find its term, the import lands on that term afterwards.

Re-sending is safe: the dataset is rebuilt from its files rather than merged into the previous one.
Note that the changed-schema check compares the *files on disk* with what was last sent, so it asks
about edits made **here**, never about edits made in RDFArchitect — those are not a reason to
re-import, and with a connected session they are picked up live anyway.

:::warning Configure the session cookie for embedding
A VS Code webview loads RDFArchitect in a **third-party browsing context**, so the browser does
not send a `SameSite=Lax` session cookie with the app's API calls. RDFArchitect's default is
`Lax`, and with it the embedded panel shows *"No schemas imported yet"* no matter what you
imported — every request lands in a different session.

Configure the instance you embed to send its session cookie in third-party contexts:

```yaml
server:
    servlet:
        session:
            cookie:
                same-site: none
                secure: true
```

or, equivalently, set `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=none` and
`SERVER_SERVLET_SESSION_COOKIE_SECURE=true` on the backend. `secure: true` works for a local
`http://localhost` instance too — browsers treat localhost as a trustworthy origin.

If you cannot change the deployment, use **Open RDFArchitect in Browser** (also on the panel's
toolbar) instead of the embedded panel. The IntelliJ tool window is unaffected: it loads the app
as a top-level document, not an iframe.
:::

### Live datasets

RDFArchitect keeps one working copy **per browser session** and never publishes it, so the datasets
in the panel are invisible to anything outside that session. CIMNotebook bridges this: the embedded
app reports which session it uses, the extension hands that to the language server, and
`"rdfArchitect": "<dataset>"` in [`opencgmes.jsonc`](/cimvocabcheck/configuration#rdfarchitect) then
validates against that dataset **as you edit it** — add a class in the panel and the next validation
knows it.

Reading the model from RDFArchitect also changes what `Ctrl+Click` does: with no schema files to
open, terms become links into the panel (see [Go-to-definition](#go-to-definition)).

The status bar shows whether a session is connected; clicking it reconnects (also available as
**CIMNotebook: Reconnect RDFArchitect Session**). The connection is remembered per workspace and
restored when the editor starts, so validation keeps working without reopening the panel — a
backend session outlives the browser that created it, though not a restart of RDFArchitect itself.

:::info An instance behind a private CA
Three processes talk to RDFArchitect, each with its own idea of which certificates to trust. A CA
installed in the machine's store is used by all of them **without any configuration**:

| Who | How the machine's CA reaches it |
| --- | --- |
| the **panel** (embedded browser) | uses the system store directly |
| the **extension**'s REST calls | the system store is added to Node's list on activation (VS Code 1.100+ / Node 22.15+; honours `http.systemCertificates`) |
| the **language server** (its own JVM) | Windows: the system root store is used; Debian/Ubuntu: `/etc/ssl/certs/java/cacerts`, which the system keeps in sync — done only when `cimnotebook.rdfArchitectUrl` is an `https` URL, since pointing the JVM at the system store *replaces* its own list |

**macOS is the exception** — a JVM cannot be pointed at the Keychain safely from here, so add the CA
to the JDK's `cacerts`, or name a truststore yourself:

```json
"cimnotebook.javaArgs": [
    "-Djavax.net.ssl.trustStore=/path/to/truststore.jks",
    "-Djavax.net.ssl.trustStorePassword=changeit"
]
```

A truststore you configure always wins over the automatic one. If a handshake still fails, the
language server says so in those terms rather than only reporting a PKIX error.

The server's certificates are settled when it starts, so changing the RDFArchitect URL offers a
window reload — take it, or the running server keeps the trust it was launched with.
:::

:::warning Requires the instance to allow the handshake
A VS Code webview is a third-party iframe, so the app only reveals its session when the deployment
sets `PUBLIC_EMBED_SESSION_HANDSHAKE=true` (see RDFArchitect's admin guide). Without it, live
datasets are unavailable and CIMNotebook falls back to snapshots. The IntelliJ plugin needs no such
setting: its tool window is the plugin's own browser.

The session id grants access to that session, so it is treated as a credential: it lives in VS
Code's secret storage (keyed by workspace) and in the language server's memory, never in
`opencgmes.jsonc`, which only ever holds the dataset name. Workspace state keeps only the URL of
the instance you were connected to.
:::

### SPARQL Notebook support

CIMNotebook validates SPARQL **cells** inside notebook documents — its own CIM Notebooks as well
as third-party
[SPARQL Notebook](https://marketplace.visualstudio.com/items?itemName=Zazuko.sparql-notebook)
documents — not just `.rq` / `.sparql` files. Each cell is validated independently, and a cell can
declare its own schema with a `# [endpoint=...]` directive. See
[SPARQL Notebooks](/cimnotebook/sparql-notebooks) for the full behaviour.

## Settings

These editor-specific settings live in VS Code's settings (`settings.json` or the Settings UI).
Schema configuration itself lives in [`opencgmes.jsonc`](/cimvocabcheck/configuration), not here.

| Setting                      | Default     | Description                                                                                      |
| ---------------------------- | ----------- | ------------------------------------------------------------------------------------------------ |
| `cimnotebook.serverJar`      | _(bundled)_ | Absolute path to `cimvocabcheck-lsp.jar`. Leave empty to use the JAR bundled with the extension. |
| `cimnotebook.javaExecutable` | `java`      | Java executable used to launch the language server. Must be Java 21 or later.                    |
| `cimnotebook.javaArgs`       | `[]`        | Extra JVM arguments passed before `-jar`, e.g. `["-Xmx512m"]`.                                   |
| `cimnotebook.trace.server`   | `off`       | LSP message tracing. Set to `messages` or `verbose` to debug communication with the server.      |
| `cimnotebook.rdfArchitectUrl` | _(unset)_  | URL of a running RDFArchitect instance for the **Open RDFArchitect** commands.                   |

:::note Applying changes
Changing a server-launch setting (`serverJar`, `javaExecutable`, `javaArgs`) requires a window
reload — VS Code prompts you to reload when one changes. Switching `rdfArchitectUrl` between an
`http` and an `https` instance prompts too, because it changes the certificates the language
server's JVM is launched with (see [Live datasets](#live-datasets)).
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
npm run copy-jar    # copies the built cimvocabcheck-lsp jar into server/
npx vsce package    # bundles (via the prepublish step) and produces the .vsix
```

The resulting `cimnotebook-<version>.vsix` can be installed with
`code --install-extension`.

## Troubleshooting

Common VS Code issues — no diagnostics, Java not found, the `.ttl` language-mode conflict, schema
load failures — are collected on the [Troubleshooting](/cimnotebook/troubleshooting) page.
