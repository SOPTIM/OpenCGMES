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

The CIM version is determined by the `cim` namespace URI declared in the document. CIMXML does not
hard-code a fixed set of recognized namespaces — parsing accepts any `cim` namespace URI, and the
namespace is mapped to a `CimProfile` implementation via `CimNamespaceFactoryRegistry`
(`de.soptim.opencgmes.cimxml.graph`), a static registry keyed by namespace URI. Three namespaces are
registered as built-ins:

| Version | Namespace URI                               | CGMES        | `CimProfile` implementation |
| ------- | ------------------------------------------- | ------------ | ---------------------------- |
| CIM 16  | `http://iec.ch/TC57/2013/CIM-schema-cim16#` | CGMES 2.4.15 | `CimProfile16`                |
| CIM 17  | `http://iec.ch/TC57/CIM100#`                | CGMES 3.0    | `CimProfile17`                |
| CIM 18  | `https://cim.ucaiug.io/ns#`                 | (no matching CGMES yet) | `CimProfile18`     |

Call `CimNamespaceFactoryRegistry.registerProfileFactory(namespace, factory)` to map any other
namespace URI to a custom `CimProfile` implementation, e.g. for a vendor extension namespace or a
future CIM version. Parsing a document whose `cim` namespace has no registered factory still
succeeds — the parser only logs a warning — but resolving that namespace's profile ontology via
`CimProfile.wrap` throws `IllegalArgumentException` until a factory is registered for it.

:::tip Registering a custom namespace without writing Java
`CimProfile16`/`CimProfile17`/`CimProfile18` are parsing *shapes*, not namespace-specific code — they
work on whatever `cim` namespace the graph declares. If your vendor namespace's ontology follows the
same conventions (`cims:isFixed` vs. `owl:versionIRI`/`dcat:keyword`), you don't need a custom
`CimProfile` implementation at all: CIMVocabCheck's `cimNamespaces` config setting maps a namespace
URI straight to one of these built-in shapes from `opencgmes.jsonc`. See
[Configuration → `cimNamespaces`](/cimvocabcheck/configuration#cimnamespaces).
:::

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
