---
title: Configuration
sidebar_position: 3
---

# Configuration (`opencgmes.json`)

This is the **canonical reference** for configuring CIMVocabCheck. Every form of CIMVocabCheck —
the [CLI](/cimvocabcheck/cli), the [CIMLangServer](/cimvocabcheck/language-server), and the
[CIMNotebook](/cimnotebook/overview) editors — reads the same `opencgmes.json` file and the same
`cimvocabcheck` section described here.

## The file

CIMVocabCheck is configured by an `opencgmes.json` file. All settings live under a top-level
`"cimvocabcheck"` section, so the same file can host configuration for other OpenCGMES tools:

```json
{
  "cimvocabcheck": {
    "schemasDirectory": "schemas/cgmes-3.0",
    "strictness": "default"
  }
}
```

JSON comments (`//`) and trailing commas are allowed.

### Discovery — nearest config wins

The file is discovered **git-style**: CIMVocabCheck walks **up** the directory tree from each
file being validated and uses the **nearest** `opencgmes.json`. Different subtrees of a repository
can therefore use different configurations. The CLI auto-discovers upward from the current working
directory (or takes an explicit `--config`).

Generate a commented starter file with:

- **Editors:** the **CIMNotebook: Create Config File** command (VS Code Command Palette, or
  IntelliJ **Tools** menu).
- **CLI:** [`cimvocabcheck init`](/cimvocabcheck/cli).

:::warning No bundled default schema
If no config file is found (and the query declares no [`# [endpoint=...]`](/cimvocabcheck/endpoints)
directive), or the config sets no schemas, CIMVocabCheck performs a **syntax-only** check. There is
**no bundled default schema** — you must point it at your CGMES profiles for schema-based
validation. Other settings (like `strictness` and standard-vocabulary checking) still apply in
syntax-only mode.
:::

## Settings

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `schemasDirectory` | string | — | Directory of RDFS/profile files (`.rdf`, `.ttl`, `.owl`) |
| `schemas` | string[] | — | Explicit list of RDFS/profile files |
| `strictness` | enum | `default` | How findings map to severities |
| `namedGraphs` | object | — | Map graph IRIs / short names to profile IRIs |
| `prefixes` | object | *(built-in set)* | PREFIX declarations injected into queries |
| `standardVocabulary` | enum | `check` | Whether to typo-check `rdf`/`rdfs`/`owl`/`sh` terms |

All fields are optional. Paths are resolved **relative to `opencgmes.json`**.

### `schemas` / `schemasDirectory`

Point at a directory or list individual files:

```json
{
  "cimvocabcheck": {
    "schemasDirectory": "schemas"
  }
}
```

```json
{
  "cimvocabcheck": {
    "schemas": [
      "schemas/61970-600-2_Equipment-AP-Voc-RDFS2020.rdf",
      "schemas/61970-600-2_Topology-AP-Voc-RDFS2020.rdf"
    ]
  }
}
```

If you omit both, no schema is loaded and validation is syntax-only (unless a
[`# [endpoint=...]`](/cimvocabcheck/endpoints) directive supplies one).

### `strictness`

Controls which findings are reported and how their severities are mapped:

| Level | Behaviour |
| --- | --- |
| `permissive` | Structural errors only (`SYNTAX_ERROR`, `UNKNOWN_CLASS`, `UNKNOWN_PROPERTY`, `UNKNOWN_VOCABULARY_TERM`, `INVALID_CARDINALITY`); semantic checks and hints suppressed |
| `default` | All findings as-is (errors are errors, warnings are warnings) |
| `strict` | Warnings promoted to errors — recommended for CI |
| `pedantic` | Warnings **and** infos/hints promoted to errors |

```json
{ "cimvocabcheck": { "strictness": "strict" } }
```

### `namedGraphs`

Maps named-graph IRIs (or short relative names) to one or more **profile version IRIs**. When set,
terms inside a `GRAPH <iri> {}` block are validated against only the mapped profiles:

