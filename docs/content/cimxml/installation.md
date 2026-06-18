---
title: Installation
sidebar_position: 2
---

# Installation

CIMXML is published under the `de.soptim.opencgmes` group as the `cimxml` artifact. Add it to your
build with Maven or Gradle, ensure you are on **Java 21+**, and you are ready to parse models — the
library pulls in **Apache Jena 5.5.0** (the `jena-arq` module) transitively.

## Maven

```xml
<dependency>
    <groupId>de.soptim.opencgmes</groupId>
    <artifactId>cimxml</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Gradle

```kotlin
implementation("de.soptim.opencgmes:cimxml:1.0.0")
```

## Requirements

| Requirement      | Version            |
| ---------------- | ------------------ |
| Java             | 21 or newer        |
| Apache Jena      | 5.5.0 (`jena-arq`) |
| Build tool       | Maven 3.9+ / Gradle |

Beyond Jena, the library depends on the Woodstox and Aalto StAX processors, Apache Commons IO, and
Apache Commons Lang3 — all resolved transitively.

## Releases and snapshots

CIMXML uses GitHub Actions for CI/CD, and the artifact is distributed in two channels:

| Channel             | What you get                              | When it is published                         |
| ------------------- | ----------------------------------------- | -------------------------------------------- |
| **Maven Central**   | Signed release artifacts (`X.Y.Z`)        | On pushing a release tag `cimxml-vX.Y.Z`     |
| **GitHub Packages** | `-SNAPSHOT` builds and release artifacts  | On every push to `main` (snapshots) and on release |

The `cimxml/pom.xml` on `main` stays at `X.Y.Z-SNAPSHOT`; the release version is supplied by the
release tag and applied by CI for deployment only.

:::note Consuming snapshots
To pull a `-SNAPSHOT` build, add the GitHub Packages repository for the OpenCGMES project to your
build's repository list and authenticate with a GitHub token. Stick to Maven Central release
versions for reproducible builds.
:::

Once the dependency resolves, head to the [Quick start](/cimxml/quick-start) to parse your first
model, or see [Library usage](/cimxml/library-usage) for using CIMXML inside your own project's
tests.
