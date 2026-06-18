---
title: Overview
sidebar_position: 1
---

# Overview

**CIMXML** is a Java library that parses IEC 61970-552 CIMXML — the RDF/XML interchange format
used in the energy sector to exchange power-system network models — into
[Apache Jena](https://jena.apache.org/) RDF graphs. It provides a specialized, StAX-based parser
and a set of CIM-aware data structures for reading, manipulating, and querying both full models and
difference models, with profile-driven datatype resolution and automatic UUID normalization.

## Features

- **CIMXML parser** — a specialized StAX-based parser optimized for CIMXML documents, built on
  Apache Jena's RDF/XML parsing machinery.
- **CIM version support** — handles CIM versions 16, 17, and 18, detected automatically from the
  document's namespace declarations.
- **Profile management** — a registry system for CIM profile ontologies (RDFS schemas) with
  datatype resolution, so property values are typed from the profiles you register.
- **Model types** — full support for both `FullModel` and `DifferenceModel` as defined by
  IEC 61970-552.
- **Graph operations** — specialized graph implementations for efficient handling of large CIM
  models, including difference application without materialization.
- **UUID handling** — automatic normalization of CIM UUIDs (with and without underscore prefixes
  and dashes) into canonical `urn:uuid:` IRIs.
- **Datatype resolution** — automatic resolution of CIM datatypes from registered profiles, plus
  registration of custom primitive types.
- **Apache Jena integration** — full compatibility with Apache Jena 5.5.0, so parsed models are
  ordinary Jena `DatasetGraph`/`Graph` objects ready for SPARQL queries and RDF operations.

The result of parsing is a [`CimDatasetGraph`](/cimxml/architecture) — an extended Jena
`DatasetGraph` that exposes the CIM model structure (header, body, forward/reverse differences,
preconditions) through convenience methods.

:::tip Where to next
New to the library? Add it to your build with [Installation](/cimxml/installation), then parse your
first model in the [Quick start](/cimxml/quick-start). If you are new to CIM/CGMES, start with the
[CGMES background](/reference/cgmes-background).
:::
