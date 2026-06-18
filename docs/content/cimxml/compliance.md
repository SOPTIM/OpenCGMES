---
title: Compliance
sidebar_position: 10
---

# Compliance

CIMXML implements the IEC 61970-552 CIMXML serialization on top of standard RDF/XML, supporting the
model-description constructs (full and difference models), CIM-specific UUID and namespace handling,
and the three CIM schema versions in current use. This page lists the supported IEC 61970-552
features, the recognized CIM versions, and the standards background.

## Supported IEC 61970-552 features

- [x] Processing instruction: `<?iec61970-552 version="x.x"?>`
- [x] `FullModel` and `DifferenceModel` types
- [x] Model header metadata — `Model.profile`, `Model.Supersedes`, `Model.DependentOn`
- [x] `rdf:parseType="Statements"` for difference-model containers
- [x] UUID normalization (underscore-prefix handling, canonical `urn:uuid:` IRIs)
- [x] CIM namespace version detection

## CIM versions

The CIM version is detected from the CIM namespace declared in the document. The recognized
namespaces are:

| Version | Namespace URI                               | CGMES        | Status      |
| ------- | ------------------------------------------- | ------------ | ----------- |
| CIM 16  | `http://iec.ch/TC57/2013/CIM-schema-cim16#` | CGMES 2.4.15 | Supported   |
| CIM 17  | `http://iec.ch/TC57/CIM100#`                | CGMES 3.0    | Supported   |
| CIM 18  | `https://cim.ucaiug.io/ns#`                 | (no matching CGMES yet) | Supported |

A document whose CIM namespace is none of the above is treated as having no CIM version
(`NO_CIM`).

:::note New to CIM and CGMES?
For background on the Common Information Model, CGMES profiles, and how the versions relate, see the
[CGMES background](/reference/cgmes-background) reference.
:::

## Standards conformance and testing

CIMXML is based on the Apache Jena RDF/XML parser (`ParserRRX_StAX_SR`) and extends it with the
IEC 61970-552 constructs above, so standard RDF/XML documents parse correctly alongside CIM-specific
ones. The test suite includes W3C RDF/XML conformance tests, CIM-specific parsing tests, profile
version-detection tests, and difference-model application tests.

### W3C RDF/XML test suite acknowledgment

The module includes example files from the
[W3C RDF/XML Syntax Specification](https://www.w3.org/TR/rdf-syntax-grammar/) (W3C Recommendation,
10 February 2004), used under the
[W3C Software License](https://www.w3.org/copyright/software-license-2023/) exclusively to test the
parser's conformance with standard RDF/XML syntax. Copyright © 2004
[World Wide Web Consortium](https://www.w3.org/). These tests ensure that the IEC 61970-552
extensions remain compatible with standard RDF/XML processing.
