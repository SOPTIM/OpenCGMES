---
title: Building
sidebar_position: 2
---

# Building

This page walks through building every OpenCGMES artifact from a fresh checkout: the Maven modules (CIMXML, CIMVocabCheck core/cli/lsp), the CIMVocabCheck LSP fat JAR, the VS Code VSIX, and the IntelliJ plugin zip. See the [Repository Overview](/developer-guide/overview) for how the modules relate.

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| **JDK** | 21 (Temurin recommended) | all Maven modules, the LSP, and the IntelliJ plugin's bundled server |
| **Maven** | 3.9+ | CIMXML and CIMVocabCheck (core/cli/lsp) |
| **Node.js** | 20 | the VS Code extension |
| **IntelliJ IDEA** | 2024.2+ (build 242) | running/testing the IntelliJ plugin |
| **Gradle** | use the bundled wrapper (`./gradlew`, Gradle 9.5.0) | building the IntelliJ plugin |

:::note Why Java 21 and IntelliJ 2024.2
The CIMVocabCheck core, CLI, and LSP compile against Java 21. The IntelliJ plugin itself compiles to Java 17 bytecode, but it launches the bundled language server on the IDE's own runtime, and IntelliJ ships a Java 21 runtime only from **2024.2 (build 242)** onward — hence the `pluginSinceBuild=242` floor. The plugin uses the bundled Gradle wrapper, so you do not need a system Gradle install.
:::

## 1. Clone and initialise the submodule

The CGMES example schemas and the integration tests use the **ENTSO-E Application Profiles** library as a Git submodule (mounted at `cimvocabcheck/core/testing/entsoe/application-profiles-library`). Initialise it before building if you want the examples and integration tests to run:

```bash
git clone https://github.com/SOPTIM/OpenCGMES.git
cd OpenCGMES
git submodule update --init
```

:::tip The submodule is optional for a plain build
A normal `mvn install` builds and unit-tests fine without the submodule — the integration tests that need it are skipped automatically (see [Testing](/developer-guide/testing)). Initialise it only if you want to run the CGMES examples or the full integration suite.
:::

## 2. Build the Maven modules (aggregator)

The root `pom.xml` is an aggregator reactor. From the repository root:

```bash
mvn test                                # build & test every Maven module
mvn install                             # build, test, and install every module into ~/.m2
mvn -pl cimvocabcheck/core -am verify   # build cimvocabcheck-core + its dependencies (cimxml) only
```

`-pl <module> -am` ("also make") builds the named module plus everything it depends on, in dependency order — handy when you only care about one part of the tree. Each module also builds standalone against its own pom:

```bash
mvn -f cimxml/pom.xml verify
mvn -f cimvocabcheck/core/pom.xml verify
```

## 3. Build the CIMVocabCheck LSP fat JAR

The language server is a self-contained ("fat") JAR. Build it on its own with `-am` so its dependencies (`cimvocabcheck-core` → `cimxml`) are built first:

```bash
mvn -pl cimvocabcheck/lsp -am -DskipTests clean package
# Output: cimvocabcheck/lsp/target/cimvocabcheck-lsp-<version>.jar
```

The CLI fat JAR is built the same way:

```bash
mvn -pl cimvocabcheck/cli -am -DskipTests clean package
# Output: cimvocabcheck/cli/target/cimvocabcheck-cli-<version>.jar
```

:::warning Build the LSP JAR *before* the editor plugins
Both editor integrations **bundle** `cimvocabcheck-lsp.jar` so end users don't need a Java toolchain to build it. If you package the VSIX or the IntelliJ plugin before the LSP JAR exists (or after changing LSP code without rebuilding it), the plugin ships a stale or missing server. Always run the `mvn ... package` step above first, then copy/bundle the freshly built JAR.
:::

## 4. Build the VS Code extension (VSIX)

From `cimnotebook/vscode`, after the LSP JAR has been built:

```bash
cd cimnotebook/vscode
npm install
npm run copy-jar    # copies cimvocabcheck/lsp/target/cimvocabcheck-lsp.jar into server/
npm run bundle      # type-checks TypeScript and bundles with esbuild
npx vsce package    # produces cimnotebook-<version>.vsix
```

`npm run copy-jar` is the bundling step — it copies the built `cimvocabcheck-lsp.jar` into `cimnotebook/vscode/server/`, and the JAR is then packed inside the VSIX.

## 5. Build the IntelliJ plugin

From `cimnotebook/intellij`, using the Gradle wrapper, again after the LSP JAR is built:

```bash
# 1. Build the language server fat JAR (from cimnotebook/intellij)
mvn -f ../../cimvocabcheck/lsp/pom.xml package -DskipTests

# 2. Build the plugin (copies the JAR in and zips it)
./gradlew buildPlugin
# Output: cimnotebook/intellij/build/distributions/cimnotebook-intellij-<version>.zip

# 3. (Optional) run the IntelliJ Plugin Verifier
./gradlew verifyPlugin
```

The Gradle build resolves the IntelliJ Platform (2024.2) and LSP4IJ from their `platformVersion` / `lsp4ijVersion` properties in `gradle.properties`, copies the bundled language server JAR in, and produces the distributable zip.

## How CI builds everything

The CI workflows mirror these steps exactly: they set versions from Git state with the [versioning scripts](/developer-guide/ci-and-releases#versioning), build the LSP JAR with `mvn -pl cimvocabcheck/lsp -am -DskipTests clean package`, then bundle it into the VSIX (`npm run bundle` + `vsce package`) and the IntelliJ plugin (`gradle buildPlugin`). See [CI & releases](/developer-guide/ci-and-releases) for the full picture.
