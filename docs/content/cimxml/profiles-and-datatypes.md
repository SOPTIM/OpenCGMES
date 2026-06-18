---
title: Profiles & Datatypes
sidebar_position: 4
---

# Profiles & Datatypes

CIM profile ontologies (RDFS schemas) describe which classes and properties exist in a model and
what datatypes their values carry. CIMXML uses a **profile registry** to hold those ontologies so
the parser can resolve a literal such as a `Voltage` to the right RDF datatype instead of leaving it
as a plain string. This page covers the profile model, the registry API, and how datatype resolution
is configured.

## CimProfile

A `CimProfile` (in `de.soptim.opencgmes.cimxml.graph`) wraps a profile ontology graph and exposes its
metadata in a version-independent way. Profiles are versioned and identified by their version IRIs,
which differ between CIM generations:

| CIM version            | Version IRIs              | Keyword          | Version info       |
| ---------------------- | ------------------------- | ---------------- | ------------------ |
| CIM 16 (CGMES 2.4.15)  | `cims:isFixed` base/entsoe URIs | `{Profile}Version.shortName` | (none)   |
| CIM 17/18 (CGMES 3.0+) | `owl:versionIRI`          | `dcat:keyword`   | `owl:versionInfo`  |

Wrap a profile graph and query its metadata:

```java
import de.soptim.opencgmes.cimxml.graph.CimProfile;

// Wrap a loaded profile graph
CimProfile profile = CimProfile.wrap(profileGraph);

CimVersion version       = profile.getCIMVersion();
Set<Node> versionIris    = profile.getOwlVersionIRIs();
String keyword           = profile.getDcatKeyword();
String versionInfo       = profile.getOwlVersionInfo();
boolean isHeaderProfile  = profile.isHeaderProfile();
```

A **model profile** describes the structure and properties of model data (Equipment, Topology,
state variables, and so on); a **header profile** describes the structure of a model header
(`FullModel`/`DifferenceModel` metadata). Header profiles are not referenced by the model itself, so
the registry selects them by CIM version rather than by version IRI.

## CimProfileRegistry

`CimProfileRegistry` (in `de.soptim.opencgmes.cimxml.rdfs`) is the interface for managing profiles;
`CimProfileRegistryStd` is the standard, caching implementation. Each `CimXmlParser` owns its own
registry, accessible via `getCimProfileRegistry()`.

```java
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;

// Create and configure a registry directly
CimProfileRegistry registry = new CimProfileRegistryStd();

// Register a profile
CimProfile profile = CimProfile.wrap(profileGraph);
registry.register(profile);

// Query registered profiles
Set<CimProfile> profiles = registry.getRegisteredProfiles();
```

When registering, the registry extracts the datatypes of every property in the profile and stores
them in a map for fast lookup. Registration throws `IllegalArgumentException` if a profile's
`owlVersionIRI` is already registered, or — for a header profile — if one is already registered for
the same CIM version.

### Registering profiles through the parser

The most common path is to let the parser register profiles for you, so subsequent parses are
profile-aware:

```java
CimXmlParser parser = new CimXmlParser();
parser.parseAndRegisterCimProfile(Path.of("Equipment.rdf"));
parser.parseAndRegisterCimProfile(Path.of("Topology.rdf"));

CimDatasetGraph dataset = parser.parseCimModel(Path.of("model.xml"));
```

## Datatype resolution

Once profiles are registered, the parser resolves property datatypes from them. The registry returns
a `PropertyInfo` record per property, capturing its domain class (`rdfType`), the property IRI, the
CIM datatype, and either a `primitiveType` (an `RDFDatatype`, for datatype properties) or a
`referenceType` (the range class, for object properties) — exactly one of the latter two is non-null.

```java
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry.PropertyInfo;

// Look up properties + datatypes for a set of profile version IRIs
Map<Node, PropertyInfo> properties = registry.getPropertiesAndDatatypes(profileVersionIris);

// Header properties are looked up per CIM version
Map<Node, PropertyInfo> headerProps =
        registry.getHeaderPropertiesAndDatatypes(CimVersion.CIM_17);
```

The returned maps are thread-safe for reading.

## Registering custom primitive types

If a profile uses a primitive type the library does not map out of the box, register a mapping from
the CIM primitive type name to a Jena `RDFDatatype`. The datatype must also be registered with
Jena's `TypeMapper`; registering the same name again overwrites the previous mapping.

```java
import org.apache.jena.datatypes.xsd.XSDDatatype;

registry.registerPrimitiveType("Voltage", XSDDatatype.XSDdouble);
registry.registerPrimitiveType("Current", XSDDatatype.XSDdouble);

// Inspect the full primitive-type mapping
Map<String, RDFDatatype> mapping = registry.getPrimitiveToRDFDatatypeMapping();
```

:::tip Validate queries against these profiles
The same profile ontologies power [CIMVocabCheck](/cimvocabcheck/overview), which statically checks
SPARQL and SHACL against the schema — unknown classes/properties, domain/range violations, and
datatype mismatches — without running anything.
:::
