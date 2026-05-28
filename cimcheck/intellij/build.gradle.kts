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
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group   = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    // IntelliJ Platform 2024.1 (build 241) requires bytecode target 17.
    // The LSP server JAR it launches is separate and uses Java 21.
    jvmToolchain(17)
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
