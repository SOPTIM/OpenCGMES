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

package de.soptim.opencgmes.cimxmlcli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Test data: the hand-written CGMES 2.4.15 mini IGM and helpers to vary it. */
public final class TestData {

  /** Names of the RDFS files of the mini IGM. */
  public static final String[] MINI_RDFS = {
    "EquipmentBoundaryProfile.rdf",
    "EquipmentProfile.rdf",
    "FileHeader.rdf",
    "StateVariablesProfile.rdf",
    "SteadyStateHypothesisProfile.rdf",
    "TopologyProfile.rdf"
  };

  /** Names of the CIMXML files of the mini IGM. */
  public static final String[] MINI_MODELS = {
    "MINI_EQ.xml", "MINI_EQ_BD.xml", "MINI_SSH.xml", "MINI_SV.xml", "MINI_TP.xml"
  };

  private TestData() {}

  /** Copies the RDFS files of the mini IGM into {@code folder}. */
  public static Path copyMiniRdfs(Path folder) {
    for (var name : MINI_RDFS) {
      copy("/igm-2415/rdfs/" + name, folder.resolve(name));
    }
    return folder;
  }

  /** Copies the CIMXML files of the mini IGM into {@code folder}. */
  public static Path copyMiniModels(Path folder) {
    for (var name : MINI_MODELS) {
      copy("/igm-2415/model/" + name, folder.resolve(name));
    }
    return folder;
  }

  /** Returns the content of a test resource. */
  public static String read(String resource) {
    try (var in = TestData.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException("Missing test resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Copies a test resource to {@code target}, creating parent folders. */
  public static void copy(String resource, Path target) {
    write(target, read(resource));
  }

  /** Writes {@code content} to {@code target}, creating parent folders. */
  public static void write(Path target, String content) {
    write(target, content.getBytes(StandardCharsets.UTF_8));
  }

  /** Writes {@code content} to {@code target}, creating parent folders. */
  public static void write(Path target, byte[] content) {
    try {
      Files.createDirectories(target.toAbsolutePath().getParent());
      Files.write(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns the bytes of a ZIP archive with the given entries (name to content). */
  public static byte[] zip(Map<String, byte[]> entries) {
    try {
      final var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes)) {
        for (var entry : entries.entrySet()) {
          zip.putNextEntry(new ZipEntry(entry.getKey()));
          zip.write(entry.getValue());
          zip.closeEntry();
        }
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns a map builder for {@link #zip(Map)}. */
  public static Map<String, byte[]> entries(Object... nameContentPairs) {
    final var entries = new LinkedHashMap<String, byte[]>();
    for (int i = 0; i < nameContentPairs.length; i += 2) {
      final var content = nameContentPairs[i + 1];
      entries.put(
          (String) nameContentPairs[i],
          content instanceof byte[] b ? b : content.toString().getBytes(StandardCharsets.UTF_8));
    }
    return entries;
  }
}
