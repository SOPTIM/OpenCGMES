---
title: CGMES Background
sidebar_position: 1
---

# CGMES Background

If you're new to the power-systems domain, the acronyms behind OpenCGMES — CIM, CGMES, RDFS, SHACL, SPARQL — can be a lot at once. This page is a short primer that ties them together: what each is, how they relate, and where CIMXML files fit in. The product docs assume this background; come back here whenever a term is unfamiliar.

## CIM (IEC 61970)

The **Common Information Model (CIM)** is an international standard (the IEC 61970 / 61968 / 62325 series) that defines a shared vocabulary for describing electrical power networks: substations, transformers, AC line segments, breakers, measurements, and how they connect. CIM is defined as an object model (originally in UML), and its classes and properties are exported as machine-readable ontologies — the building blocks everything else in this ecosystem references.

A CIM term looks like `cim:ACLineSegment` (a class) or `cim:ACLineSegment.r` (a property — resistance of that line segment), where `cim:` expands to a versioned namespace IRI.

## CGMES (ENTSO-E grid model exchange)

The **Common Grid Model Exchange Standard (CGMES)** is ENTSO-E's profile of CIM, created so European Transmission System Operators can exchange grid models reliably. CGMES doesn't reinvent CIM — it **constrains and packages** it into a set of **profiles**, each covering one concern of a model:

- **EQ** — Equipment (the network's physical elements)
- **TP** — Topology (how nodes connect)
- **SSH** — Steady State Hypothesis (operating set-points)
- **SV** — State Variables (a computed network state)
- …and several others (diagram layout, geographical location, dynamics, etc.)

Each profile is published as an **RDFS** schema file that says which classes and properties are valid in that profile. A complete grid model is a set of instance files, one per profile, that reference each other.

## RDF, RDFS, SHACL, SPARQL — and how they relate

CGMES is expressed in the **RDF (Resource Description Framework)** data model: everything is a set of *triples* (subject – predicate – object). On top of RDF sit three W3C technologies you'll meet constantly:

| Technology | What it is | Role in CGMES |
| --- | --- | --- |
| **RDFS** (RDF Schema) | A vocabulary-definition language — declares classes, properties, `rdfs:domain`, `rdfs:range`, `rdfs:subClassOf` | How each CGMES **profile** describes its valid terms (the *schema*) |
| **SHACL** (Shapes Constraint Language) | A constraint language — declares *shapes* that data must satisfy (cardinalities, value types, target classes) | How CGMES expresses validation rules over instance data |
| **SPARQL** | The query (and update) language for RDF | How you ask questions of, or transform, CGMES data |

The relationship in one sentence: **RDFS profiles** define the vocabulary, **instance data** uses that vocabulary, **SHACL** constrains the instance data, and **SPARQL** queries it. OpenCGMES's [CIMVocabCheck](/cimvocabcheck/overview) checks your SPARQL and SHACL *against the RDFS profiles statically* — confirming your queries and shapes use real, correctly-applied CIM terms — without needing any instance data.

## How CIMXML carries instance data

A grid model's actual data — the specific substations, lines, and set-points — is exchanged as **CIMXML** files, an RDF/XML serialization standardised by **IEC 61970-552**. A CIMXML file is one of two kinds:

- **FullModel** — a complete snapshot of a profile's data.
- **DifferenceModel** — a delta (forward/reverse differences) to apply to a base model.

Each file carries a model header listing the **profiles** it conforms to and its dependencies on other model parts. OpenCGMES's [CIMXML](/cimxml/overview) library parses these files into Apache Jena RDF graphs, resolving CIM datatypes from the registered profiles and normalising CIM UUIDs.

## CIM version table

The CIM namespace IRI in a file tells you which CIM version it uses. CIMXML supports the three current generations:

| CIM version | Namespace IRI |
| --- | --- |
| CIM 16 | `http://iec.ch/TC57/2013/CIM-schema-cim16#` |
| CIM 17 | `http://iec.ch/TC57/CIM100#` |
| CIM 18 | `https://cim.ucaiug.io/ns#` |

:::note CGMES generation ↔ CIM version
**CGMES 2.4.15** is built on **CIM 16** (`…CIM-schema-cim16#`), while **CGMES 3.0** is built on **CIM 100** (`http://iec.ch/TC57/CIM100#`). OpenCGMES's integration tests exercise both generations — see [Testing](/developer-guide/testing).
:::

## External references

- [IEC 61970-552 — CIMXML model exchange format](https://webstore.iec.ch/publication/25939)
- [ENTSO-E — CGMES (Common Grid Model Exchange Standard)](https://www.entsoe.eu/data/cim/cim-for-grid-models-exchange/)
- [Apache Jena](https://jena.apache.org/) — the RDF framework OpenCGMES is built on
- [W3C SHACL — Shapes Constraint Language](https://www.w3.org/TR/shacl/)
- [W3C SPARQL 1.1 Query Language](https://www.w3.org/TR/sparql11-query/)
