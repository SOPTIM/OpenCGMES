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
| `INVALID_ENUM_VALUE` | ERROR | Semantic | Object IRI is not a member of the property's enumeration `rdfs:range` |
| `UNKNOWN_TERM_IN_EXPRESSION` | WARN | Existence | A constant IRI in a `FILTER`/`VALUES`/`BIND` expression is unknown to every index (class/property/enumeration member) |
| `NODE_KIND_INCOMPATIBLE_WITH_RANGE` | WARN | SHACL | `sh:nodeKind` conflicts with the property's `rdfs:range` |
| `DATATYPE_INCOMPATIBLE_WITH_RANGE` | WARN | SHACL | `sh:datatype` used on an object property (range is a class) |
| `CLASS_INCOMPATIBLE_WITH_RANGE` | WARN | SHACL | `sh:class` used on a datatype property (range is a literal type) |
| `INVALID_CARDINALITY` | ERROR | SHACL | `sh:minCount` exceeds `sh:maxCount` on the same property shape |
| `INVALID_VALUE_RANGE` | ERROR | SHACL | A value-range constraint is self-contradictory — a lower bound (`sh:minInclusive`/`sh:minExclusive`) exceeds an upper bound (`sh:maxInclusive`/`sh:maxExclusive`) |
| `CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY` | WARN | SHACL | `sh:minCount`/`sh:maxCount` cannot be satisfied given the property's declared CIM `cims:multiplicity` |

