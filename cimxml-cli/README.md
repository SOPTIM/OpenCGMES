# cimxml2jsonld

Converts the CIMXML files of one CGMES IGM, together with the RDFS profiles they reference, into a
single JSON-LD dataset with the named graphs `cgmes:schema:<keyword>`, `cgmes:header:<keyword>` and
`cgmes:model:<keyword>`, which can be queried with SPARQL.

```bash
bin/cimxml2jsonld --schema rdfs/ --model igm/ --output igm.jsonld.gz
```

Requires Java 21 or newer; without Java, use the Docker image `ghcr.io/soptim/cimxml-cli`.

📖 **Documentation: <https://opencgmes.soptim.de/cimxml-cli/overview>**: options, memory, Docker,
input rules and how to fix rejected input.

## Build

```bash
mvn -pl cimxml-cli -am package
# target/cimxml-cli-<version>.zip  distribution with start scripts
# target/cimxml-cli-<version>.jar  fat JAR
```

The integration tests use official ENTSO-E data from git submodules and are skipped unless they are
initialized: `git submodule update --init --recursive`.
