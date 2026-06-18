---
title: Performance
sidebar_position: 9
---

# Performance

CIMXML is built for large power-system models. It streams parsing rather than buffering whole
documents, uses memory-efficient indexed graphs, and applies difference models as deltas instead of
materializing copies. This page covers the choices that matter when you process big files.

## Memory optimization

The library uses specialized Jena graph implementations tuned for CIM data:

| Mechanism                          | What it does                                                        |
| ---------------------------------- | ------------------------------------------------------------------ |
| `GraphMem2Roaring`                 | Roaring-bitmap-based indexing for compact, fast in-memory graphs   |
| `IndexingStrategy.LAZY_PARALLEL`   | Defers index construction and builds indexes in parallel after parsing |
| `FastDeltaGraph`                   | Applies difference models as a delta over the base, without materialization |

Parsed graphs are created with `GraphMem2Roaring` using `LAZY_PARALLEL` indexing, so triples are
ingested quickly during parsing and the per-graph indexes are initialized in parallel afterwards —
only for the graphs that need them. The small header/structure graphs use lighter indexing, which
avoids paying for indexes you will not query.

## Difference application without copies

Applying a difference model with `differenceModelToFullModel(...)` returns a `FastDeltaGraph` layered
over the predecessor body. Additions and removals are held in their own `GraphMem2Roaring` delta
graphs rather than rewriting the base, so applying a difference to a large model stays cheap in both
time and memory. See [Difference models](/cimxml/difference-models).

## Large file handling

When you parse from a `Path`, CIMXML reads through a `BufferedFileChannelInputStream` with a buffer
sized to the file — capped at a maximum so very large files do not allocate an oversized buffer:

```java
// Parsing from a Path uses a buffered file channel internally
Path largeCimFile = Path.of("large_model.xml");
CimDatasetGraph dataset = parser.parseCimModel(largeCimFile);
```

For smaller inputs the buffer matches the file size; beyond the internal maximum it is clamped to a
fixed size. You do not configure this — it is chosen automatically by the `Path` overload.

:::tip Prefer the Path overload for files
Passing a `Path` lets the library pick an optimal buffered channel and size it for you. Use the
`InputStream` / `Reader` overloads for in-memory or streamed sources where you already control
buffering.
:::

:::note Reuse the parser
A single `CimXmlParser` is thread-safe for parsing and holds the profile registry, so register your
profiles once and reuse the parser across many model files rather than recreating it per file.
:::
