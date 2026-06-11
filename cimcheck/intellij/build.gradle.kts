/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group   = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// IntelliJ Platform 2024.2 (build 242) requires bytecode target 17.
// The platform plugin internally sets jvmToolchain(17); we override it in afterEvaluate
// so the build compiles with whatever JDK is installed (≥17), targeting Java 17 bytecode.
afterEvaluate {
    val localJdk = listOf(17, 21, 25, 26)
        .firstOrNull { v ->
            org.gradle.jvm.toolchain.JavaLanguageVersion.of(v).let { lv ->
                try {
                    javaToolchains.launcherFor { languageVersion.set(lv) }.get()
                    true
                } catch (_: Exception) { false }
            }
        } ?: 17

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(localJdk)) }
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
}

intellijPlatform {
    pluginConfiguration {
        name    = providers.gradleProperty("pluginName")
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
}

// ---------------------------------------------------------------------------
// Server JAR bundling
//
// The cimcheck-lsp.jar fat-JAR is built by the Maven module at
// cimcheck/lsp/target/cimcheck-lsp.jar.  Build that first:
//
//   mvn -f ../lsp/pom.xml package -DskipTests
//
// Then build the IntelliJ plugin:
//
//   ./gradlew buildPlugin
//
// The JAR is embedded inside the plugin jar as a classpath resource
// (server/cimcheck-lsp.jar) and extracted to the IntelliJ system cache
// on first use.  It is intentionally excluded from git via .gitignore.
// ---------------------------------------------------------------------------
tasks {
    val copyServerJar by registering(Copy::class) {
        description = "Copies cimcheck-lsp.jar from the Maven build output into resources."
        from("../lsp/target/cimcheck-lsp.jar")
        into("src/main/resources/server")
        onlyIf {
            val src = file("../lsp/target/cimcheck-lsp.jar")
            if (!src.exists()) {
                logger.warn(
                    "[CIMcheck] ${src.absolutePath} not found — " +
                    "run 'mvn -f ../lsp/pom.xml package -DskipTests' first."
                )
            }
            src.exists()
        }
    }

    processResources {
        dependsOn(copyServerJar)
    }
}
