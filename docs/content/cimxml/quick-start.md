---
title: Quick Start
sidebar_position: 3
---

# Quick Start

This page walks through the three things you will do most often: parsing a model and reading its
header, registering a profile for datatype resolution, and applying a difference model. Every entry
point goes through [`CimXmlParser`](/cimxml/architecture); a parsed model is a
[`CimDatasetGraph`](/cimxml/architecture) you can query with Jena.

## Basic usage

Create a parser, parse a CIMXML file, and inspect the result. If the document is a full model you
can reach the header (metadata) and the body (the actual model data) directly:

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

## Working with CIM profiles

Registering a CIM profile ontology lets the parser resolve datatypes (for example, typing a
`Voltage` value as `xsd:double`) when it parses model files. Register profiles first, then parse:

```java
// Register CIM profile ontologies for datatype resolution
CimProfile profile = parser.parseAndRegisterCimProfile(Path.of("Equipment.rdf"));

// Parse CIMXML with profile-aware datatype resolution
CimDatasetGraph dataset = parser.parseCimModel(Path.of("model.xml"));
```

See [Profiles & datatypes](/cimxml/profiles-and-datatypes) for the registry API and how datatype
resolution works in detail.

## Handling difference models

A difference model (IEC 61970-552) describes incremental changes — triples to add (forward), triples
to remove (reverse), and optional preconditions — relative to a base full model. Parse it, then
apply it to a base model to obtain the resulting graph:

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

The [Difference models](/cimxml/difference-models) page explains the structure and the application
algorithm, including how preconditions are validated.
