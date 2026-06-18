---
title: Library & Tests
sidebar_position: 6
---

# Using CIMVocabCheck from Java & JUnit

`cimvocabcheck-core` is a plain Java library. You can build a schema index from your CGMES
profiles once and validate any number of SPARQL queries or SHACL shapes against it — ideal for
**unit tests** and **build-time guards** that fail the moment a query or shape stops matching the
schema.

This page is a practical cookbook. For the full type-by-type reference see the
[API page](/cimvocabcheck/api).

## Dependency

```xml
<dependency>
    <groupId>de.soptim.opencgmes</groupId>
    <artifactId>cimvocabcheck-core</artifactId>
    <version>@cimvocabcheckVersion@</version>
</dependency>
```

```gradle
testImplementation 'de.soptim.opencgmes:cimvocabcheck-core:@cimvocabcheckVersion@'
```

The library pulls in [`cimxml`](/cimxml/overview) (for parsing CIM profiles) and Apache Jena.

## Build a schema index

The entry point is `SparqlValidationApi`, constructed with a `SchemaIndex`. The standard
implementation, `RdfsSchemaIndex`, is built from CGMES RDFS profiles. The most direct route is via
the CIMXML profile registry, which parses the profile files and resolves their datatypes:

```java
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

static SparqlValidationApi loadApi(Path rdfsDir) throws IOException {
    CimProfileRegistry registry = new CimProfileRegistryStd();
    RdfXmlParser parser = new RdfXmlParser();

    try (var paths = Files.list(rdfsDir)) {
        paths.filter(p -> p.toString().endsWith(".rdf"))
             .sorted()
             .forEach(p -> {
                 try {
                     registry.register(parser.parseCimProfile(p));
                 } catch (IllegalArgumentException duplicateVersionIri) {
                     // Some CGMES 2.4 Equipment variants share version IRIs — skip duplicates.
                 } catch (IOException e) {
                     throw new UncheckedIOException(e);
                 }
             });
    }

    return new SparqlValidationApi(RdfsSchemaIndex.fromCimRegistry(registry));
}
```

Build the `SparqlValidationApi` **once** (e.g. in a `@BeforeAll` / static initializer) and reuse it
across tests — parsing the profiles is the expensive part.

:::tip A tiny synthetic index for fast unit tests
When you don't want to load real profiles, build a minimal index with the builder:

```java
RdfsSchemaIndex index = RdfsSchemaIndex.builder()
        .addProfile(
            "http://example.org/EQ/1.0",
            java.util.List.of("http://iec.ch/TC57/CIM100#ACLineSegment"),
            java.util.List.of("http://iec.ch/TC57/CIM100#ACLineSegment.r"))
        .build();
SparqlValidationApi api = new SparqlValidationApi(index);
```
:::

## Validate a SPARQL query

```java
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationResult;

SparqlValidationResult result = api.validateSparql("""
    PREFIX cim: <http://iec.ch/TC57/CIM100#>
    SELECT * WHERE {
        ?line a cim:ACLineSegment ;
              cim:IdentifiedObject.name ?name .
    }
    """);

System.out.println("valid: " + result.isValid());
result.annotations().forEach(a ->
    System.out.printf("[%s] %s (line %s) — %s%n",
        a.severity(), a.code(), a.line(), a.message()));
```

`validateSparql` auto-detects query vs. SPARQL Update. Multiple `;`-separated queries in one string
are split and validated independently with line offsets preserved.

## Assert in a JUnit test

A query that references the schema correctly should produce no errors; a typo'd property should
produce `UNKNOWN_PROPERTY`:

