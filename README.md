# OpenCGMES
Suite of tools for CGMES / CIM (IEC 61970) RDF - RDFS, SHACL and CIMXML

📖 **Full documentation: <https://opencgmes.soptim.de>** — guides, API reference, and examples for
[CIMXML](https://opencgmes.soptim.de/cimxml/overview),
[CIMVocabCheck](https://opencgmes.soptim.de/cimvocabcheck/overview), and
[CIMNotebook](https://opencgmes.soptim.de/cimnotebook/overview).

## Build

The repository ships an aggregator pom at the root. From a fresh checkout:

```bash
mvn test                                # build & test every module
mvn install                             # install every module into ~/.m2
mvn -pl cimvocabcheck/core -am verify   # build cimvocabcheck-core + its dependencies only
```

Each module also still builds standalone (`mvn -f cimxml/pom.xml verify` etc.); the
release workflows operate on the module poms directly.

## CimXml

A Java library for parsing CIMXML files into RDF graphs using Apache Jena. 
It supports both full models and difference models as defined in IEC 61970-552.
see [CimXmlParser](cimxml/README.md)

## CIMVocabCheck

Static SPARQL and SHACL validation against RDFS / CIM profile schemas — answers
*"does this query (or shapes graph) make sense for the schema I'm working with?"* without
executing the query and without requiring any RDF data. Ships as a library
(**CIMVocabCheck**), a CLI tool, and an LSP language server (**CIMLangServer**).
see [CIMVocabCheck](cimvocabcheck/core/README.md)

## CIMNotebook

Editor integrations — a VS Code extension and an IntelliJ plugin — that bring the
CIMVocabCheck / CIMLangServer validation to SPARQL and SHACL files (and SPARQL Notebook
cells) as you type.
see [CIMNotebook — VS Code](cimnotebook/vscode/README.md) · [CIMNotebook — IntelliJ](cimnotebook/intellij/README.md)

## Coming soon: QueryAndValidationUI

A web application for uploading RDF Schema, SHACL shapes, and CIM XML files,
querying the data using SPARQL, and validating the data against SHACL shapes.
see [QueryAndValidationUI](./QueryAndValidationUI/README.md)

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Commercial Support and Services

For organizations requiring commercial support, professional maintenance, integration services,
or custom extensions for this project, these services are available from **SOPTIM AG**.

Please feel free to contact us via [opencgmes@soptim.de](mailto:opencgmes@soptim.de).

## Contributing

We welcome contributions to improve this project.
Please see our [Contributing Guide](CONTRIBUTING.md) for details on how to submit pull requests, report issues, and suggest improvements.

## Code of Conduct

This project adheres to a code of conduct adapted from the [Apache Foundation's Code of Conduct](https://www.apache.org/foundation/policies/conduct).
We expect all contributors and users to follow these guidelines to ensure a welcoming and inclusive community.