---
title: Usage
sidebar_position: 2
---

# Usage

```bash
cimxml2jsonld -s <rdfs-folder> -m <igm-folder> -o <file.jsonld | file.jsonld.gz>
```

## Options

| Option | Description |
| --- | --- |
| `-s`, `--schema <dir>` | Folder with the CGMES RDFS files. Required. |
| `-m`, `--model <dir>` | Folder with the CIMXML files of one IGM. Required. |
| `-o`, `--output <file>` | Output file. `.jsonld` writes JSON-LD, `.jsonld.gz` gzip-compressed JSON-LD; other extensions are rejected. An existing file is replaced. Required. |
| `-c`, `--compact` | Write JSON-LD in compacted form (with `@context`) instead of expanded form. Needs considerably more memory, see [Memory](#memory). |
| `-q`, `--quiet` | Only print errors. |
| `-v`, `--verbose` | Also print every parser warning, debug output and stack traces. |
| `-h`, `--help` | Show the help. |
| `-V`, `--version` | Show the version. |

Both folders are searched recursively, and ZIP archives inside them are read directly, see
[Input and problems](/cimxml-cli/input).

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | The dataset was written. |
| `1` | The conversion failed, e.g. because of invalid input. Nothing was written. |
| `2` | Usage error: missing or invalid options. |

## Output file

The file is written to a temporary file next to it and renamed at the end, so a failed run never
leaves a half-written file behind. Both forms contain the same data:

| | Expanded (default) | Compacted (`--compact`) |
| --- | --- | --- |
| IRIs | full IRIs, no `@context` | shortened with the prefixes of the RDFS and CIMXML files |
| Size | larger uncompressed; with gzip about the same | smaller uncompressed |
| Memory and time | less | about twice as much |

In compacted form, prefixes named like an IRI scheme used in the data (such as `urn` or `cgmes`)
are left out, because they would change those IRIs when the file is read.

## Memory

The JSON-LD writer builds the whole document in memory, so the required heap grows with the size of
the IGM:

- Roughly 0.8 GB of heap per million quads, twice that with `--compact`. The quad count is shown in
  the last line of the output.
- The start scripts set a maximum heap of 4 GB. Change it with `CIMXML2JSONLD_OPTS` (or
  `JAVA_OPTS`):

  ```bash
  CIMXML2JSONLD_OPTS=-Xmx8g cimxml2jsonld -s rdfs -m igm -o igm.jsonld.gz
  ```

## Running without the start scripts

The release also contains the fat JAR on its own:

```bash
java -Xmx4g -jar cimxml-cli-<version>.jar -s rdfs -m igm -o igm.jsonld.gz
```

## Docker

The image `ghcr.io/soptim/cimxml-cli` needs no local Java:

```bash
docker run --rm -v "${PWD}:/work" ghcr.io/soptim/cimxml-cli:latest -s rdfs -m igm -o igm.jsonld.gz
```

- Mount the folder with your data to `/work`.
- On Linux, add `--user "$(id -u):$(id -g)"` so the output file belongs to you instead of root.
- The heap defaults to 75 % of the container memory; change it with `-e CIMXML2JSONLD_OPTS=-Xmx8g`.

## Building from source

```bash
mvn -pl cimxml-cli -am -DskipTests package
# cimxml-cli/target/cimxml-cli-<version>.zip  distribution with start scripts
# cimxml-cli/target/cimxml-cli-<version>.jar  fat JAR
```
