---
title: Getting Started
sidebar_position: 2
---

# Getting Started

OpenCGMES is a suite, so "getting started" depends on which product you need. Pick your path
below — each links to the full guide.

:::tip Prerequisites
Most of OpenCGMES runs on **Java 21+** and **Maven 3.9+**. The editor integrations also need
**Node.js 20+** (VS Code) or **IntelliJ 2024.2+**. See the
[Developer guide → Building](/developer-guide/building) for the full toolchain.
:::

## I want to parse CIMXML in Java

Add the [CIMXML dependency](/cimxml/installation) and parse a model:

```java
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.nio.file.Path;

CimXmlParser parser = new CimXmlParser();
CimDatasetGraph dataset = parser.parseCimModel(Path.of("model.xml"));

if (dataset.isFullModel()) {
    var header = dataset.getModelHeader();
    var profiles = header.getProfiles();
}
```

Continue with the [CIMXML quick start](/cimxml/quick-start).

## I want to validate SPARQL / SHACL against CGMES profiles

The fastest way to see CIMVocabCheck work is the bundled example. From a checkout (with the
ENTSO-E Application Profiles submodule initialized — see below):

```bash
git submodule update --init
mvn -q install -DskipTests
mvn -q -pl cimvocabcheck/core exec:java
```

This validates a sample query against the CGMES 3.0 RDFS profiles and prints the findings.
Continue with the [CIMVocabCheck getting started](/cimvocabcheck/getting-started).

To wire validation into **CI**, use the [CLI](/cimvocabcheck/cli). To call it from **Java tests**,
see [Library & tests](/cimvocabcheck/library-and-tests).

## I want validation in my editor

Install the editor integration and point it at your schema with an
[`opencgmes.json`](/cimvocabcheck/configuration) file:

- **VS Code** — install the [CIMNotebook extension](/cimnotebook/vscode), open a `.rq`/`.sparql`/`.ttl`
  file, and run **CIMNotebook: Create Config File**.
- **IntelliJ** — install the [CIMNotebook plugin](/cimnotebook/intellij) (and LSP4IJ), then
  **Tools → CIMNotebook: Create Config File**.

```json
{
  "cimvocabcheck": {
    "schemasDirectory": "schemas/cgmes-3.0"
  }
}
```

With no schema configured, validation is **syntax-only** — there is no bundled default schema.
See [Configuration](/cimvocabcheck/configuration) for the full reference.

## Getting the source

```bash
git clone https://github.com/SOPTIM/OpenCGMES.git
cd OpenCGMES
git submodule update --init   # ENTSO-E Application Profiles (used by examples & tests)
mvn install                   # build & install every module
```

The repository ships an aggregator `pom.xml` at the root that builds all Maven modules
(`cimxml`, `cimvocabcheck/*`). The CIMNotebook editor integrations build separately — see
[Building](/developer-guide/building).
