---
title: CIM Notebooks
sidebar_position: 3
---

# CIM Notebooks

CIM Notebooks are CIMNotebook's own notebook documents for VS Code: markdown files whose
```` ```sparql ```` and ```` ```shacl ```` code blocks open as notebook cells. Because the
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

| Format | Opens as notebook | Notes |
| --- | --- | --- |
| `*.cimnb.md` | by default | The native format. A regular markdown file — the suffix just tells VS Code to open it as a notebook. |
| any `*.md` / `*.markdown` | via **Open With… → CIM Notebook (Markdown)** | Turn any markdown document (a README, a runbook) into a notebook without renaming it. |
| `*.sparqlbook` | via **Open With… → CIM Notebook (SPARQL Book)** | Zazuko SPARQL Notebook interop (JSON). |

To use **Open With…**: right-click the file in the Explorer → *Open With…* → pick the CIM
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

Cell execution (running SPARQL queries and SHACL validation against endpoints or local
files) ships in an upcoming CIMNotebook release; today CIM Notebooks are an authoring and
validation surface. To *run* `.sparqlbook` notebooks you can meanwhile keep using the Zazuko
SPARQL Notebook extension side by side — CIMNotebook validates its cells too.