```java
import static org.junit.jupiter.api.Assertions.*;

import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationResult;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QueryGuardrailTest {

    static SparqlValidationApi api;

    @BeforeAll
    static void setUp() throws Exception {
        api = loadApi(Path.of("src/test/resources/cgmes-3.0"));
    }

    @Test
    void productionQueryMatchesSchema() {
        SparqlValidationResult r = api.validateSparql("""
            PREFIX cim: <http://iec.ch/TC57/CIM100#>
            SELECT ?line ?name WHERE {
                ?line a cim:ACLineSegment ;
                      cim:IdentifiedObject.name ?name .
            }
            """);

        assertTrue(r.isValid(), () -> "Unexpected findings:\n" + render(r));
    }

    @Test
    void typoIsCaught() {
        SparqlValidationResult r = api.validateSparql("""
            PREFIX cim: <http://iec.ch/TC57/CIM100#>
            SELECT * WHERE { ?l a cim:ACLineSegment ; cim:DoesNotExist ?x }
            """);

        assertFalse(r.isValid());
        assertTrue(
            r.annotations().stream()
                .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_PROPERTY),
            "expected UNKNOWN_PROPERTY");
    }

    private static String render(SparqlValidationResult r) {
        StringBuilder sb = new StringBuilder();
        r.annotations().forEach(a ->
            sb.append(a.severity()).append(' ').append(a.code())
              .append(" — ").append(a.message()).append('\n'));
        return sb.toString();
    }
}
```

## Validate SHACL shapes

`validateShacl` takes a Jena `Graph`. It validates the **shape structure** and any **embedded
SPARQL** in one pass:

```java
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.RDFDataMgr;

Graph shapes = RDFDataMgr.loadGraph("my-shapes.ttl");
ShaclValidationResult r = api.validateShacl(shapes);

// Structural findings (unknown class/property in sh:targetClass, sh:class, sh:path …)
r.shapeAnnotations().forEach(a ->
    System.out.printf("[%s] %s — %s%n", a.severity(), a.code(), a.message()));

// One result per embedded SPARQL fragment (sh:sparql, sh:rule, sh:validator, sh:target)
for (var fragment : r.embeddedResults()) {
    System.out.println("fragment: " + fragment.embedded().rawQuery());
    fragment.result().annotations().forEach(a ->
        System.out.printf("  [%s] %s — %s%n", a.severity(), a.code(), a.message()));
}

assertTrue(r.isValid());   // true when neither structure nor any embedded query has errors
```

`$this` inside a `sh:sparql` constraint is automatically typed as the enclosing shape's
`sh:targetClass`, and `$PATH` is substituted with the property shape's `sh:path` — no extra wiring.

## Fail CI on warnings (strictness)

For pipelines, treat warnings as failures by passing a `StrictnessLevel` to `isValid`:

```java
import de.soptim.opencgmes.cimvocabcheck.core.StrictnessLevel;

SparqlValidationResult r = api.validateSparql(queryText);

// DEFAULT: only ERRORs fail. STRICT: warnings count too. PEDANTIC: infos/hints too.
assertTrue(r.isValid(StrictnessLevel.STRICT));
```

The same four levels are available on the [CLI](/cimvocabcheck/cli) (`--strictness`) and in
[`opencgmes.json`](/cimvocabcheck/configuration#strictness).

## Discover profile dependencies

Ask which classes, properties, graphs, or profiles a query references — useful for tests that
assert a query only touches the profiles it should:

```java
import org.apache.jena.graph.Node;
import de.soptim.opencgmes.cimvocabcheck.core.schema.VersionIri;
import java.util.Collection;

Collection<Node>       classes  = api.getClassDependencies(query);
Collection<Node>       props    = api.getPropertyDependencies(query);
Collection<Node>       graphs   = api.getGraphDependencies(query);
Collection<VersionIri> profiles = api.getProfileDependencies(query);
```

Equivalent `getShacl…Dependencies(Graph)` and `getUpdate…Dependencies(String)` methods exist for
shapes graphs and SPARQL Update — see the [API reference](/cimvocabcheck/api#dependency-extraction).

## See also

- [API reference](/cimvocabcheck/api) — every public type and overload.
- [Validation checks](/cimvocabcheck/validation-checks) — the codes you assert on.
- [Endpoints](/cimvocabcheck/endpoints) — build the index from a live SPARQL endpoint instead of files.
