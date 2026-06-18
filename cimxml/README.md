# OpenCGMES — CIMXML

A Java library for parsing IEC 61970-552 CIMXML files into Apache Jena RDF graphs. It supports both
full models and difference models, with CIM version 16/17/18 support, profile-aware datatype
resolution, and UUID normalization.

## 📖 Documentation

Full documentation lives at **<https://opencgmes.soptim.de/cimxml/overview>**:

- [Overview & features](https://opencgmes.soptim.de/cimxml/overview)
- [Installation](https://opencgmes.soptim.de/cimxml/installation)
- [Quick start](https://opencgmes.soptim.de/cimxml/quick-start)
- [Architecture](https://opencgmes.soptim.de/cimxml/architecture) ·
  [Difference models](https://opencgmes.soptim.de/cimxml/difference-models) ·
  [Library usage](https://opencgmes.soptim.de/cimxml/library-usage)

## Installation

```xml
<dependency>
    <groupId>de.soptim.opencgmes</groupId>
    <artifactId>cimxml</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Quick example

```java
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.nio.file.Path;

CimXmlParser parser = new CimXmlParser();
CimDatasetGraph dataset = parser.parseCimModel(Path.of("model.xml"));
```

See the [quick start](https://opencgmes.soptim.de/cimxml/quick-start) for working with profiles and
difference models.

## License

Licensed under the [Apache License 2.0](../LICENSE).

This module is based on the Apache Jena RDF/XML parser (`ParserRRX_StAX_SR`), and includes example
files from the [W3C RDF/XML Syntax Specification](https://www.w3.org/TR/rdf-syntax-grammar/) used
under the [W3C Software License](https://www.w3.org/copyright/software-license-2023/) for testing
RDF/XML conformance. © World Wide Web Consortium.
