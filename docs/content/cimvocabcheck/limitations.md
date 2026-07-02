---
title: Known Limitations
sidebar_position: 12
---

# Known Limitations

CIMVocabCheck is a **static** validator: it reasons about a query or shapes graph against a schema,
without data and without execution. That makes some checks impossible or intentionally conservative.
This page is the honest list of what it does **not** catch, so you know where to apply judgement.

The guiding principle is **silent-on-missing**: when the schema doesn't carry the information a
check needs, the check is skipped rather than guessed.

## Dynamic predicates and classes

When a triple pattern uses a variable as the predicate or `rdf:type` object (e.g. `?s ?p ?o` or
`?s a ?cls`), CIMVocabCheck cannot perform per-triple existence or domain/range checks. It emits a
single `UNSUPPORTED_DYNAMIC_PROPERTY` (WARN) for the query to flag that static validation is
incomplete — it does not try to enumerate what the variable might be. A variable in **subject**
position is fine; only variable *predicates* and *type-object* positions are affected.

**Exception — SHACL `$PATH`:** in `$this $PATH ?value` patterns, `$PATH` is resolved to the
enclosing property shape's `sh:path` before analysis, so no warning is emitted for those.

## SERVICE blocks

`SERVICE { }` federated-query blocks are silently skipped — CIMVocabCheck has no access to the
remote endpoint's schema, so no class/property checks run inside the SERVICE body. No diagnostic is
emitted.

## Incomplete schema — missing `rdfs:domain` / `rdfs:range`

All semantic checks follow the silent-on-missing policy:

- No `rdfs:domain` for a property → no `PROPERTY_NOT_ALLOWED_FOR_CLASS` or `QUERY_IMPLIED_TYPE`.
- No `rdfs:range` → no `DATATYPE_MISMATCH`.

So a query using a property against a completely wrong subject type produces no error if the profile
omits `rdfs:domain` for it. The **existence** checks (`UNKNOWN_CLASS` / `UNKNOWN_PROPERTY`) still
fire regardless.

:::note Practical consequence for CIM users
The CGMES RDFS files published by ENTSO-E declare `rdfs:domain`/`rdfs:range` on most properties, so
semantic checks are generally active. Proprietary or partial profile files may suppress them
silently. `rdfs:subClassOf` traversal is transitive but limited to what the loaded profiles declare.
:::

## Path-chain checks

Forward property-path chains (`ex:p1/ex:p2/ex:p3`) are checked for range–domain compatibility
between adjacent segments. These operators are **not** checked in chain context (each URI segment is
still checked for existence):

- Inverse paths (`^ex:p`)
- Alternative paths (`ex:p1 | ex:p2`)
- Zero-or-more / one-or-more / zero-or-one (`*`, `+`, `?`)

## Datatype precision

Datatype range checks group XSD types into coarse compatibility buckets:

- All numeric XSD types (`xsd:integer`, `xsd:float`, `xsd:decimal`, …) are one bucket — using the
  wrong numeric subtype is not flagged.
- `rdf:langString` is treated as compatible with `xsd:string`.

Precision mismatches *within* a bucket do not produce a `DATATYPE_MISMATCH`.

## Source positions

Line/column are located by searching the original query text for the IRI in three forms
(`<full-IRI>`, `prefix:local`, and the `a` keyword), picking the occurrence nearest the offending
triple's subject. Positions may be wrong or missing when:

- the IRI appears only inside a string literal or comment (a simple bracket/string tracker can be
  confused by complex multi-line strings);
- the IRI is a constant in a `BIND` / `VALUES` / `FILTER` expression — such constants **are**
  validated (existence, enumeration, vocabulary typos), but their reported source position may be
  approximate or absent;
- the finding is a SHACL **shape-level** annotation — the shapes graph is RDF, not text, so the
  position is resolved by searching the Turtle source for the offending term. Findings from embedded
  SPARQL are mapped back to their line/column in the Turtle source by all consumers (CLI, editors,
  and Code Quality reports alike).

