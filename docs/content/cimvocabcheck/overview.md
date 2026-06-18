---
title: Overview
sidebar_position: 1
---

# CIMVocabCheck

Static SPARQL and SHACL validation against RDFS / CIM profile schemas. CIMVocabCheck answers the
question *"does this query (or shapes graph) make sense for the schema I'm working with?"* —
**without executing anything and without needing any RDF data**.

It catches mistakes at development time, in unit tests, or in CI: unknown classes and properties,
domain/range violations, datatype mismatches, invalid SHACL cardinalities, and typos in the
standard `rdf:`/`rdfs:`/`owl:`/`sh:` vocabularies.

CIMVocabCheck ships in several forms:

| Form | What it is | Page |
| --- | --- | --- |
| **Library** | `cimvocabcheck-core` — the validation engine, callable from Java | [Library & tests](/cimvocabcheck/library-and-tests) · [API](/cimvocabcheck/api) |
| **CLI** | `cimvocabcheck-cli` — a command-line tool for CI pipelines | [CLI](/cimvocabcheck/cli) |
| **CIMLangServer** | `cimvocabcheck-lsp` — an LSP 3.17 server over stdio | [Language server](/cimvocabcheck/language-server) |
| **Editors** | The [CIMNotebook](/cimnotebook/overview) VS Code extension & IntelliJ plugin front the language server | [CIMNotebook](/cimnotebook/overview) |

Inspired by [gdotv's "SPARQL Query Guardrails"](https://gdotv.com/blog/sparql-query-guardrails-etl-ready/).

## Why static validation?

Running a SPARQL query against a triplestore does **not** tell you whether the query is
*correct* for your schema — a typo'd property name simply returns no rows. CIMVocabCheck compares
every class and property IRI in your query or shapes graph against the schema and reports what
doesn't fit.

| Concern | CIMVocabCheck | SPARQL execution | SHACL validation |
| --- | :---: | :---: | :---: |
| Needs an RDF dataset | ❌ | ✅ | ✅ |
| Needs a schema (RDFS / profile) | ✅ | ❌ | ✅ (shapes) |
| Detects unknown class / property IRI | ✅ | ❌ (returns ∅) | ❌ |
| Detects domain / range mismatch | ✅ | ❌ | ❌ |
| Detects bad data values | ❌ | ❌ | ✅ |

CIMVocabCheck is complementary to SHACL data validation, not a replacement: it validates the
*query/shape itself* against the *schema*, where SHACL validates *data* against *shapes*.

## What it checks

- **Existence** — every class and property IRI must exist in the selected profiles.
- **Semantics** — `rdfs:domain` / `rdfs:range` / `rdfs:subClassOf` compatibility, implied types,
  datatype mismatches.
- **SHACL structure** — `sh:targetClass`, `sh:class`, `sh:path`, node-kind vs range, cardinality.
- **Embedded SPARQL** — `sh:sparql`, `sh:target`, `sh:validator`, `sh:rule` fragments are
  extracted and fully validated.
- **Standard vocabularies** — typos like `rdf:typ` or `sh:minCountt` are flagged against the
  official W3C vocabularies.

See [Validation checks](/cimvocabcheck/validation-checks) for the complete catalogue of diagnostic
codes, and [Known limitations](/cimvocabcheck/limitations) for what is intentionally *not* checked.

## Next steps

- [Getting started](/cimvocabcheck/getting-started) — run the bundled SPARQL & SHACL examples.
- [Configuration](/cimvocabcheck/configuration) — point CIMVocabCheck at your CGMES profiles via
  `opencgmes.json`.
- [Library & tests](/cimvocabcheck/library-and-tests) — drive it from Java / JUnit.
