---
title: Troubleshooting
sidebar_position: 5
---

# Troubleshooting

Common problems with the CIMNotebook editor integrations, grouped by symptom. Each tip notes which
editor it applies to — **VS Code**, **IntelliJ**, or **both**.

## No diagnostics at all

**Both.** With no schema configured, CIMNotebook reports only syntax errors. Add an
[`opencgmes.jsonc`](/cimvocabcheck/configuration) with `schemas` (or `schemasDirectory`), or a
`# [endpoint=...]` directive in the query, to get full schema-based validation.

If even **syntax** errors are missing, the language server probably did not start:

- **VS Code** — open **CIMNotebook: Show Output** from the Command Palette and confirm Java 21+ is
  available. The output channel shows startup and schema-loading errors.
- **IntelliJ** — make sure LSP4IJ is enabled, then open the **Language Servers** tool window
  (provided by LSP4IJ) to see CIMLangServer's status and message log. This is the IntelliJ
  equivalent of an LSP output channel and the best place to diagnose startup issues.

Also confirm the file extension is recognised: `.rq`, `.sparql`, `.ttl`, or `.shacl`.

## Java not found, or wrong version

**Both.** CIMNotebook launches the language server as a Java process and needs **Java 21 or later**.
Point it at a specific JVM:

- **VS Code** — set `cimnotebook.javaExecutable` to the full path of a Java 21+ executable, e.g.
  `/usr/lib/jvm/java-21/bin/java`.
- **IntelliJ** — set **Settings → Tools → CIMNotebook → Java executable** to the same. Leave it
  empty to use the IDE's own bundled runtime (Java 21+ from 2024.2 onward).

## No diagnostics on a `.ttl` / SHACL file (but SPARQL works)

This means something else has claimed the `.ttl` extension, so the file is not treated as SHACL.

- **VS Code** — another installed RDF/Turtle extension (or a `files.associations` entry) has set the
  language mode to **Turtle** or **Plain Text** instead of **SHACL**. Check the language indicator in
  the bottom-right status bar. Recent builds also match `.ttl` / `.shacl` files by path, so they
  validate regardless of language mode — make sure you are on an up-to-date build. You can force the
  mode by clicking the language indicator and selecting **SHACL**, or add
  `"files.associations": { "*.ttl": "shacl" }` to your settings.
- **IntelliJ** — the SHACL file type claims `.ttl`, but file-type registration is last-wins, so
  another plugin may own it. Add the association under **Settings → Editor → File Types → SHACL**.

## Server fails to start, or "Schema load failed"

**Both.** This is usually a Java problem (see [above](#java-not-found-or-wrong-version)) or a bad
schema path. To read the full error:

- **VS Code** — open **CIMNotebook: Show Output**. Common causes: an incorrect path in
  `schemasDirectory` / `schemas`, or a malformed RDF file. Set `cimnotebook.serverJar` only if you
  are overriding the bundled JAR.
- **IntelliJ** — open the LSP4IJ **Language Servers** console, which shows the full server error. If
  the server never launches, confirm LSP4IJ is installed and enabled, and that
  **Settings → Tools → CIMNotebook → Java executable** points at a Java 21+ runtime.

For a schema-load failure specifically, double-check that the paths in your `opencgmes.jsonc` resolve
and that each referenced RDF / Turtle file is well-formed.

:::tip Confirming the schema loaded
**VS Code** shows a notification — "Schema loaded successfully." — once the schema is ready. If you
never see it, the schema did not load; check the output channel.
:::

## A feature seems missing — which server am I actually running?

Both editors bundle the language server, so a plugin or extension packaged before a change looks
exactly like a feature that does not work: no error, just nothing. The server names its own build,
so this is one look rather than a guess:

- **VS Code** — **CIMNotebook: Show Output** logs the JAR it resolved and, once the handshake is
  through, the build that answered: `[server] CIMLangServer 1.2.3 (built 2026-01-01 00:00Z)`.
- **IntelliJ** — the LSP4IJ **Language Servers** window shows the server as
  `CIMLangServer 1.2.3 (built 2026-01-01 00:00Z)`.

If that build predates the feature you are looking for, reinstall from a newer artifact — and note
that the two editors are packaged separately, so one can be current while the other is not.

A server built from a working tree rather than from a release tag reports `development build
(built …)` — only a release carries a version number, so that is the expected reading when you
built the JAR yourself.

## RDFArchitect: `rdfArchitect` / `# [rdfarchitect=...]` seems ignored

A **dataset name** is only resolvable while the editor's RDFArchitect view is connected — it is what
says which instance, and which session, holds that dataset. Open the RDFArchitect panel (VS Code) or
tool window (IntelliJ) once; the connection is then remembered and restored when the workspace is
reopened. VS Code shows the state in the status bar. A **snapshot or instance link** needs no
connection at all, and is the form to use in CI. See
[live datasets](/cimvocabcheck/configuration#live-datasets).

## See also

- [VS Code](/cimnotebook/vscode) and [IntelliJ](/cimnotebook/intellij) — install and settings.
- [Configuration](/cimvocabcheck/configuration) — the `opencgmes.jsonc` format.
- [Validation checks](/cimvocabcheck/validation-checks) — what each diagnostic code means.
