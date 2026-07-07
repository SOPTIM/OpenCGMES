---
title: SPARQL Queries
sidebar_position: 6
---

# SPARQL Queries

Because a parsed model is an ordinary Apache Jena `DatasetGraph`, you can run standard SPARQL over it
with Jena's ARQ engine — no special API. This page shows how to query parsed CIM data and points you
to static query validation when you want to catch query mistakes before you run anything.

## Querying parsed CIM data

Parse a model, build a query with the appropriate CIM namespace prefix, and execute it against the
`CimDatasetGraph`:

```java
import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import org.apache.jena.query.*;
import java.nio.file.Path;

CimXmlParser parser = new CimXmlParser();
CimDatasetGraph dataset = parser.parseCimModel(Path.of("model.xml"));

String queryString = """
    PREFIX cim: <http://iec.ch/TC57/CIM100#>
    SELECT ?equipment ?name
    WHERE {
        ?equipment a cim:Equipment ;
                   cim:IdentifiedObject.name ?name .
    }
    """;

Query query = QueryFactory.create(queryString);

// Execute query on the CIM dataset
try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        // Process results
    }
}
```

:::tip Match the CIM version's namespace
Use the prefix that matches your model's CIM version: `http://iec.ch/TC57/2013/CIM-schema-cim16#`
for CIM 16, `http://iec.ch/TC57/CIM100#` for CIM 17, and `https://cim.ucaiug.io/ns#` for CIM 18.
See [Compliance](/cimxml/compliance) for the full table.
:::

## Named graphs in the dataset

A full model exposes its body as the **default graph** and its header under a named graph, while a
difference model keeps the forward/reverse/preconditions containers as separate named graphs (see
[Architecture](/cimxml/architecture)). Plain `WHERE { ... }` patterns match the default graph (the
body); use `GRAPH ?g { ... }` if you need to query header or difference graphs explicitly.

## Validate queries statically first

Running a query tells you whether it returns rows — not whether it is *correct* for the schema. A
typo in a class name or a property used on the wrong domain silently returns nothing.
[CIMVocabCheck](/cimvocabcheck/overview) validates SPARQL **statically** against the CIM/RDFS
profiles — flagging unknown classes and properties, domain/range violations, and datatype
mismatches — without executing the query and without needing any RDF data. Pair it with CIMXML to
catch query mistakes before they reach a dataset.
