---
title: Validation Checks
sidebar_position: 4
---

# Validation Checks

This is the **canonical catalogue** of the diagnostic codes CIMVocabCheck emits. The
[CLI](/cimvocabcheck/cli), [language server](/cimvocabcheck/language-server), and
[editor integrations](/cimnotebook/overview) all surface these same codes (a stable
`SparqlValidationCode` enum).

Each finding carries a **severity** (`ERROR`, `WARN`, `INFO`), a **code**, a human-readable
**message**, and — where resolvable — a **line/column** and the offending **term**. How severities
map to pass/fail is controlled by [`strictness`](/cimvocabcheck/configuration#strictness).

## All codes at a glance

| Code | Severity | Category | Triggers when |
| --- | --- | --- | --- |
| `SYNTAX_ERROR` | ERROR | Syntax | The document is not syntactically valid SPARQL or Turtle |
| `UNKNOWN_CLASS` | ERROR | Existence | Class IRI not found in the selected profiles |
| `UNKNOWN_PROPERTY` | ERROR | Existence | Property IRI not found in the selected profiles |
| `UNKNOWN_VOCABULARY_TERM` | ERROR | Existence | Typo in a closed standard vocabulary (`rdf`/`rdfs`/`owl`/`sh`) |
| `GRAPH_NOT_CONFIGURED` | WARN | Scope | `GRAPH <g>` used but `<g>` has no mapped profiles (named-graph scope only) |
| `UNSUPPORTED_DYNAMIC_PROPERTY` | WARN | Scope | Variable predicate / type-object that cannot be statically resolved |
| `PROPERTY_NOT_ALLOWED_FOR_CLASS` | ERROR | Semantic | Subject's type is not a subclass of any `rdfs:domain` of the property |
| `QUERY_IMPLIED_TYPE` | INFO | Semantic | Subject has no `rdf:type` but the property implies exactly one domain |
| `DATATYPE_MISMATCH` | WARN | Semantic | Literal object's datatype is incompatible with `rdfs:range` |
| `NODE_KIND_INCOMPATIBLE_WITH_RANGE` | WARN | SHACL | `sh:nodeKind` conflicts with the property's `rdfs:range` |
| `DATATYPE_INCOMPATIBLE_WITH_RANGE` | WARN | SHACL | `sh:datatype` used on an object property (range is a class) |
| `CLASS_INCOMPATIBLE_WITH_RANGE` | WARN | SHACL | `sh:class` used on a datatype property (range is a literal type) |
| `INVALID_CARDINALITY` | ERROR | SHACL | `sh:minCount` exceeds `sh:maxCount` on the same property shape |

:::note The "exists in another profile" hint
When a class or property does not exist in the *selected* profiles but **does** exist in another
loaded profile, the `UNKNOWN_CLASS` / `UNKNOWN_PROPERTY` annotation carries a
`foundInOtherProfiles` list naming where it lives — a hint that you may have the wrong profile in
scope rather than a real typo. Editors surface this as a hint on the underline.
:::

## Existence checks

Every class and property IRI in the query or shapes graph is looked up in the schema index. These
fire regardless of how completely the schema annotates semantics:

- `UNKNOWN_CLASS` / `UNKNOWN_PROPERTY` — the IRI is not declared by any selected profile.
- `UNKNOWN_VOCABULARY_TERM` — a term in a **closed** standard vocabulary (`rdf`, `rdfs`, `owl`,
  `sh`) that the official W3C vocabulary does not define, e.g. `rdf:typ`, `owl:Clas`,
  `sh:minCountt`. Controlled by [`standardVocabulary`](/cimvocabcheck/configuration#standardvocabulary).

## Semantic checks

When the schema index carries `rdfs:domain`, `rdfs:range`, or `rdfs:subClassOf` (as loaded from
real CGMES RDFS files), these additional checks run:

- `PROPERTY_NOT_ALLOWED_FOR_CLASS` — the subject's `rdf:type` is not a subclass of any
  `rdfs:domain` of the property; also fires when adjacent path-chain segments have disjoint
  range/domain sets.
- `QUERY_IMPLIED_TYPE` (INFO) — the subject carries no explicit `rdf:type` but the property has
  exactly one domain, so the type is implied.
- `DATATYPE_MISMATCH` — a literal object's datatype is incompatible with the property's
  `rdfs:range`.

`rdfs:subClassOf` traversal is transitive and cycle-safe across the union of all profiles in scope.

:::tip Lenience policy — silent when the schema is silent
A semantic check is **skipped** when the schema doesn't carry the information it needs: no
`rdfs:domain` → no domain/implied-type check; no `rdfs:range` (or a class range) → no datatype
check; inverse/alternative/repetition path operators → path-chain check skipped for that segment.
See [Known limitations](/cimvocabcheck/limitations) for the full policy.
:::

## SHACL property-shape checks

Two passes run over a SHACL shapes graph (see [Known limitations](/cimvocabcheck/limitations#shacl-structural-checks-vs-sparql-validated)
for the full breakdown). On every property shape (any blank node with `sh:path`):

- `NODE_KIND_INCOMPATIBLE_WITH_RANGE` — `sh:nodeKind` forces a non-literal but `rdfs:range` is a
  datatype (or vice versa).
- `DATATYPE_INCOMPATIBLE_WITH_RANGE` — `sh:datatype` on a property whose range is a class.
- `CLASS_INCOMPATIBLE_WITH_RANGE` — `sh:class` on a property whose range is a literal datatype.
- `INVALID_CARDINALITY` — `sh:minCount` exceeds `sh:maxCount`.

Shape-structure findings (`sh:targetClass`, `sh:class`, `sh:path` against the schema) reuse the
existence codes above. Embedded SPARQL (`sh:sparql`, `sh:target`, `sh:validator`, `sh:rule`) is
extracted and validated with the full SPARQL check set.

## See also

- [Configuration → strictness](/cimvocabcheck/configuration#strictness) — promote warnings to
  errors for CI.
- [Known limitations](/cimvocabcheck/limitations) — what is intentionally not checked, and why.
- [API reference](/cimvocabcheck/api) — the `SparqlValidationAnnotation` record these codes live on.
