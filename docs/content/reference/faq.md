---
title: FAQ
sidebar_position: 2
---

# FAQ

Short answers to questions that come up repeatedly across the OpenCGMES products. Each links to the page with the full detail.

## Is there a bundled default schema?

No. CIMVocabCheck ships **no** built-in CGMES schema. Without a configured schema (via `opencgmes.json` or a `# [endpoint=...]` directive), validation is **syntax-only** — it confirms the SPARQL or Turtle parses, but cannot check classes, properties, or domain/range. Point it at your RDFS profiles to get full validation. See [Configuration](/cimvocabcheck/configuration).

## Why static validation instead of running the query?

Because static validation needs **no dataset** and catches a class of mistakes that execution hides. If you run a SPARQL query against a typo'd class or property, SPARQL silently returns an empty result (∅) rather than an error — so the query *looks* fine but is quietly wrong. CIMVocabCheck instead checks every class/property against the schema and flags unknown terms, domain/range violations, and datatype mismatches before the query ever runs. See [CIMVocabCheck overview](/cimvocabcheck/overview).

## Why Java 21?

The CIMVocabCheck core, CLI, and language server are compiled for Java 21, so any process that runs the language server needs a Java 21+ runtime. This is also why the IntelliJ plugin requires **IntelliJ 2024.2+ (build 242)** — that's the first IntelliJ release whose bundled runtime is Java 21. See [Building](/developer-guide/building).

## Why does my `.ttl` SHACL file show no diagnostics?

Almost always because **another extension claimed the `.ttl` extension**, so your file opened in a generic *Turtle* or *Plain Text* language mode instead of *SHACL*. Check the language indicator in the editor's status bar and switch it to **SHACL** (or add a `files.associations` entry). CIMNotebook deliberately uses `.ttl` for SHACL because ENTSO-E and most tooling ship SHACL shapes as plain Turtle. See [VS Code](/cimnotebook/vscode) / [IntelliJ](/cimnotebook/intellij).

## Do I need the ENTSO-E submodule?

Only for the **examples and integration tests**. A normal build (`mvn install`) works without it, and the CGMES integration tests skip themselves automatically when it's missing. Initialise it with `git submodule update --init` if you want to run the CGMES 2.4.15 / 3.0 integration suite or the runnable examples. See [Testing](/developer-guide/testing).

## Where do the CGMES profiles come from?

From the [ENTSO-E Application Profiles library](https://github.com/entsoe/application-profiles-library), included as a Git submodule under `cimvocabcheck/core/testing/entsoe/`. It supplies the real CGMES 2.4.15 and 3.0 RDFS/SHACL profiles that the examples and integration tests validate against. For your own projects, point the config at whichever RDFS profile files you exchange. See [CGMES background](/reference/cgmes-background).
