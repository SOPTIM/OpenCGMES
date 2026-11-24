---
title: Input and problems
sidebar_position: 3
---

# Input and problems

## What the folders may contain

**Schema folder** (`--schema`): CGMES RDFS profiles (`.rdf`). Only the profiles referenced by a
model end up in the dataset, so the folder may contain a whole profile library, also for several
CGMES versions.

**Model folder** (`--model`): the CIMXML files (`.xml`) of one IGM, one file per profile, e.g.
EQ, TP, SSH, SV plus the boundary. A file may also declare several profiles, see
[Files with several profiles](/cimxml-cli/overview#files-with-several-profiles).

For both folders:

- Subfolders are searched recursively.
- ZIP archives are read directly, also ZIP archives inside ZIP archives (up to 8 levels deep), as
  ENTSO-E ships them. Problems show the path inside the archive, e.g. `igm.zip!/inner.zip!/EQ.xml`.
- Other files are ignored.

Supported are CGMES 2.4.15 and CGMES 3.0, one version per run.

## Problems

The tool checks everything before it writes, collects all problems and reports them together, each
with a hint how to fix it. If there is a problem, nothing is written and the exit code is `1`.

| Problem | Cause and fix |
| --- | --- |
| `N CIMXML files map to the same graph` | Two files of the same profile, e.g. the EQ files of two TSOs: the folder contains more than one IGM or a CGM. Convert one IGM per run. A file that declares several profiles counts for each of them, so an EQ file that also declares SC collides with a separate SC file. |
| `is a DifferenceModel` | Only FullModels are supported. Use the complete export of the IGM. |
| `has no md:FullModel header` | The file has no header, or an NC-style header (`dcat:Dataset`). Only CGMES FullModels are supported. |
| `no RDFS profile in the schema folder matches md:Model.profile` | The RDFS of this profile is missing. Add it to the schema folder. |
| `the models use different CIM versions` | CGMES 2.4.15 and 3.0 files are mixed. Convert one version per run. |
| `second file header (FH) profile` | The schema folder contains two header profiles for the same CGMES version; the ENTSO-E library for CGMES 3.0 ships `Header-AP-Voc-RDFS2019.rdf` and `Header-AP-Voc-RDFS2020.rdf`. Remove one of them. |
| `profile with version IRI(s) ... is defined twice` | Two RDFS files with the same version IRIs but different keywords. Remove one of them. |
| `<term> has <dataType/domain/...> [...], but [...] in <file>` | Variants of one profile (same keyword and version IRIs) define a class or property differently, so at least one file is faulty. Remove the faulty file. |
| `cannot parse CIMXML` / `cannot parse RDFS` | The file is broken; the message contains the parser error. |
| `is not a valid ZIP archive or contains no entries` | The archive is broken, is no ZIP file at all, or is empty. Check it or remove it from the folder. |
| `ZIP archive nested deeper than 8 levels` | Extract the outer archives first. |

## Warnings

Warnings do not stop the conversion; the dataset is written.

| Warning | Cause and fix |
| --- | --- |
| `N RDFS files define profile EQ with the same version IRIs` | Variants of one profile, like the four EQ files (Core, Core+Operation, Core+ShortCircuit, Core+Operation+ShortCircuit) of the official CGMES 2.4.15 RDFS folder. The files are merged into one profile with all their classes and properties. Keep only one variant in the schema folder to avoid the warning. |
| `N warnings: "..."` in the progress line of a file | Parser warnings of the CIMXML file, e.g. `CIM UUID without dashes`, summarized per file. `--verbose` prints each of them. |

## What is not checked

The header dependencies (`md:Model.DependentOn`) and the completeness of the IGM are not checked; a
folder with only EQ and SSH is converted as well.