:::note The "exists in another profile" hint
When a class or property does not exist in the *selected* profiles but **does** exist in another
loaded profile, the `UNKNOWN_CLASS` / `UNKNOWN_PROPERTY` annotation carries a
`foundInOtherProfiles` list naming where it lives — a hint that you may have the wrong profile in
scope rather than a real typo. Editors surface this as a hint on the underline. This is exactly what
fires when a term is used in the wrong named graph under per-graph scoping — see
[Endpoints → per-graph validation](/cimvocabcheck/endpoints) and
[`namedGraphs`](/cimvocabcheck/configuration#namedgraphs).
:::

## Existence checks

Every class and property IRI in the query or shapes graph is looked up in the schema index. These
fire regardless of how completely the schema annotates semantics:

- `UNKNOWN_CLASS` / `UNKNOWN_PROPERTY` — the IRI is not declared by any selected profile.
- `UNKNOWN_VOCABULARY_TERM` — a term in a **closed** standard vocabulary (`rdf`, `rdfs`, `owl`,
  `sh`) that the official W3C vocabulary does not define, e.g. `rdf:typ`, `owl:Clas`,
  `sh:minCountt`. This is checked in **every** position a term can appear — predicate, `rdf:type`
  object, and other object positions such as `sh:nodeKind sh:IRII` or `sh:severity sh:Violatio`.
  The XSD datatype namespace is likewise a closed set, so a typo'd `sh:datatype xsd:strng` is
  reported here too (valid XSD 1.1 datatypes and `rdf:langString` are accepted).
  Controlled by [`standardVocabulary`](/cimvocabcheck/configuration#standardvocabulary).
- `UNKNOWN_TERM_IN_EXPRESSION` (WARN) — a constant IRI used in a `FILTER`, `VALUES`, or `BIND`
  expression that is unknown to every schema index (class, property, and enumeration member). It is
  a warning, not an error, because such a constant can legitimately be an instance IRI the schema
  does not track. Curated CGMES header terms (`rdf:Statements`, …) and open-namespace terms are
  accepted.

**Header extension terms.** The IEC 61970-552 / 600-2 model header coins a handful of terms
directly under the closed `rdf:` namespace (`rdf:Statements`, `rdf:Statements.subject`,
`rdf:Statements.predicate`, `rdf:Statements.object`). These are recognised as a curated header
vocabulary, so their use in `sh:path`, `sh:in`, and expressions is accepted rather than flagged as a
closed-namespace typo.

**Enumeration members.** CGMES enumeration values — individuals typed by an enumeration class, e.g.
`cim:WindGenUnitKind.offshore` — are indexed as a distinct kind of term. Used correctly in object
position (`?u cim:WindGeneratingUnit.windGenUnitType cim:WindGenUnitKind.offshore`) they are
recognised, not flagged. Using one where a class is expected (after `rdf:type`) or as a predicate is
reported with a message that names it as an enumeration value rather than a missing class/property.
A wrong value in object position — a typo, or a member of a different enumeration — is reported as
`INVALID_ENUM_VALUE` (see [Semantic checks](#semantic-checks)).

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
- `INVALID_ENUM_VALUE` — a URI object used for a property whose `rdfs:range` is an enumeration is
  not one of that enumeration's members (e.g. a misspelt `cim:WindGenUnitKind.offshroe`). Fires only
  when *every* declared range is an enumeration with members in scope, so instance IRIs of ordinary
  object properties are never flagged. The same check applies to enumeration values compared against
  a variable in a `FILTER(?kind = cim:…)` / `FILTER(?kind IN (…))` expression or supplied via a
  `VALUES` row, when the variable's property context is a known enumeration.

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
- `CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY` (WARN) — the shape's `sh:minCount`/`sh:maxCount`
  cannot be satisfied given the property's declared CIM `cims:multiplicity`: a `sh:minCount` above
  the schema's upper bound, or a `sh:maxCount` below its lower bound. A shape that is merely
  *stricter* than the schema (the usual profile-tightening pattern) is accepted — only genuine
  contradictions are flagged.

### Value constraints

Beyond the range checks, several value-level SHACL constraints are validated:

- **`sh:in`** — every URI member of the value list is checked. When the property's range is a known
  enumeration, a member that is not one of its values is reported as `INVALID_ENUM_VALUE`;
  otherwise a member that is unknown to every index (class, property, enumeration member) is flagged
  as a missing term. Literal members are skipped.
- **`sh:hasValue`** — when the property's range is a known enumeration, a required value that is not
  one of its members is reported as `INVALID_ENUM_VALUE`.
- **`sh:datatype`** — a value in the XSD namespace that is not a valid XSD 1.1 datatype is reported
  as `UNKNOWN_VOCABULARY_TERM` (e.g. `xsd:strng`).
- **Value ranges** — `sh:minInclusive` / `sh:minExclusive` / `sh:maxInclusive` / `sh:maxExclusive`
  are checked for a self-contradiction: a lower bound above an upper bound (and, when either bound is
  exclusive, a lower bound *equal* to an upper bound, which admits no value) → `INVALID_VALUE_RANGE`.

### Target and property-reference constraints

Predicates whose value names a CIM **property** by IRI are existence-checked, reporting
`UNKNOWN_PROPERTY` for an unknown term (the common `rdf:type` and other standard/header terms are
accepted):

- **`sh:targetSubjectsOf`** / **`sh:targetObjectsOf`** — the target property must exist.
- **`sh:equals`** / **`sh:disjoint`** / **`sh:lessThan`** / **`sh:lessThanOrEquals`** — the paired
  property must exist.
- **`sh:ignoredProperties`** — every property in the list (used with `sh:closed`) must exist.

### Deactivated shapes

A shape carrying `sh:deactivated true` is skipped entirely — none of the checks above run on it.

### Shape-structure terms

Shape-structure findings (`sh:targetClass`, `sh:class`, `sh:path` against the schema) reuse the
existence codes above. Three kinds of term are **not** checked against the CIM schema, so they are
never falsely reported as unknown: standard-vocabulary classes (e.g. `rdf:List`, `rdfs:Resource`),
terms the shapes file declares itself (any URI subject of `rdf:type`, `rdfs:subClassOf`,
`rdfs:subPropertyOf`, `rdfs:domain`, or `rdfs:range`), and the `sh:path` / `sh:class` of a custom
constraint component's parameters or validators (`sh:parameter`, `sh:nodeValidator`,
`sh:propertyValidator`) — which declare a parameter rather than reference a CIM term. A
closed-namespace *typo* in class position (e.g. `sh:Fooo`) or in a `sh:path` (e.g. `sh:path rdf:typ`)
is still reported, as `UNKNOWN_VOCABULARY_TERM`. Embedded SPARQL (`sh:sparql`, `sh:target`,
`sh:validator`, `sh:rule`) is extracted and validated with the full SPARQL check set; its findings
are mapped back to positions in the Turtle source.

## See also

- [Configuration → strictness](/cimvocabcheck/configuration#strictness) — promote warnings to
  errors for CI.
- [Known limitations](/cimvocabcheck/limitations) — what is intentionally not checked, and why.
- [API reference](/cimvocabcheck/api) — the `SparqlValidationAnnotation` record these codes live on.
