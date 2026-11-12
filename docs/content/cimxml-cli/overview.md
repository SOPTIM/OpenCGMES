---
title: Overview
sidebar_position: 1
---

# cimxml2jsonld

`cimxml2jsonld` converts the CIMXML files of one CGMES IGM (independent grid model), together with
the RDFS profiles they reference, into a single JSON-LD dataset. Loaded into a SPARQL store such as
[Apache Jena Fuseki](https://jena.apache.org/documentation/fuseki2/), it lets you query grid models
with SPARQL.

The [CIMNotebook](/cimnotebook/overview) editor plugins recognize such a dataset on a SPARQL HTTP
endpoint, as Fuseki provides one, and offer syntax highlighting and autocompletion for it.

## The dataset

Every profile of the IGM ends up in its own named graphs. `<keyword>` stands for the profile
keyword from the RDFS file, e.g. `EQ`, `TP`, `SSH`, `SV` or `EQBD`:

| Graph | Content |
| --- | --- |
| `cgmes:schema:<keyword>` | the RDFS profile, only for profiles referenced by a model |
| `cgmes:schema:FH` | the file header profile of the models' CGMES version |
| `cgmes:header:<keyword>` | the `md:FullModel` header of the model |
| `cgmes:model:<keyword>` | the model data |

Example SPARQL query, all lines of the EQ model with their resistance:

```sparql
PREFIX cim: <http://iec.ch/TC57/CIM100#>

SELECT ?line ?name ?r
WHERE {
  GRAPH <cgmes:model:EQ> {
    ?line a cim:ACLineSegment ;
          cim:IdentifiedObject.name ?name ;
          cim:ACLineSegment.r ?r .
  }
}
```

### Files with several profiles

If the header of a CIMXML file declares several profiles, for example EQ, OP and SC:

```xml
<md:Model.profile>http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0</md:Model.profile>
<md:Model.profile>http://iec.ch/TC57/ns/CIM/Operation-EU/3.0</md:Model.profile>
<md:Model.profile>http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/3.0</md:Model.profile>
```

the file is added under each of them: its data goes into `cgmes:model:EQ`, `cgmes:model:OP` and
`cgmes:model:SC`, its header into the matching `cgmes:header:<keyword>` graphs, and all three RDFS
profiles are included. The output file contains the data once per graph. The progress output shows
such a file as `EQ+OP+SC`.

If a single RDFS profile covers all profiles of the header, only that profile is used.

## Quick start

Download `cimxml-cli-<version>.zip` from the
[GitHub releases](https://github.com/SOPTIM/OpenCGMES/releases), unpack it and run (Java 21 or newer
required):

```bash
cimxml-cli-<version>/bin/cimxml2jsonld --schema rdfs/ --model igm/ --output igm.jsonld.gz
```

- `rdfs/` contains the CGMES RDFS files (e.g. the
  [ENTSO-E application profiles](https://github.com/entsoe/application-profiles-library)),
- `igm/` the CIMXML files of one IGM, plain or zipped,
- `igm.jsonld.gz` is the dataset; load it into your SPARQL store.

Without Java, use the [Docker image](/cimxml-cli/usage#docker). All options are described in
[Usage](/cimxml-cli/usage); what the input must look like and how to fix rejected input is
described in [Input and problems](/cimxml-cli/input).
