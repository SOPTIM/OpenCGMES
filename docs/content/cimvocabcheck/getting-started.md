---
title: Getting Started
sidebar_position: 2
---

# Getting Started with CIMVocabCheck

The quickest way to see CIMVocabCheck in action is the bundled examples, which validate a sample
SPARQL query and a sample SHACL shapes file against all CGMES 3.0 RDFS profiles.

## Prerequisites

Both examples require the ENTSO-E Application Profiles submodule (the CGMES RDFS files) and a
local build:

```bash
git submodule update --init
mvn -q install -DskipTests
```

:::note
The submodule supplies the CGMES profiles used by the examples and the integration tests. It is
**not** needed at runtime for your own schemas — you point CIMVocabCheck at your profiles via
[`opencgmes.json`](/cimvocabcheck/configuration).
:::

## SPARQL example

```bash
mvn -q -pl cimvocabcheck/core exec:java
```

This validates `src/main/resources/examples/example-query.rq` against all CGMES 3.0 RDFS profiles.
Edit that file and re-run to try your own query.

```
=================================================================
 CIMVocabCheck -- static SPARQL query validation
=================================================================

--- schema profiles loaded from: .../CGMES/CurrentRelease/RDFS
  CoreEquipment-EU/3.0                                     210 cls  429 prop
  ...  (10 profiles total)

--- query: examples/example-query.rq (classpath)

--- validation result
  valid: false
  annotations: 3

  [ERROR] UNKNOWN_PROPERTY  -- line 19, col 11
    Property <...#DoesNotExist.property> does not exist in selected profiles [...]

  [ERROR] PROPERTY_NOT_ALLOWED_FOR_CLASS  -- line 23, col 9
    Property <...#ACLineSegment.r> is not allowed on Variable ?term typed as
    [<...#Terminal>]; expected one of [<...#ACLineSegment>].
```

To use a custom RDFS directory or query file:

```bash
mvn -q -pl cimvocabcheck/core exec:java \
    -Dexec.args="path/to/rdfs-folder path/to/query.rq"
```

## SHACL example

```bash
mvn -q -pl cimvocabcheck/core exec:java \
    -Dexec.mainClass=de.soptim.opencgmes.cimvocabcheck.core.examples.ShaclValidationExample
```

This validates `src/main/resources/examples/example-shapes.ttl` against all CGMES 3.0 RDFS
profiles. CIMVocabCheck validates both the **shape structure** and any **embedded SPARQL**
fragments:

```
=================================================================
 CIMVocabCheck -- static SHACL shapes graph validation
=================================================================

--- shape-structure validation
  annotations: 2

  [ERROR] UNKNOWN_CLASS  -- (no source location)
    Shape sh:targetClass: class <...#DoesNotExist> does not exist in [...]

  [ERROR] UNKNOWN_PROPERTY  -- (no source location)
    Shape sh:path: property <...#ACLineSegment.typo> does not exist in [...]

--- embedded SPARQL fragments (2 found)

  -- Fragment 1 of 2  [SELECT]  target: [Terminal]
  valid: false  -- 1 annotation(s)
    [ERROR] PROPERTY_NOT_ALLOWED_FOR_CLASS  -- line 5, col 23
      Property <...#ACLineSegment.r> is not allowed on Variable ?this typed as
      [<...#Terminal>]; expected one of [<...#ACLineSegment>].
```

:::note Source positions
The command-line example operates on an RDF graph, not raw text, so SHACL shape-level findings
report *(no source location)*. The [editor integrations](/cimnotebook/overview) resolve positions
back into the source file for all annotation types.
:::

## Where to go next

- **Use your own schema** → [Configuration](/cimvocabcheck/configuration)
- **Understand the findings** → [Validation checks](/cimvocabcheck/validation-checks)
- **Validate in CI** → [CLI](/cimvocabcheck/cli)
- **Validate in Java tests** → [Library & tests](/cimvocabcheck/library-and-tests)
- **Load the schema from a Fuseki endpoint** → [Endpoints](/cimvocabcheck/endpoints)
