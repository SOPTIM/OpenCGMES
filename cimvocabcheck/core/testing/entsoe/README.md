# ENTSO-E Application Profiles Library — drop-in test fixture

The Phase 2 SHACL extractor in `de.soptim.opencgmes.cimvocabcheck.core.shacl` is designed
to consume the SHACL shapes from the ENTSO-E *Application Profiles Library*:

> https://github.com/entsoe/application-profiles-library

The shapes there carry **embedded SPARQL** via `sh:SPARQLTarget`, `sh:SPARQLConstraint`,
`sh:SPARQLAskValidator` and `sh:SPARQLRule`. They use `sh:prefixes → sh:declare → sh:prefix/sh:namespace`
to declare their namespaces (which the validator resolves automatically) and they do
**not** use named graphs — exactly the assumption the Phase 2 design is built on.

## How to wire the upstream shapes into your build

The ENTSO-E library is published under its own licence and is therefore *not* vendored
into this repository. To run the validator against it locally:

1. Clone the upstream repo somewhere outside this tree:
   ```bash
   git clone https://github.com/entsoe/application-profiles-library.git
   ```
2. Point the loader at the shapes you want to validate. Any Jena {@link org.apache.jena.graph.Graph}
   that contains the shapes works — load it however you prefer
   (`RDFDataMgr.loadGraph(...)`, `RDFParser.source(...).parse(...)`, etc.).

```java
Graph shapes = RDFDataMgr.loadGraph(
        "/path/to/application-profiles-library/shapes/SomeProfile.ttl");

SparqlValidationApi api = new SparqlValidationApi(
        RdfsSchemaIndex.fromCimRegistry(myCimRegistry));

// Which profiles does this shape file *need*?
Collection<VersionIri> needed = api.getShaclProfileDependencies(shapes);

// Do all embedded SPARQL fragments resolve against those profiles?
ShaclValidationResult r = api.validateShacl(shapes, needed);
if (!r.isValid()) {
    r.embeddedResults().forEach(er -> er.result().annotations().forEach(System.out::println));
}
```

## Why this isn't a checked-in test resource

Including the upstream shapes here would (a) duplicate licensed material and (b) tie this
module's build to the upstream release cadence. The handcrafted Turtle fixtures under
[`src/test/resources/shacl/`](../../src/test/resources/shacl/) exercise the same code paths
(`sh:SPARQLTarget`, `sh:SPARQLConstraint` with `sh:prefixes`, `sh:SPARQLAskValidator`,
`sh:SPARQLRule`, cross-profile dependency inference) without that coupling.

If you want a CI job that runs against the live ENTSO-E shapes, write a separate test
class outside this module that loads them from a local clone or a build-time download —
the public API (`SparqlValidationApi.validateShacl`, `getShaclProfileDependencies`, etc.)
is the only contract you need.
