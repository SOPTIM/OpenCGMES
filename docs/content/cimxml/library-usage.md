---
title: Library Usage
sidebar_position: 8
---

# Library Usage

This page shows how to use CIMXML as a dependency inside your own project — in particular, how to
parse a model and assert on it from a unit test. Everything below uses only public API:
[`CimXmlParser`](/cimxml/architecture), [`CimDatasetGraph`](/cimxml/architecture), and
[`CimModelHeader`](/cimxml/architecture). Add the dependency first (see
[Installation](/cimxml/installation)).

## Parsing in your code

The parser is thread-safe for parsing, and its registry is synchronized, so a single
`CimXmlParser` can be reused across parses. Pick the overload that matches your input —
`Path`, `InputStream`, or `Reader`:

```java
CimXmlParser parser = new CimXmlParser();

// From a file path (throws IOException)
CimDatasetGraph fromPath = parser.parseCimModel(Path.of("model.xml"));

// From any reader/stream (e.g. a classpath resource or in-memory string)
CimDatasetGraph fromReader = parser.parseCimModel(new StringReader(cimxml));
```

## A JUnit 5 example

The test below parses a small full model from an in-memory string, asserts it is a full model, reads
the profiles from its header, and counts the triples in the combined graph. It relies only on
methods that exist on the public types, so it compiles against the published artifact.

```java
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CimXmlParserTest {

    private static final String FULL_MODEL = """
        <?xml version="1.0" encoding="utf-8"?>
        <rdf:RDF xmlns:cim="http://iec.ch/TC57/CIM100#"
                 xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                 xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <md:FullModel rdf:about="urn:uuid:08984e27-811f-4042-9125-1531ae0de0f6">
            <md:Model.profile>http://soptim.de/CIM/MyProfile/1.1</md:Model.profile>
          </md:FullModel>
          <cim:MyEquipment rdf:ID="_f67fc354-9e39-4191-a456-67537399bc48">
            <cim:IdentifiedObject.name>My Custom Equipment</cim:IdentifiedObject.name>
          </cim:MyEquipment>
        </rdf:RDF>
        """;

    @Test
    void parsesFullModelHeaderAndBody() {
        CimXmlParser parser = new CimXmlParser();

        CimDatasetGraph dataset = parser.parseCimModel(new StringReader(FULL_MODEL));

        // It is a full model, not a difference model
        assertTrue(dataset.isFullModel());

        // The header exposes the declared profiles
        var header = dataset.getModelHeader();
        Node model = header.getModel();
        Set<Node> profiles = header.getProfiles();
        assertEquals(1, profiles.size());

        // The body holds the model data; the combined graph holds header + body
        Graph body = dataset.getBody();
        Graph combined = dataset.fullModelToSingleGraph();

        assertEquals(2, body.size());      // type + name of the equipment
        assertEquals(4, combined.size());  // header (2) + body (2)
    }
}
```

:::note Why `fullModelToSingleGraph()` has four triples
The header contributes the `rdf:type md:FullModel` and `md:Model.profile` triples, and the body
contributes the equipment's `rdf:type` and `cim:IdentifiedObject.name` — two plus two. This mirrors
the library's own `fullModelToSingleGraph` test.
:::

## Difference-model assertions

To exercise difference handling, parse a base full model and a difference model, then assert on the
applied result. The library's `differenceModelToFullModel` test demonstrates the full pattern —
asserting that unchanged elements remain, updated properties take their new value, added elements
appear, and removed elements are gone — using `Graph#contains(...)` on the resulting graph. See
[Difference models](/cimxml/difference-models) for the structure and validation rules.

:::tip Validate your queries too
If your tests run SPARQL against parsed models, consider validating those queries statically with
[CIMVocabCheck](/cimvocabcheck/overview) so schema mistakes fail fast in the same test suite.
:::