## SHACL: structural checks vs. SPARQL-validated

CIMVocabCheck makes **two passes** over a SHACL shapes graph.

**Pass 1 — structural analysis** validates shape declarations against the schema:

| Predicate | What is checked |
| --- | --- |
| `sh:targetClass` / `sh:class` | CIM class IRI must exist in the selected profiles. Standard-vocab classes, classes the file declares itself, and constraint-component parameters are exempt; a closed-namespace typo (e.g. `sh:Fooo`) is reported as `UNKNOWN_VOCABULARY_TERM` |
| `sh:path` | Every URI segment must be a known property (standard-vocab terms, header extensions, properties the file declares itself, and constraint-component parameter paths are exempt); a closed-namespace typo (e.g. `rdf:typ`) is reported as `UNKNOWN_VOCABULARY_TERM` |
| `sh:nodeKind` + `rdfs:range` | `NODE_KIND_INCOMPATIBLE_WITH_RANGE` |
| `sh:datatype` / `sh:class` vs `rdfs:range` | `DATATYPE_INCOMPATIBLE_WITH_RANGE` / `CLASS_INCOMPATIBLE_WITH_RANGE`; a non-XSD-datatype `sh:datatype` is `UNKNOWN_VOCABULARY_TERM` |
| `sh:minCount` + `sh:maxCount` | `INVALID_CARDINALITY` when min &gt; max; `CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY` when incompatible with the property's `cims:multiplicity` |
| `sh:in` / `sh:hasValue` | Enumeration-member and existence checks on the listed/required values |
| `sh:minInclusive` / `sh:maxInclusive` / `sh:minExclusive` / `sh:maxExclusive` | `INVALID_VALUE_RANGE` on a contradictory bound |
| `sh:targetSubjectsOf` / `sh:targetObjectsOf`, `sh:equals` / `sh:disjoint` / `sh:lessThan`(`OrEquals`), `sh:ignoredProperties` | Referenced property IRIs must exist |

Shapes carrying `sh:deactivated true` are skipped entirely.

**Pass 2 — embedded SPARQL** extracts and fully validates inline SPARQL (`sh:sparql`→`sh:select`,
`sh:target`→`sh:select`, `sh:validator`→`sh:ask`, `sh:rule`→`sh:construct`), with `$this` typed as
the shape's `sh:targetClass`. `QUERY_IMPLIED_TYPE` is suppressed inside embedded results (the
intermediate bindings are transient and not expected to be type-annotated).

**SHACL features not checked** (they require data or boolean-shape reasoning):
`sh:pattern`/`sh:flags`, `sh:qualifiedValueShape`/`Min`/`MaxCount`, `sh:uniqueLang`,
`sh:minLength`/`sh:maxLength`, `sh:or`/`sh:and`/`sh:not`/`sh:xone`, and `sh:targetNode`. The
shape-declared **`sh:severity`** is not honored either — findings use CIMVocabCheck's own
`ERROR`/`WARN` mapping (adjust it globally with [`strictness`](/cimvocabcheck/configuration#strictness))
rather than the severity a shape declares.

## `owl:imports` are not followed

When schema files are loaded from disk, `owl:imports` declarations are **not** resolved — every
profile file that contributes terms must be passed explicitly (via `schemas` / `schemasDirectory`,
`--schema`, or a schema-bearing endpoint). CGMES import references are namespace IRIs with no
IRI→file catalog, so there is nothing to resolve them against automatically.

## Roadmap

- Tighter path-chain checks (inverse / alternative operators).
- Remaining SHACL constraints (`sh:pattern`, qualified value shapes, logical constraints).
- Honor shape-declared `sh:severity`.
- `SERVICE` schema hints via config.
- Completion in full-IRI (`<http://…`) position.

See [Validation checks](/cimvocabcheck/validation-checks) for what **is** checked.
