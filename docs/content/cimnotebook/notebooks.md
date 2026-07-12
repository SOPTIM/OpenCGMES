---
title: CIM Notebooks
sidebar_position: 3
---

# CIM Notebooks

CIM Notebooks are CIMNotebook's own notebook documents for VS Code: markdown files whose
` ```sparql ` and ` ```shacl ` code blocks open as notebook cells. Because the
on-disk format **is** plain markdown, notebooks diff and merge cleanly in git, render on any
git forge, and need no special tooling to read.

Every code cell is validated by CIMLangServer exactly like a standalone `.rq` or `.shacl`
file — diagnostics, hover, completion, and go-to-definition all work inside cells, resolved
against your configured CGMES schema (see
[SPARQL Notebooks](/cimnotebook/sparql-notebooks) for how a cell picks its schema with the
`# [endpoint=...]` directive).

:::note VS Code only
CIM Notebooks are a VS Code feature. The [IntelliJ plugin](/cimnotebook/intellij) validates
`.rq`, `.sparql`, `.ttl`, and `.shacl` files but has no notebook support.
:::

:::info Attribution
The notebook feature is inspired by the
[SPARQL Notebook](https://marketplace.visualstudio.com/items?itemName=Zazuko.sparql-notebook)
extension by Zazuko (MIT-licensed) — CIM Notebooks are an independent implementation that
reads and writes Zazuko's `.sparqlbook` format for interoperability.
:::

## File formats

| Format                    | Opens as notebook                               | Notes                                                                                                |
| ------------------------- | ----------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `*.cimnb.md`              | by default                                      | The native format. A regular markdown file — the suffix just tells VS Code to open it as a notebook. |
| any `*.md` / `*.markdown` | via **Open With… → CIM Notebook (Markdown)**    | Turn any markdown document (a README, a runbook) into a notebook without renaming it.                |
| `*.sparqlbook`            | via **Open With… → CIM Notebook (SPARQL Book)** | Zazuko SPARQL Notebook interop (JSON).                                                               |

To use **Open With…**: right-click the file in the Explorer → _Open With…_ → pick the CIM
Notebook editor. VS Code remembers the choice per file type if you set it as default.

## The markdown format

Only top-level, unindented three-backtick fences labelled `sparql` or `shacl` become code
cells. Everything else — prose, headings, tables, code blocks in other languages (` ```turtle `,
` ```json `, …) — stays markdown:

````markdown
# Switch survey

Count switches per substation:

```sparql
SELECT ?substation (COUNT(?switch) AS ?n)
WHERE { ?switch cim:Equipment.EquipmentContainer ?substation }
GROUP BY ?substation
```

Shapes for the same data:

```shacl
ex:SwitchShape a sh:NodeShape ;
  sh:targetClass cim:Switch .
```
````

Saving a notebook normalizes the file deterministically: cells are separated by exactly one
blank line, trailing blank lines inside cells are dropped, and the file ends with a single
newline. Line endings (LF/CRLF) are preserved. Cell **outputs are never written to disk** —
notebook files stay pure source.

## Converting between formats

The command **CIMNotebook: Convert Notebook (Markdown ⇔ SPARQL Book)** writes the active
notebook in the other format as a sibling file (`report.sparqlbook` → `report.cimnb.md` and
back) and opens it. The source file is never modified. Zazuko per-cell metadata survives a
`.sparqlbook` round trip but is dropped when converting to markdown.

## Running cells

SPARQL and SHACL cells have a **Run** button (the _CIM Notebook_ kernel). Execution
happens inside CIMLangServer — the same Java process that validates your queries — using
Apache Jena's SPARQL 1.1 and SHACL engines, so there is nothing extra to install.

A cell names its target with the same `# [endpoint=...]` directive used for validation —
one directive drives both _validate against_ and _run against_. The target can be a SPARQL
endpoint or local data files:

```sparql
# [endpoint=http://localhost:3030/cgmes/query]
SELECT ?class (COUNT(?s) AS ?n)
WHERE { ?s a ?class }
GROUP BY ?class ORDER BY DESC(?n)
```

```sparql
# [endpoint=./model.xml]
SELECT ?name WHERE { ?s cim:IdentifiedObject.name ?name }
```

What you get per query kind:

| Query                               | Output                                                                                                                    |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `SELECT`                            | Result table (first 50 rows rendered; the full result travels alongside as `application/sparql-results+json`) |
| `ASK`                               | ✅ **true** / ❌ **false**                                                                                                |
| `CONSTRUCT` / `DESCRIBE`            | The result graph as Turtle                                                                                                |
| `INSERT` / `DELETE` / other updates | A confirmation with the update endpoint used                                                                              |
| SHACL cell                          | A conforms / does-not-conform verdict with severity counts, plus the full validation report as Turtle                     |

Each output ends with a small stats line — row count, duration, and the resolved endpoint.
Results are capped server-side (10 000 rows/triples by default) and the output says so when
the cap was hit. The raw payload of a result is one click away: the output's **⋯ → Change
Presentation** menu switches between the rendered view and the plain-text payload (the
SPARQL Results JSON, or the Turtle of a graph/report).

**Updates** run against the endpoint's update service: for Fuseki-style URLs the client
derives it from the query URL (`…/query` or `…/sparql` → `…/update`); any other URL is
assumed to accept updates directly.

**Cancel and timeout.** The notebook Stop button cancels the running request (the server
stops waiting and aborts the query where the endpoint supports it). Requests time out
after 30 seconds by default.

### Querying local files — including CIMXML models

When a directive is not an `http(s)://` URL it is taken as a file path, relative to the
notebook's own directory. The file is parsed in-process and queried directly — no triple
store, no import step:

- **CIMXML models** (`*.xml`): parsed by OpenCGMES's IEC 61970-552 parser. The model body
  and its `md:FullModel` header are both queryable.
- **RDF files**: anything Jena reads by extension — `.ttl`, `.rdf`, `.owl`, `.nt`, `.nq`,
  `.trig`, ….

Several directives in one cell query the **union** of the files: named graphs stay
addressable with `GRAPH`, and a bare `?s ?p ?o` sees everything.

```sparql
# [endpoint=./model.xml]
# [endpoint=./boundary.ttl]
SELECT (COUNT(*) AS ?triples) WHERE { ?s ?p ?o }
```

Parsed files are cached in the language server and re-parsed automatically when the file
changes on disk, so _edit model → re-run cell_ just works. Local files are **read-only**:
a SPARQL Update against a file target is rejected — updates need an HTTP endpoint.

Note the dual meaning of the directive: for _validation_, a `.ttl`/`.rdf`/`.owl` file is
loaded as the schema, while instance-data files (`.xml` models, `.nt`/`.nq`/`.trig` dumps)
keep the workspace schema so diagnostics stay meaningful — and either way the file is what
the cell _runs_ against.

### Running SHACL cells

A SHACL cell's text is the **shapes graph**; running the cell validates the target data
against those shapes. The same `# [endpoint=...]` directives pick the data:

- **Local files** — the shapes are checked in-process (Apache Jena SHACL) against the
  union of the referenced files, CIMXML models included:

  ```shacl
  # [endpoint=./model.xml]
  ex:SwitchNameShape a sh:NodeShape ;
    sh:targetClass cim:Switch ;
    sh:property [ sh:path cim:IdentifiedObject.name ; sh:minCount 1 ] .
  ```

- **HTTP endpoints** — the shapes are POSTed as Turtle to the endpoint's SHACL service
  (Fuseki's `shacl` operation and compatibles), which validates its own data. For
  Fuseki-style URLs the service is derived from the query URL (`…/query` → `…/shacl`);
  because Fuseki requires a graph selector, `?graph=default` (the default graph) is added
  automatically — put an explicit `?graph=…` in the directive to validate a named graph.

The output is a ✅ conforms / ❌ does-not-conform banner with counts per severity
(violations, warnings, infos), followed by the full `sh:ValidationReport` as Turtle. A
run that finds violations is still a _successful_ run — non-conformance is the result,
not an error.

**Current limitations:**

- No authentication support yet — HTTP endpoints must be reachable without credentials.
