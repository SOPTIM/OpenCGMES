---
title: Testing
sidebar_position: 3
---

# Testing

OpenCGMES has two layers of automated tests: fast unit tests that run anywhere, and integration tests that validate against the **real ENTSO-E CGMES profiles** delivered as a Git submodule. This page explains how to run each and what they cover. See [Building](/developer-guide/building) for the toolchain and submodule setup.

## Running tests

The aggregator reactor tests every Maven module from the repository root:

```bash
mvn test          # unit-test every module
mvn verify        # tests + static-analysis gates + coverage (see Code style)
```

To test a single module (and build its dependencies first):

```bash
mvn -pl cimvocabcheck/core -am verify   # cimvocabcheck-core + cimxml
mvn -f cimxml/pom.xml test              # cimxml only, standalone
```

Running `verify` rather than `test` also runs the formatting/static-analysis gates (Spotless, Checkstyle, SpotBugs, PMD) and the JaCoCo coverage floors — see [Code style](/developer-guide/code-style).

## CIMXML tests

The CIMXML module's tests cover:

- **W3C RDF/XML conformance** — example files from the W3C RDF/XML Syntax Specification, ensuring the IEC 61970-552 extensions stay compatible with standard RDF/XML.
- **CIMXML-specific parsing** — FullModel vs. DifferenceModel handling, the `<?iec61970-552 ...?>` processing instruction, and `parseType="Statements"` containers.
- **Profile version detection** — CIM 16 / 17 / 18 namespace resolution.
- **Difference-model application** — applying forward/reverse differences to a base model.

These tests need no submodule and run as part of any `mvn test`.

## ENTSO-E integration tests (CGMES 2.4.15 + 3.0)

The CIMVocabCheck core has an integration suite that loads the **real ENTSO-E RDFS/SHACL profiles** and validates SPARQL and SHACL against them. The profiles come from the [ENTSO-E Application Profiles library](https://github.com/entsoe/application-profiles-library) submodule at `cimvocabcheck/core/testing/entsoe/application-profiles-library`.

Two CGMES generations are exercised:

| Test class | CGMES version | CIM namespace | Profiles directory (under the submodule) |
| --- | --- | --- | --- |
| `Cgmes24IntegrationTest` | 2.4.15 | `http://iec.ch/TC57/2013/CIM-schema-cim16#` (CIM 16) | `CGMES/PastReleases/v2-4/Original/RDFS` |
| `Cgmes30IntegrationTest` | 3.0 | `http://iec.ch/TC57/CIM100#` (CIM 100) | `CGMES/CurrentRelease/RDFS` |
| `CgmesShacl24IntegrationTest` | 2.4.15 | CIM 16 | `.../v2-4/Original/RDFS` + `.../Enhanced/SHACL` |
| `CgmesShacl30IntegrationTest` | 3.0 | CIM 100 | `CGMES/CurrentRelease/RDFS` + `CGMES/CurrentRelease/SHACL` |
| `CgmesSchemaLoaderTest` | 3.0 | CIM 100 | `CGMES/CurrentRelease/RDFS` |

What they verify, against schemas that real CGMES users actually exchange:

- The full RDFS profile set loads into the schema index (classes, properties, domains, ranges, subclass chains).
- SPARQL validation flags genuine errors (unknown class/property, `PROPERTY_NOT_ALLOWED_FOR_CLASS`, datatype mismatch) and stays quiet on valid queries.
- SHACL shapes graphs are validated both structurally and via their embedded SPARQL constraints.

To run them, initialise the submodule first:

```bash
git submodule update --init
mvn -pl cimvocabcheck/core -am verify
```

### Tests skip when the submodule is missing

Every integration test guards itself with a JUnit `Assume.assumeTrue(...)` that checks whether the submodule directory exists and contains `.rdf` files:

```java
Assume.assumeTrue(
    "CGMES 2.4.15 submodule not initialised — skipping",
    Files.isDirectory(RDFS_DIR) && hasRdfFiles(RDFS_DIR));
```

:::note Skipped, not failed
If the submodule is not initialised, these tests are **skipped** (marked as assumptions-not-met), not failed. A plain `mvn test` on a checkout without the submodule still passes — only the integration coverage is absent. CI checks out submodules recursively (`submodules: recursive`), so the integration tests always run in the `build-test` job.
:::

## Running the CGMES examples

The CIMVocabCheck core also ships runnable examples that double as a smoke test against the submodule profiles:

```bash
git submodule update --init
mvn -q install -DskipTests
mvn -q -pl cimvocabcheck/core exec:java                                  # SPARQL example
mvn -q -pl cimvocabcheck/core exec:java \
    -Dexec.mainClass=de.soptim.opencgmes.cimvocabcheck.core.examples.ShaclValidationExample   # SHACL example
```
