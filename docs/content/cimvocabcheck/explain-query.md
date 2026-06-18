---
title: Explain Query
sidebar_position: 9
---

# Explain Query

CIMVocabCheck can surface a Jena-style **static algebra plan** for a SPARQL query — the same
information as `arq.qparse --print=query,op,opt`, but without running anything. It's a quick way to
see how the engine *reads* your query: how patterns nest, how filters and optionals are placed, and
what the optimizer rewrites.

The plan is **static** — it is computed from the query text alone (no endpoint, no execution, no
statistics).

## What you get

`explain` renders three sections:

```
# Query
<the query, with default prefixes injected>

# Algebra
(the SPARQL algebra, SSE form)

# Algebra (optimized)
(the algebra after Jena's optimizer)
```

For SPARQL Update requests or unparseable input, a short message is returned instead of a plan.

## From Java

```java
import de.soptim.opencgmes.cimvocabcheck.core.explain.QueryExplanation;

// Instance method — injects the configured default prefixes.
QueryExplanation explanation = api.explain("""
    SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r }
    """);

System.out.println(explanation.render());
```

```java
// Static — schema-independent (the algebra plan doesn't depend on the schema, only prefix
// injection does), uses the built-in prefix set.
QueryExplanation explanation = SparqlValidationApi.explainStatic(queryText);
```

The algebra plan does **not** depend on the schema; only prefix injection does. That's why the
static form needs no `SchemaIndex`.

## From the CLI

`explain` is a subcommand of the validator:

```bash
java -jar cimvocabcheck-cli.jar explain path/to/query.rq
```

A schema is optional — without one it falls back to the static plan. See the
[CLI page](/cimvocabcheck/cli).

## In the editors

[CIMNotebook](/cimnotebook/overview) exposes **Explain Query** as an editor action (right-click /
command palette in VS Code; editor context menu in IntelliJ). The plan opens in a read-only
document beside your query.

The underlying LSP `executeCommand` id is `cimvocabcheck.explainQuery` — see the
[language server page](/cimvocabcheck/language-server) if you are integrating another editor.
