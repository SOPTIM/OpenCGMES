# OpenCGMES IEC 61970-552 CIMXML Parser


## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
    - [Basic Usage](#basic-usage)
    - [Working with CIM Profiles](#working-with-cim-profiles)
    - [Handling Difference Models](#handling-difference-models)
- [Architecture](#architecture)
    - [Core Components](#core-components)
        - [1. Parser (`de.soptim.opencgmes.cimxml.parser`)](#1-parser-desoptimopencgmescimxmlparser)
        - [2. Graph Structures (`de.soptim.opencgmes.cimxml.graph`)](#2-graph-structures-desoptimopencgmescimxmlgraph)
        - [3. Dataset (`de.soptim.opencgmes.cimxml.sparql.core`)](#3-dataset-desoptimopencgmescimxmlsparqlcore)
        - [4. Profile Registry (`de.soptim.opencgmes.cimxml.rdfs`)](#4-profile-registry-desoptimopencgmescimxmlrdfs)
    - [Data Model](#data-model)
- [IEC 61970-552 Compliance](#iec-61970-552-compliance)
    - [Supported Features](#supported-features)
    - [CIM Versions](#cim-versions)
- [Advanced Usage](#advanced-usage)
    - [Custom Error Handling](#custom-error-handling)
    - [Profile Registry Management](#profile-registry-management)
    - [Working with Model Headers](#working-with-model-headers)
    - [SPARQL Queries on CIM Data](#sparql-queries-on-cim-data)
- [Performance Considerations](#performance-considerations)
    - [Memory Optimization](#memory-optimization)
    - [Large File Handling](#large-file-handling)
- [Testing](#testing)
- [Dependencies](#dependencies)
- [Contributing](#contributing)
- [License](#license)
- [References](#references)
- [Support](#support)
    - [Commercial Support and Services](#commercial-support-and-services)
- [Acknowledgments](#acknowledgments)

## Overview

The OpenCGMES CIMXML module provides a specialized parser and data structures for handling IEC 61970-552 CIMXML (Common Information Model XML) files within the Apache Jena RDF framework. This module enables parsing, manipulation, and querying of power system models that conform to the IEC 61970-552 standard, commonly used in the energy sector for exchanging power system network data.

## Features

- **CIMXML Parser**: Specialized StAX-based parser optimized for CIMXML documents
- **CIM Version Support**: Supports CIM versions 16, 17, and 18
- **Profile Management**: Registry system for CIM profile ontologies with datatype resolution
- **Model Types**: Support for both FullModel and DifferenceModel as per IEC 61970-552
- **Graph Operations**: Specialized graph implementations for efficient handling of large CIM models
- **UUID Handling**: Automatic normalization of CIM UUIDs (with and without underscores/dashes)
- **Datatype Resolution**: Automatic resolution of CIM datatypes from registered profiles
- **Apache Jena Integration**: Full compatibility with Apache Jena 5.5.0 for SPARQL queries and RDF operations

## Installation

### Maven
```xml
<dependency>
    <groupId>de.soptim.opencgmes</groupId>
    <artifactId>cimxml</artifactId>
    <version>1.1.0</version>
</dependency>
```
### Gradle
```gradle
implementation 'de.soptim.opencgmes:cimxml:1.1.0'
```

## Quick Start

### Basic Usage

```java
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.nio.file.Path;

// Create a parser instance
CimXmlParser parser = new CimXmlParser();

// Parse a CIMXML file
CimDatasetGraph dataset = parser.parseCimModel(Path.of("model.xml"));

// Check model type
if (dataset.isFullModel()) {
    // Access the model header and body
    var header = dataset.getModelHeader();
    var body = dataset.getBody();
    
    // Get model metadata
    var modelId = header.getModel();
    var profiles = header.getProfiles();
}
```

### Working with CIM Profiles

```java
// Register CIM profile ontologies for datatype resolution
CimProfile profile = parser.parseAndRegisterCimProfile(Path.of("Equipment.rdf"));

// Parse CIMXML with profile-aware datatype resolution
CimDatasetGraph dataset = parser.parseCimModel(Path.of("model.xml"));
```

### Handling Difference Models

```java
// Parse a difference model
CimDatasetGraph diffModel = parser.parseCimModel(Path.of("difference.xml"));

if (diffModel.isDifferenceModel()) {
    // Access difference components
    var forwardDiffs = diffModel.getForwardDifferences();
    var reverseDiffs = diffModel.getReverseDifferences();
    var preconditions = diffModel.getPreconditions();
    
    // Apply differences to a base model
    CimDatasetGraph baseModel = parser.parseCimModel(Path.of("base.xml"));
    Graph resultModel = diffModel.differenceModelToFullModel(baseModel);
}
```

## Architecture

### Core Components

#### 1. Parser (`de.soptim.opencgmes.cimxml.parser`)
- `CimXmlParser`: Main entry point for parsing CIMXML files
- `ReaderCIMXML_StAX_SR`: StAX-based XML reader implementation
- `ParserCIMXML_StAX_SR`: Core parsing logic with CIMXML-specific handling

#### 2. Graph Structures (`de.soptim.opencgmes.cimxml.graph`)
- `CimProfile`: Represents CIM profile ontologies (versions 16, 17, 18)
- `CimModelHeader`: Wrapper for model header information
- `FastDeltaGraph`: Efficient implementation for difference models
- `DisjointMultiUnion`: Union graph without duplicate elimination

#### 3. Dataset (`de.soptim.opencgmes.cimxml.sparql.core`)
- `CimDatasetGraph`: Extended DatasetGraph with CIM-specific operations
- `LinkedCimDatasetGraph`: Implementation supporting multiple named graphs

#### 4. Profile Registry (`de.soptim.opencgmes.cimxml.rdfs`)
- `CimProfileRegistry`: Interface for managing CIM profiles
- `CimProfileRegistryStd`: Standard implementation with caching

### Data Model

The module organizes CIMXML data into distinct graphs:

**FullModel Structure:**
- Model Header Graph (named: `urn:FullModel`)
- Body Graph (default graph)

**DifferenceModel Structure:**
- Model Header Graph (named: `urn:DifferenceModel`)
- Forward Differences Graph
- Reverse Differences Graph
- Preconditions Graph (optional)

## IEC 61970-552 Compliance

### Supported Features

- ✅ Processing instruction: `<?iec61970-552 version="x.x"?>`
- ✅ FullModel and DifferenceModel types
- ✅ Model header metadata (profiles, supersedes, dependentOn)
- ✅ parseType="Statements" for difference model containers
- ✅ UUID normalization (underscore prefix handling)
- ✅ CIM namespace version detection

### CIM Versions

| Version| Namespace URI                               | Status |
|--------|---------------------------------------------|--------|
| CIM 16 | `http://iec.ch/TC57/2013/CIM-schema-cim16#` | ✅ Supported |
| CIM 17 | `http://iec.ch/TC57/CIM100#`                | ✅ Supported |
| CIM 18 | `https://cim.ucaiug.io/ns#`                 | ✅ Supported |

## Advanced Usage

### Custom Error Handling

```java
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.ErrorHandlerFactory;

// Create parser with custom error handler
ErrorHandler errorHandler = ErrorHandlerFactory.errorHandlerStrict;
CimXmlParser parser = new CimXmlParser(errorHandler);
```

### Profile Registry Management

```java
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;

// Create and configure registry
CimProfileRegistry registry = new CimProfileRegistryStd();

// Register custom primitive type mappings
registry.registerPrimitiveType("Voltage", XSDDatatype.XSDdouble);

// Register profiles
CimProfile profile = CimProfile.wrap(profileGraph);
registry.register(profile);

// Query registered profiles
Set<CimProfile> profiles = registry.getRegisteredProfiles();
```

### Working with Model Headers

```java
CimModelHeader header = dataset.getModelHeader();

// Get model metadata
Node modelUri = header.getModel();
Set<Node> profileUris = header.getProfiles();
Set<Node> supersededModels = header.getSupersedes();
Set<Node> dependencies = header.getDependentOn();

// Check model type
boolean isFullModel = header.isFullModel();
boolean isDiffModel = header.isDifferenceModel();
```

### SPARQL Queries on CIM Data

```java
import org.apache.jena.query.*;

// Create SPARQL query
String queryString = """
    PREFIX cim: <http://iec.ch/TC57/CIM100#>
    SELECT ?equipment ?name
    WHERE {
        ?equipment a cim:Equipment ;
                   cim:IdentifiedObject.name ?name .
    }
    """;

Query query = QueryFactory.create(queryString);

// Execute query on CIM dataset
try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        // Process results
    }
}
```

## Performance Considerations

### Memory Optimization

The module uses specialized graph implementations optimized for CIM data:

- `FastDeltaGraph`: Efficient difference application of difference models without materialization

### Large File Handling

```java
// Use buffered file channel for large files
Path largeCimFile = Path.of("large_model.xml");
CimDatasetGraph dataset = parser.parseCimModel(largeCimFile);
// Internally uses BufferedFileChannelInputStream with optimal buffer sizing
```

## Testing

The module includes comprehensive test coverage:

- W3C RDF/XML conformance tests
- CIM-specific parsing tests
- Profile version detection tests
- Difference model application tests

Run tests with:
```bash
mvn -f cimxml/pom.xml test
```

## Release Process

`cimxml` uses GitHub Actions for CI/CD.

- Pull requests and pushes to `main` run build and tests.
- Pushes to `main` also publish `-SNAPSHOT` artifacts to GitHub Packages.
- Pushing a tag in the format `cimxml-vX.Y.Z` triggers a release:
  - publish signed artifacts to Maven Central (Sonatype Central Portal)
  - publish release artifacts to GitHub Packages

Versioning model:
- `cimxml/pom.xml` on `main` remains `X.Y.Z-SNAPSHOT`
- release tag defines the release version (`X.Y.Z`)
- CI sets that release version in the workflow workspace for deployment only

## Dependencies

Main dependencies:
- Apache Jena 5.5.0 (ARQ module)
-  Woodstox StAX parser
-  Aalto XML processor
-  Apache Commons IO
-  Apache Commons Lang3
-  SLF4J API

## Contributing

Contributions are welcome! Please ensure:
1. All tests pass
2. Code follows Apache Jena coding standards
3. JavaDoc is complete for public APIs
4. Changes are documented in release notes

Submit pull requests to: https://github.com/SOPTIM/OpenCGMES

## License

Licensed under the Apache License, Version 2.0. See LICENSE file for details.

## References

- [IEC 61970-552 Standard](https://webstore.iec.ch/publication/25939)
- [Apache Jena Documentation](https://jena.apache.org/)
- [CGMES (Common Grid Model Exchange Standard)](https://www.entsoe.eu/data/cim/cim-for-grid-models-exchange/)
- [OpenCGMES Project](https://github.com/SOPTIM/OpenCGMES) 

## Support

For issues and questions:
- GitHub Issues: [OpenCGMES GitHub](https://github.com/SOPTIM/OpenCGMES/issues)

## Commercial Support and Services

For organizations requiring commercial support, professional maintenance, integration services,
or custom extensions for this project, these services are available from **[SOPTIM AG](https://www.soptim.de/)**.

Please feel free to contact us via [opencgmes@soptim.de](mailto:opencgmes@soptim.de).

## Acknowledgments
This project is based on the Apache Jena RDF/XML parser (ParserRRX_StAX_SR), originally developed by the Apache Jena team. We thank the Apache Software Foundation and all contributors to the original project.

### W3C RDF/XML Test Suite
This module includes example files from the [W3C RDF/XML Syntax Specification](https://www.w3.org/TR/rdf-syntax-grammar/)
(W3C Recommendation 10 February 2004) in the `testing/w3c-rdf-syntax-grammar/` directory.

Copyright © 2004 [World Wide Web Consortium](https://www.w3.org/).

These files are used under the [W3C Software License](https://www.w3.org/copyright/software-license-2023/)
exclusively for testing the conformance of the CIMXML parser with standard RDF/XML syntax. The test files
ensure that the IEC 61970-552 extensions are compatible with standard RDF/XML processing.
