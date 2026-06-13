/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimcheck.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provides the CGMES 3.0 RDFS profiles that ship bundled inside the jar, so validation works
 * with zero configuration.
 *
 * <p>The profile files live on the classpath under {@code cgmes/3.0/} (listed by
 * {@code cgmes/3.0/manifest.txt}). Because the rest of the loader pipeline navigates to schema
 * declarations by real file {@link Path} (go-to-definition, workspace symbols), the bundled
 * resources are <em>extracted</em> once into the user's cache directory rather than parsed
 * straight from the jar. Extraction is idempotent: a {@code .version} marker lets repeated runs
 * skip the copy when the cache is already up to date.</p>
 *
 * @see CgmesSchemaLoader#bundledDefault()
 */
public final class BundledSchemas {

    private static final Logger LOG = LoggerFactory.getLogger(BundledSchemas.class);

    /** Classpath directory holding the bundled profiles and their manifest. */
    private static final String RESOURCE_DIR = "cgmes/3.0";
    private static final String MANIFEST     = RESOURCE_DIR + "/manifest.txt";

    /**
     * Cache layout version. Bump this whenever the bundled profiles change so existing caches are
     * re-extracted instead of serving stale files.
     */
    private static final String VERSION = "cgmes-3.0";

    private static final Object EXTRACT_LOCK = new Object();

    private static volatile Path cachedDir;

    private BundledSchemas() {}

    /**
     * Extracts the bundled CGMES 3.0 profiles into the user cache directory (once) and returns the
     * directory containing them. Subsequent calls return the same directory without re-copying.
     *
     * @throws IOException if the resources cannot be read or written to the cache directory
     */
    public static Path extractedDir() throws IOException {
        Path dir = cachedDir;
        if (dir != null) return dir;
        synchronized (EXTRACT_LOCK) {
            if (cachedDir != null) return cachedDir;
            cachedDir = extract();
            return cachedDir;
        }
    }

    /** Returns the bundled-profile filenames listed in the manifest. */
    public static List<String> profileFileNames() {
        return readManifest();
    }

    // ---- Extraction ------------------------------------------------------------------------

    private static Path extract() throws IOException {
        List<String> files = readManifest();
        Path dir = cacheDir();
        Files.createDirectories(dir);

        if (isUpToDate(dir, files)) {
            LOG.debug("Bundled CGMES 3.0 schemas already present in {}", dir);
            return dir;
        }

        for (String name : files) {
            String resource = "/" + RESOURCE_DIR + "/" + name;
            try (InputStream in = BundledSchemas.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("Bundled schema resource missing from classpath: " + resource);
                }
                Path target = dir.resolve(name);
                Path tmp    = dir.resolve(name + ".tmp");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        Files.writeString(dir.resolve(".version"), VERSION, StandardCharsets.UTF_8);
        LOG.info("Extracted {} bundled CGMES 3.0 schema file(s) to {}", files.size(), dir);
        return dir;
    }

    /** True when the cache holds the current version marker and every manifest file. */
    private static boolean isUpToDate(Path dir, List<String> files) {
        try {
            Path marker = dir.resolve(".version");
            if (!Files.isRegularFile(marker)
                    || !VERSION.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) {
                return false;
            }
            for (String name : files) {
                if (!Files.isRegularFile(dir.resolve(name))) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> readManifest() {
        try (InputStream in = BundledSchemas.class.getResourceAsStream("/" + MANIFEST)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("Bundled schema manifest missing: " + MANIFEST));
            }
            var names = new ArrayList<String>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) names.add(trimmed);
            }
            if (names.isEmpty()) {
                throw new UncheckedIOException(new IOException("Bundled schema manifest is empty: " + MANIFEST));
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Cache directory resolution --------------------------------------------------------

    /** Resolves the OS-appropriate per-user cache directory for the extracted schemas. */
    private static Path cacheDir() {
        return baseCacheDir().resolve("cimcheck").resolve("schemas").resolve(VERSION);
    }

    private static Path baseCacheDir() {
        String os   = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) return Path.of(localAppData);
            return Path.of(home, "AppData", "Local");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Caches");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg);
        return Path.of(home, ".cache");
    }
}
