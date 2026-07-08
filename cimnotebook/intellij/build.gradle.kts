/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.cyclonedx.bom") version "3.4.1"
    id("com.diffplug.spotless") version "8.10.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// IntelliJ Platform 2024.2 (build 242) requires bytecode target 17.
// The platform plugin internally sets jvmToolchain(17); we override it in afterEvaluate
// so the build compiles with whatever JDK is installed (≥17), targeting Java 17 bytecode.
afterEvaluate {
    val localJdk =
        listOf(17, 21, 25, 26)
            .firstOrNull { v ->
                org.gradle.jvm.toolchain.JavaLanguageVersion.of(v).let { lv ->
                    try {
                        javaToolchains.launcherFor { languageVersion.set(lv) }.get()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            } ?: 17

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(
                org.gradle.jvm.toolchain.JavaLanguageVersion
                    .of(localJdk),
            )
        }
        // Compile to Java 17 bytecode regardless of the JDK that runs javac, so the
        // output runs on every supported IDE. Set at the extension level (not just on
        // the JavaCompile tasks) so it isn't overridden by the toolchain default.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Inherit JVM default methods from platform Kotlin interfaces (e.g. ToolWindowFactory)
        // instead of generating DefaultImpls-delegating bridge overrides in implementing classes.
        // Without this, the bridges override @ApiStatus.Internal default members (getAnchor,
        // getIcon, manage, ...), which the Plugin Verifier fails as INTERNAL_API_USAGES.
        compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// ---------------------------------------------------------------------------
// Spotless: Kotlin formatting + linting via ktlint, plus license-header checks.
//
//   ./gradlew spotlessCheck   # verify (wired into `check`)
//   ./gradlew spotlessApply   # auto-format + insert/update license headers
//
// ktlint enforces the official Kotlin style. licenseHeaderFile enforces the
// SOPTIM Apache-2.0 header on every Kotlin source, the Gradle build scripts,
// the plugin.xml descriptor and the module README — mirroring the Maven modules
// (mycila/Spotless). The build/ and generated server resources are excluded.
// The header text lives under config/license/.
// ---------------------------------------------------------------------------
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0")
        licenseHeaderFile(rootProject.file("config/license/header-kotlin.txt"))
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
        licenseHeaderFile(
            rootProject.file("config/license/header-kotlin.txt"),
            "(import |plugins |rootProject|dependencyResolutionManagement|pluginManagement|@)",
        )
    }
    format("pluginXml") {
        target("src/main/resources/META-INF/plugin.xml")
        licenseHeaderFile(rootProject.file("config/license/header-xml.txt"), "(<idea-plugin)")
    }
    format("readme") {
        target("README.md")
        licenseHeaderFile(rootProject.file("config/license/header-xml.txt"), "(#)")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        // LSP4IJ from JetBrains Marketplace — required for LSP client support.
        // Update lsp4ijVersion in gradle.properties to the latest available release.
        plugins("com.redhat.devtools.lsp4ij:${providers.gradleProperty("lsp4ijVersion").get()}")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// CycloneDX SBOM (org.cyclonedx.bom)
//
// Inventories the IntelliJ Platform libraries (2024.2) + LSP4IJ + any other
// library the plugin builds against. Scoped to `compileClasspath` ONLY: the
// default scans every resolvable configuration, which pulls in the
// `intellijPluginVerifierIdes` configs (multiple extra IDE versions used by
// verifyPlugin) and test runtimes — that is both noisy and non-reproducible
// across machines. (runtimeClasspath is empty: the platform is a provided,
// compile-time dependency, not shipped in the plugin zip.) compileClasspath
// resolves deterministically from the pinned platformVersion / lsp4ijVersion.
//
// Output goes to the committed ../sbom/intellij/bom.json (i.e. cimnotebook/sbom/intellij).
// scripts/generate-sbom.sh then canonicalizes it (normalises the build timestamp, the git
// remote URL form, and drops the per-file hashes of the bytecode-instrumented platform jars,
// which are not reproducible across JDK/IDE builds) so re-runs are byte-identical anywhere.
// License compliance + attribution is handled by scripts/check-sbom-licenses.py.
// ---------------------------------------------------------------------------
tasks.cyclonedxDirectBom {
    includeConfigs.set(listOf("compileClasspath"))
    schemaVersion.set(org.cyclonedx.Version.VERSION_16)
    includeBomSerialNumber.set(false)
    jsonOutput.set(layout.projectDirectory.file("../sbom/intellij/bom.json"))
    xmlOutput.unsetConvention()
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
    // buildSearchableOptions launches a headless IDE instance to index settings.
    // This triggers premature file type validation before PlainTextLanguage is registered,
    // causing a non-fatal SEVERE log that fails the task.  The plugin works correctly
    // without the index; the settings page is still fully functional.
    buildSearchableOptions = false

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // ── Plugin Verifier (./gradlew verifyPlugin) ─────────────────────────────
    // Runs the IntelliJ Plugin Verifier — the same tool the JetBrains Marketplace
    // runs on upload. Catches internal-API and scheduled-for-removal usages (which
    // previously slipped through to the Marketplace) plus binary incompatibilities
    // against the IDEs we claim to support.
    pluginVerification {
        // Verify against a curated set spanning our since-build (242) and the latest
        // releases in range, so an API removed in a newer IDE is caught here.
        ides {
            recommended()
        }
        // Fail the build on the categories the Marketplace cares about. Plain
        // DEPRECATED_API_USAGES is intentionally omitted: the only non-removal
        // 2024.2 API for the file-chooser browse button is plain-deprecated, and
        // failing on it would force a premature Kotlin-UI-DSL rewrite.
        failureLevel =
            listOf(
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            )
    }
}

// ---------------------------------------------------------------------------
// Server JAR bundling
//
// The cimvocabcheck-lsp.jar fat-JAR is built by the Maven module at
// cimvocabcheck/lsp/target/cimvocabcheck-lsp.jar.  Build that first:
//
//   mvn -f ../../cimvocabcheck/lsp/pom.xml package -DskipTests
//
// Then build the IntelliJ plugin:
//
//   ./gradlew buildPlugin
//
// The JAR is embedded inside the plugin jar as a classpath resource
// (server/cimvocabcheck-lsp.jar) and extracted to the IntelliJ system cache
// on first use.  It is intentionally excluded from git via .gitignore.
// ---------------------------------------------------------------------------
tasks {
    val copyServerJar by registering(Copy::class) {
        description = "Copies cimvocabcheck-lsp.jar from the Maven build output into resources."
        from(
            fileTree("../../cimvocabcheck/lsp/target") {
                include("cimvocabcheck-lsp-*.jar")
                exclude("*original*", "*sources*", "*javadoc*")
            },
        )
        into("src/main/resources/server")
        rename { "cimvocabcheck-lsp.jar" }
        onlyIf {
            val jars =
                fileTree("../../cimvocabcheck/lsp/target") {
                    include("cimvocabcheck-lsp-*.jar")
                    exclude("*original*", "*sources*", "*javadoc*")
                }.files
            if (jars.isEmpty()) {
                val hint =
                    "[CIMNotebook] No cimvocabcheck-lsp-*.jar found in ../../cimvocabcheck/lsp/target — " +
                        "run 'mvn -f ../../cimvocabcheck/lsp/pom.xml package -DskipTests' first."
                val packaging =
                    gradle.taskGraph.allTasks.any {
                        it.name == "buildPlugin" || it.name == "publishPlugin"
                    }
                val ci = providers.gradleProperty("ci").isPresent
                if (ci || packaging) {
                    throw GradleException(
                        "$hint The bundled language server is mandatory for a packaged or " +
                            "published plugin; failing the build instead of producing a broken " +
                            "plugin zip.",
                    )
                }
                logger.warn(hint)
            }
            jars.isNotEmpty()
        }
    }

    processResources {
        dependsOn(copyServerJar)

        // Bake the plugin version into cimnotebook-plugin.properties so the runtime can read
        // it from the classpath instead of querying the (internal) plugin-manager API.
        val pluginVersionValue = providers.gradleProperty("pluginVersion").get()
        inputs.property("pluginVersion", pluginVersionValue)
        filesMatching("cimnotebook-plugin.properties") {
            expand("pluginVersion" to pluginVersionValue)
        }
    }
}