```json
{
  "cimvocabcheck": {
    "namedGraphs": {
      "urn:uuid:my-equipment-graph": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
      "urn:uuid:my-topology-graph":  ["http://iec.ch/TC57/ns/CIM/Topology-EU/3.0"]
    }
  }
}
```

Each value is an **array**, so one graph can be validated against multiple profiles. Keys can be
**full absolute IRIs** or **short relative names** matching how you write the graph in the query —
a query using `FROM NAMED <EQ>` or `GRAPH <EQ> {}` matches the key `"EQ"`:

```json
{
  "cimvocabcheck": {
    "namedGraphs": {
      "EQ": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
      "TP": ["http://iec.ch/TC57/ns/CIM/Topology-EU/3.0"]
    }
  }
}
```

| Behaviour | When |
| --- | --- |
| All profiles, no `GRAPH_NOT_CONFIGURED` diagnostics | `namedGraphs` **not** set (default) |
| Per-graph profiles; graphs not in the map produce `GRAPH_NOT_CONFIGURED` | `namedGraphs` set |

### `prefixes`

Default `PREFIX` declarations injected into every SPARQL query/update that does not already declare
them, so you can write `cim:ACLineSegment` without repeating `PREFIX cim:` everywhere. When the
field is **absent**, this built-in set is used:

| Prefix | Namespace |
| --- | --- |
| `rdf` | `http://www.w3.org/1999/02/22-rdf-syntax-ns#` |
| `rdfs` | `http://www.w3.org/2000/01/rdf-schema#` |
| `owl` | `http://www.w3.org/2002/07/owl#` |
| `xsd` | `http://www.w3.org/2001/XMLSchema#` |
| `sh` | `http://www.w3.org/ns/shacl#` |
| `cim` | `http://iec.ch/TC57/CIM100#` |
| `md` | `http://iec.ch/TC57/61970-552/ModelDescription/1#` |

Providing an explicit `"prefixes"` object **replaces** the built-in set entirely:

```json
{
  "cimvocabcheck": {
    "prefixes": {
      "rdf":   "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
      "cim":   "http://iec.ch/TC57/CIM100#",
      "mycim": "http://example.com/my-extension#"
    }
  }
}
```

Use `{}` to disable automatic prefix injection entirely. Prefixes already declared inside the query
file are never overwritten.

### `standardVocabulary`

Controls typo-checking of terms in the **closed** standard vocabularies (`rdf`, `rdfs`, `owl`,
`sh`), validated against the official W3C vocabularies:

| Value | Behaviour |
| --- | --- |
| `check` (default) | Typos such as `rdf:typ` or `sh:minCountt` are reported as `UNKNOWN_VOCABULARY_TERM` (ERROR) |
| `ignore` | These namespaces are accepted without inspection (legacy behaviour) |

```json
{ "cimvocabcheck": { "standardVocabulary": "ignore" } }
```

Open annotation/datatype namespaces (`xsd`, `dcterms`, `dc`, `skos`, `dcat`, and the IEC extension
namespaces) are always accepted regardless of this setting. The vendored W3C vocabulary documents
are redistributed under the W3C Software and Document License.

## Full example

```json
{
  // CIMVocabCheck settings — see https://opencgmes.soptim.de/cimvocabcheck/configuration
  "cimvocabcheck": {
    "schemasDirectory": "schemas/cgmes-3.0",
    "strictness": "strict",
    "standardVocabulary": "check",
    "namedGraphs": {
      "EQ": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
      "TP": ["http://iec.ch/TC57/ns/CIM/Topology-EU/3.0"]
    },
    "prefixes": {
      "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
      "cim": "http://iec.ch/TC57/CIM100#"
    }
  }
}
```

## See also

- [Validation checks](/cimvocabcheck/validation-checks) — the diagnostic codes these settings affect.
- [Endpoints](/cimvocabcheck/endpoints) — load the schema from a SPARQL endpoint instead of files.
- [CLI](/cimvocabcheck/cli) — `--config`, `--schema`, `--strictness` flags mirror these settings.
