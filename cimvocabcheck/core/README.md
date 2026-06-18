# OpenCGMES — CIMVocabCheck

Static SPARQL and SHACL validation against RDFS / CIM profile schemas. CIMVocabCheck answers
*"does this query (or shapes graph) make sense for the schema I'm working with?"* — **without
executing anything and without needing any RDF data**. It catches unknown classes/properties,
domain/range violations, datatype mismatches, invalid SHACL cardinalities, and standard-vocabulary
typos at development time, in unit tests, or in CI.

CIMVocabCheck ships as a library (`cimvocabcheck-core`), a CLI (`cimvocabcheck-cli`), and an LSP
language server, **CIMLangServer** (`cimvocabcheck-lsp`). The [CIMNotebook](../../cimnotebook)
editor integrations front the language server.

## 📖 Documentation

Full documentation lives at **<https://opencgmes.soptim.de/cimvocabcheck/overview>**:

- [Overview](https://opencgmes.soptim.de/cimvocabcheck/overview) ·
  [Getting started](https://opencgmes.soptim.de/cimvocabcheck/getting-started)
- [Configuration (`opencgmes.json`)](https://opencgmes.soptim.de/cimvocabcheck/configuration) ·
  [Validation checks](https://opencgmes.soptim.de/cimvocabcheck/validation-checks)
- [Library & tests (Java/JUnit)](https://opencgmes.soptim.de/cimvocabcheck/library-and-tests) ·
  [API reference](https://opencgmes.soptim.de/cimvocabcheck/api)
- [CLI](https://opencgmes.soptim.de/cimvocabcheck/cli) ·
  [Language server](https://opencgmes.soptim.de/cimvocabcheck/language-server) ·
  [Endpoints](https://opencgmes.soptim.de/cimvocabcheck/endpoints)

## Quick start

Both examples require the ENTSO-E Application Profiles submodule:

```bash
git submodule update --init
mvn -q install -DskipTests
mvn -q -pl cimvocabcheck/core exec:java          # SPARQL example
```

See [Getting started](https://opencgmes.soptim.de/cimvocabcheck/getting-started) for the SHACL
example and expected output.

## License

Licensed under the [Apache License 2.0](../../LICENSE). The bundled W3C standard vocabularies
(`rdf`, `rdfs`, `owl`, `sh`), used for standard-vocabulary term checking, are redistributed under
the W3C Software and Document License. © World Wide Web Consortium.
