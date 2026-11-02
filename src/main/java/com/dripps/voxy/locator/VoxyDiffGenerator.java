package com.dripps.voxy.locator;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Build-time diff generator comparing official Fabric Voxy with compiled NeoForge Voxy.
 * Generates .delta binary patches for modified files, exports addition files, and builds patches.json.
 */
public class VoxyDiffGenerator {
    private static final String DEFAULT_MODRINTH_URL =
            "https://cdn.modrinth.com/data/fxxUqruK/versions/iIrM8ZKQ/voxy-0.2.20-beta.jar";

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: VoxyDiffGenerator <originalJar> <modifiedJar> <outputResourceDir> [modVersion] [referenceSourceDir]");
            System.exit(1);
        }

        Path originalJar = Paths.get(args[0]).toAbsolutePath().normalize();
        Path modifiedJar = Paths.get(args[1]).toAbsolutePath().normalize();
        Path outputResourceDir = Paths.get(args[2]).toAbsolutePath().normalize();
        String modVersion = (args.length >= 4) ? args[3] : "0.6.1";
        Path referenceSourceDir = (args.length >= 5) ? Paths.get(args[4]).toAbsolutePath().normalize() : null;

        System.out.println("==================================================");
        System.out.println("   NeoVoxy Build-Time Class Diff Generator        ");
        System.out.println("==================================================");
        System.out.println("Original Fabric JAR: " + originalJar);
        System.out.println("Modified NeoForge JAR: " + modifiedJar);
        System.out.println("Output Resource Dir:  " + outputResourceDir);
        System.out.println("Mod Version:          " + modVersion);
        if (referenceSourceDir != null) {
            System.out.println("Reference Source Dir: " + referenceSourceDir);
        }

        if (!Files.exists(originalJar)) {
            throw new FileNotFoundException("Original JAR not found at: " + originalJar);
        }
        if (!Files.exists(modifiedJar)) {
            throw new FileNotFoundException("Modified JAR not found at: " + modifiedJar);
        }

        Path patchesDir = outputResourceDir.resolve("voxy_patches");
        Path additionsDir = outputResourceDir.resolve("voxy_additions");

        // Clean output dirs if they exist
        cleanDirectory(patchesDir);
        cleanDirectory(additionsDir);
        Files.createDirectories(patchesDir);
        Files.createDirectories(additionsDir);

        // Load modified sources filter to only diff what actually changed in the port
        Set<String> modifiedSources = loadModifiedSources(referenceSourceDir);

        // Compute original JAR checksums
        String origSha256 = DeltaEngine.sha256File(originalJar);
        String origSha512 = DeltaEngine.sha512File(originalJar);
        System.out.println("Original JAR SHA-256: " + origSha256);
        System.out.println("Original JAR SHA-512: " + origSha512);

        // Fetch official Modrinth URL
        String modrinthUrl = fetchModrinthUrl(origSha512);
        System.out.println("Modrinth Download URL: " + modrinthUrl);

        // Read all entries from both JARs
        Map<String, byte[]> origEntries = readJarEntries(originalJar);
        Map<String, byte[]> modEntries = readJarEntries(modifiedJar);

        System.out.println("Original JAR entry count: " + origEntries.size());
        System.out.println("Modified JAR entry count: " + modEntries.size());

        PatchesManifest manifest = new PatchesManifest();
        manifest.modVersion = modVersion;
        manifest.originalSha256 = origSha256;
        manifest.originalSha512 = origSha512;
        manifest.modrinthUrl = modrinthUrl;

        int unchangedCount = 0;
        int modifiedCount = 0;
        int additionsCount = 0;
        long totalDeltaBytes = 0;

        for (Map.Entry<String, byte[]> entry : modEntries.entrySet()) {
            String path = entry.getKey();
            byte[] modBytes = entry.getValue();

            // Skip the runtime locator/loader classes themselves and loader config from additions
            if (path.startsWith("com/dripps/voxy/locator/") ||
                    path.equals("META-INF/neoforge.loader.mods.toml") ||
                    path.equals("META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator")) {
                continue;
            }

            if (origEntries.containsKey(path)) {
                byte[] origBytes = origEntries.get(path);

                boolean isModified = false;
                if (!Arrays.equals(origBytes, modBytes)) {
                    if (path.endsWith(".class")) {
                        // Check if the source code of this class actually changed in the NeoForge port
                        String sourceName = path.split("\\$")[0];
                        if (sourceName.endsWith(".class")) {
                            sourceName = sourceName.substring(0, sourceName.length() - 6);
                        }
                        sourceName = sourceName + ".java";
                        if (modifiedSources.contains(sourceName)) {
                            isModified = true;
                        }
                    } else {
                        // Resource file modification
                        isModified = true;
                    }
                }

                if (!isModified) {
                    // Unchanged in source code or identical in bytes -> Exclude from distributable jar
                    unchangedCount++;
                } else {
                    // Actually modified in port -> Generate binary delta patch
                    modifiedCount++;
                    byte[] delta = DeltaEngine.createDelta(origBytes, modBytes);
                    totalDeltaBytes += delta.length;

                    // Verify self-consistency of patch immediately
                    byte[] testRecon = DeltaEngine.applyDelta(origBytes, delta);
                    if (!Arrays.equals(testRecon, modBytes)) {
                        throw new IllegalStateException("Immediate patch verification failed for: " + path);
                    }

                    Path deltaFile = patchesDir.resolve(path + ".delta");
                    Files.createDirectories(deltaFile.getParent());
                    Files.write(deltaFile, delta);

                    PatchesManifest.PatchInfo info = new PatchesManifest.PatchInfo();
                    info.deltaPath = path + ".delta";
                    info.targetSha256 = DeltaEngine.sha256Hex(modBytes);
                    info.targetSize = modBytes.length;
                    manifest.patches.put(path, info);
                }
            } else {
                // Step 2.3: New file (100% ours) -> Write to additions
                additionsCount++;
                Path additionFile = additionsDir.resolve(path);
                Files.createDirectories(additionFile.getParent());
                Files.write(additionFile, modBytes);
                manifest.additions.add(path);
            }
        }

        // Identify stripped files (files present in original Fabric JAR but not in modified JAR)
        for (String origPath : origEntries.keySet()) {
            if (!modEntries.containsKey(origPath)) {
                manifest.strippedFiles.add(origPath);
            }
        }

        // Strict verification: Verify zero raw original files ended up in additions
        for (String addition : manifest.additions) {
            if (origEntries.containsKey(addition)) {
                throw new IllegalStateException("Verification failed: Original file present in additions: " + addition);
            }
        }

        // Write patches.json
        Path manifestPath = patchesDir.resolve("patches.json");
        Files.writeString(manifestPath, manifest.toJson(), StandardCharsets.UTF_8);

        // Package additions into voxy_additions.pack inside patchesDir to avoid JPMS invalid package errors
        Path packFile = patchesDir.resolve("voxy_additions.pack");
        System.out.println("Packaging additions into: " + packFile);
        try (java.util.zip.ZipOutputStream packZos = new java.util.zip.ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(packFile)))) {
            for (String additionPath : manifest.additions) {
                Path srcFile = additionsDir.resolve(additionPath);
                if (Files.isRegularFile(srcFile)) {
                    java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(additionPath);
                    packZos.putNextEntry(ze);
                    Files.copy(srcFile, packZos);
                    packZos.closeEntry();
                }
            }
        }

        System.out.println("--------------------------------------------------");
        System.out.println("Diff Generation Complete:");
        System.out.println("  Unchanged Files (Excluded): " + unchangedCount);
        System.out.println("  Modified Files (Patched):   " + modifiedCount + " (" + totalDeltaBytes + " bytes in .delta)");
        System.out.println("  Additions (New 100% Mine):  " + additionsCount);
        System.out.println("  Stripped Files:             " + manifest.strippedFiles.size());
        System.out.println("  Manifest written to:        " + manifestPath);
        System.out.println("==================================================");
    }

    private static Set<String> loadModifiedSources(Path referenceSourceDir) {
        Set<String> set = new HashSet<>();

        // 1. If referenceSourceDir exists, compute diff dynamically
        if (referenceSourceDir != null && Files.isDirectory(referenceSourceDir)) {
            try {
                Path localSrc = Paths.get("src/main/java").toAbsolutePath().normalize();
                if (Files.isDirectory(localSrc)) {
                    try (var stream = Files.walk(localSrc)) {
                        stream.filter(p -> p.toString().endsWith(".java")).forEach(localFile -> {
                            String rel = localSrc.relativize(localFile).toString().replace('\\', '/');
                            Path refFile = referenceSourceDir.resolve(rel);
                            if (Files.exists(refFile)) {
                                try {
                                    String localContent = Files.readString(localFile).replace("\r\n", "\n").trim();
                                    String refContent = Files.readString(refFile).replace("\r\n", "\n").trim();
                                    if (!localContent.equals(refContent)) {
                                        set.add(rel);
                                    }
                                } catch (IOException e) {
                                    set.add(rel);
                                }
                            }
                        });
                    }
                    if (!set.isEmpty()) {
                        System.out.println("Dynamically detected " + set.size() + " modified source files from reference sources.");
                        return set;
                    }
                }
            } catch (Exception e) {
                System.out.println("Warning: Could not dynamically diff reference sources: " + e.getMessage());
            }
        }

        // 2. Fall back to embedded/resource voxy_modified_sources.txt
        try (InputStream is = VoxyDiffGenerator.class.getResourceAsStream("/voxy_modified_sources.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            set.add(line);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. Fall back to file src/main/resources/voxy_modified_sources.txt
        if (set.isEmpty()) {
            Path listFile = Paths.get("src/main/resources/voxy_modified_sources.txt");
            if (Files.exists(listFile)) {
                try {
                    for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            set.add(line);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }

        System.out.println("Loaded " + set.size() + " modified source files filter.");
        return set;
    }

    private static Map<String, byte[]> readJarEntries(Path jarPath) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        map.put(entry.getName(), DeltaEngine.readFully(is, (int) entry.getSize()));
                    }
                }
            }
        }
        return map;
    }

    private static String fetchModrinthUrl(String sha512) {
        try {
            String apiUrl = "https://api.modrinth.com/v2/version_file/" + sha512 + "?algorithm=sha512";
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "NeoVoxy-Build-Diff-Generator/1.0");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                    String response = baos.toString(StandardCharsets.UTF_8);
                    int filesIdx = response.indexOf("\"files\"");
                    if (filesIdx != -1) {
                        int urlIdx = response.indexOf("\"url\":", filesIdx);
                        if (urlIdx != -1) {
                            int start = response.indexOf('"', urlIdx + 6);
                            int end = response.indexOf('"', start + 1);
                            if (start != -1 && end != -1) {
                                return response.substring(start + 1, end);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Warning: Could not fetch URL from Modrinth API (" + e.getMessage() + "), using fallback.");
        }
        return DEFAULT_MODRINTH_URL;
    }

    private static void cleanDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {}
                    });
        }
    }
}
