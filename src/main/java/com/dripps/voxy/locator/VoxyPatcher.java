package com.dripps.voxy.locator;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Runtime patcher that locates or downloads the official Fabric Voxy JAR,
 * reconstructs the full NeoForge Voxy JAR using binary .delta patches and additions,
 * and caches the reconstructed JAR in .minecraft/cache/.
 */
public class VoxyPatcher {
    private final Path gameDir;
    private final Path modsDir;
    private final Path cacheDir;
    private final Path voxyCacheDir;
    private String manifestSha256 = "";

    public VoxyPatcher(Path gameDir, Path modsDir, Path cacheDir) {
        this.gameDir = gameDir;
        this.modsDir = modsDir;
        this.cacheDir = cacheDir;
        this.voxyCacheDir = gameDir.resolve("voxy_cache");
    }

    public Path preparePatchedJar() throws Exception {
        Files.createDirectories(cacheDir);
        Files.createDirectories(voxyCacheDir);

        PatchesManifest manifest = loadManifest();
        if (manifest == null) {
            return null;
        }

        Path patchedJar = cacheDir.resolve("voxy_neoforge_patched.jar");
        Path metaFile = cacheDir.resolve("voxy_neoforge_patched.jar.meta");

        if (isCacheValid(patchedJar, metaFile, manifest)) {
            VoxyLog.info("[NeoVoxy] Using valid cached patched JAR: {}", patchedJar);
            return patchedJar;
        }

        VoxyLog.info("[NeoVoxy] Cached patched JAR missing or outdated. Beginning reconstruction...");
        Path officialJar = locateOrDownloadOfficialJar(manifest);
        reconstructJar(officialJar, patchedJar, metaFile, manifest);

        VoxyLog.info("[NeoVoxy] Patched JAR reconstructed successfully: {}", patchedJar);
        return patchedJar;
    }

    public PatchesManifest loadManifest() {
        InputStream is = getResourceStream("voxy_patches/patches.json");
        if (is == null) {
            return null;
        }
        try {
            byte[] bytes = is.readAllBytes();
            this.manifestSha256 = DeltaEngine.sha256Hex(bytes);
            return PatchesManifest.fromStream(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            VoxyLog.error("[NeoVoxy] Failed to parse patches.json: {}", e.getMessage());
            return null;
        }
    }

    private boolean isCacheValid(Path patchedJar, Path metaFile, PatchesManifest manifest) {
        if (!Files.exists(patchedJar) || !Files.exists(metaFile)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(metaFile, StandardCharsets.UTF_8);
            String cachedVersion = "";
            String cachedOrigSha = "";
            String cachedManifestSha = "";
            for (String line : lines) {
                if (line.startsWith("version=")) cachedVersion = line.substring("version=".length()).trim();
                if (line.startsWith("originalSha256=")) cachedOrigSha = line.substring("originalSha256=".length()).trim();
                if (line.startsWith("manifestSha256=")) cachedManifestSha = line.substring("manifestSha256=".length()).trim();
            }
            return manifest.modVersion.equals(cachedVersion) &&
                    manifest.originalSha256.equalsIgnoreCase(cachedOrigSha) &&
                    !cachedManifestSha.isEmpty() &&
                    this.manifestSha256.equalsIgnoreCase(cachedManifestSha) &&
                    Files.size(patchedJar) > 0;
        } catch (Exception e) {
            VoxyLog.warn("[NeoVoxy] Error reading cache metadata: {}", e.getMessage());
            return false;
        }
    }

    private Path locateOrDownloadOfficialJar(PatchesManifest manifest) throws Exception {
        String expectedSha256 = manifest.originalSha256;
        VoxyLog.info("[NeoVoxy] Scanning for official Fabric Voxy JAR with SHA-256: {}", expectedSha256);

        // 1. Scan mods folder
        Path foundInMods = findJarWithSha256(modsDir, expectedSha256);
        if (foundInMods != null) {
            VoxyLog.info("[NeoVoxy] Found official Fabric Voxy in mods directory: {}", foundInMods);
            return foundInMods;
        }

        // 2. Scan voxy_cache folder
        Path foundInCache = findJarWithSha256(voxyCacheDir, expectedSha256);
        if (foundInCache != null) {
            VoxyLog.info("[NeoVoxy] Found official Fabric Voxy in voxy_cache directory: {}", foundInCache);
            return foundInCache;
        }

        // 3. Download from Modrinth
        Path downloadTarget = voxyCacheDir.resolve("voxy-fabric-official.jar");
        VoxyLog.info("[NeoVoxy] Official Fabric Voxy not found locally. Downloading from: {}", manifest.modrinthUrl);
        downloadFileWithRedirects(manifest.modrinthUrl, downloadTarget, expectedSha256);

        return downloadTarget;
    }

    private Path findJarWithSha256(Path dir, String expectedSha256) {
        if (!Files.isDirectory(dir)) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    try {
                        String hash = DeltaEngine.sha256File(path);
                        if (hash.equalsIgnoreCase(expectedSha256)) {
                            return path;
                        }
                    } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void downloadFileWithRedirects(String initialUrl, Path destination, String expectedSha256) throws Exception {
        Path tempFile = destination.resolveSibling(destination.getFileName().toString() + ".download.tmp");
        Files.deleteIfExists(tempFile);

        String currentUrl = initialUrl;
        int redirects = 0;
        HttpURLConnection conn;

        while (true) {
            URL url = URI.create(currentUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "NeoVoxy-Runtime-Patcher/0.6.1 (Minecraft NeoForge)");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308) {
                redirects++;
                if (redirects > 10) {
                    throw new IOException("Too many HTTP redirects when downloading: " + initialUrl);
                }
                String location = conn.getHeaderField("Location");
                if (location == null) {
                    throw new IOException("Redirect status " + status + " but no Location header found");
                }
                currentUrl = URI.create(currentUrl).resolve(location).toString();
                conn.disconnect();
                continue;
            }

            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Failed to download official JAR! HTTP Status: " + status + " (" + conn.getResponseMessage() + ")");
            }
            break;
        }

        long totalSize = conn.getContentLengthLong();
        VoxyLog.info("[NeoVoxy] Downloading {} ({} bytes)...", currentUrl, totalSize);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = conn.getInputStream();
             OutputStream os = Files.newOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int r;
            long downloaded = 0;
            while ((r = is.read(buf)) != -1) {
                os.write(buf, 0, r);
                md.update(buf, 0, r);
                downloaded += r;
            }
        } finally {
            conn.disconnect();
        }

        String downloadedSha256 = DeltaEngine.bytesToHex(md.digest());
        if (!downloadedSha256.equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(tempFile);
            throw new IllegalStateException("Downloaded JAR SHA-256 checksum mismatch! Expected: " +
                    expectedSha256 + " but got: " + downloadedSha256);
        }

        Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
        VoxyLog.info("[NeoVoxy] Download verified and saved to: {}", destination);
    }

    private void reconstructJar(Path officialJar, Path outputJar, Path metaFile, PatchesManifest manifest) throws Exception {
        Path tempOutput = outputJar.resolveSibling(outputJar.getFileName().toString() + ".reconstruct.tmp");
        Files.deleteIfExists(tempOutput);

        Set<String> strippedSet = new HashSet<>(manifest.strippedFiles);
        Set<String> processedEntries = new HashSet<>();

        VoxyLog.info("[NeoVoxy] Reconstructing NeoForge Voxy JAR: {} patches, {} additions, {} stripped files",
                manifest.patches.size(), manifest.additions.size(), manifest.strippedFiles.size());

        try (ZipFile officialZip = new ZipFile(officialJar.toFile());
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tempOutput)))) {

            // Step 1: Process official JAR entries
            Enumeration<? extends ZipEntry> entries = officialZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (entry.isDirectory()) {
                    continue;
                }

                if (strippedSet.contains(name)) {
                    // Skip stripped Fabric files
                    continue;
                }

                if (manifest.patches.containsKey(name)) {
                    // Apply delta patch
                    PatchesManifest.PatchInfo patchInfo = manifest.patches.get(name);
                    byte[] origBytes;
                    try (InputStream is = officialZip.getInputStream(entry)) {
                        origBytes = DeltaEngine.readFully(is, (int) entry.getSize());
                    }

                    String deltaRes = "voxy_patches/" + patchInfo.deltaPath;
                    InputStream deltaStream = getResourceStream(deltaRes);
                    if (deltaStream == null) {
                        deltaStream = getResourceStream(patchInfo.deltaPath);
                    }
                    if (deltaStream == null) {
                        throw new FileNotFoundException("Missing required delta resource: " + deltaRes);
                    }

                    byte[] patchedBytes = DeltaEngine.applyDelta(origBytes, deltaStream);
                    String actualSha = DeltaEngine.sha256Hex(patchedBytes);
                    if (!actualSha.equalsIgnoreCase(patchInfo.targetSha256)) {
                        throw new IllegalStateException("Reconstructed checksum mismatch for " + name +
                                ": expected " + patchInfo.targetSha256 + " but got " + actualSha);
                    }

                    ZipEntry newEntry = new ZipEntry(name);
                    newEntry.setTime(entry.getTime());
                    zos.putNextEntry(newEntry);
                    zos.write(patchedBytes);
                    zos.closeEntry();
                    processedEntries.add(name);
                } else {
                    // Copy unchanged entry directly
                    ZipEntry newEntry = new ZipEntry(name);
                    newEntry.setTime(entry.getTime());
                    zos.putNextEntry(newEntry);
                    if (name.endsWith(".class")) {
                        byte[] classBytes;
                        try (InputStream is = officialZip.getInputStream(entry)) {
                            classBytes = DeltaEngine.readFully(is, (int) entry.getSize());
                        }
                        if (classBytes.length >= 8 && classBytes[0] == (byte) 0xCA && classBytes[1] == (byte) 0xFE &&
                                classBytes[2] == (byte) 0xBA && classBytes[3] == (byte) 0xBE) {
                            int major = ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);
                            if (major > 65) {
                                // Align class file version to Java 21 for 1.21.1 JVM compatibility
                                classBytes[6] = 0;
                                classBytes[7] = 65;
                            }
                        }
                        zos.write(classBytes);
                    } else {
                        try (InputStream is = officialZip.getInputStream(entry)) {
                            copyStream(is, zos);
                        }
                    }
                    zos.closeEntry();
                    processedEntries.add(name);
                }
            }

            // Step 2: Inject all new files from additions (preferring voxy_additions.pack)
            InputStream packStream = getResourceStream("voxy_patches/voxy_additions.pack");
            if (packStream == null) {
                packStream = getResourceStream("voxy_additions.pack");
            }

            if (packStream != null) {
                try (ZipInputStream zis = new ZipInputStream(packStream)) {
                    ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        String name = ze.getName();
                        if (ze.isDirectory() || processedEntries.contains(name)) {
                            continue;
                        }
                        ZipEntry newEntry = new ZipEntry(name);
                        newEntry.setTime(ze.getTime());
                        zos.putNextEntry(newEntry);
                        copyStream(zis, zos);
                        zos.closeEntry();
                        processedEntries.add(name);
                    }
                }
            } else {
                for (String additionPath : manifest.additions) {
                    if (processedEntries.contains(additionPath)) {
                        continue;
                    }

                    String additionRes = "voxy_additions/" + additionPath;
                    try (InputStream addStream = getResourceStream(additionRes)) {
                        if (addStream == null) {
                            throw new FileNotFoundException("Missing required addition resource: " + additionRes);
                        }
                        ZipEntry newEntry = new ZipEntry(additionPath);
                        zos.putNextEntry(newEntry);
                        copyStream(addStream, zos);
                        zos.closeEntry();
                        processedEntries.add(additionPath);
                    }
                }
            }
        }

        // Atomically replace destination jar
        Files.move(tempOutput, outputJar, StandardCopyOption.REPLACE_EXISTING);

        // Write meta file
        String metaContent = "version=" + manifest.modVersion + "\n" +
                "originalSha256=" + manifest.originalSha256 + "\n" +
                "manifestSha256=" + this.manifestSha256 + "\n" +
                "created=" + System.currentTimeMillis() + "\n";
        Files.writeString(metaFile, metaContent, StandardCharsets.UTF_8);
    }

    private InputStream getResourceStream(String resourcePath) {
        if (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }

        // 1. Class / ClassLoader getResourceAsStream
        InputStream is = getClass().getResourceAsStream("/" + resourcePath);
        if (is != null) return is;
        is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is != null) return is;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            is = cl.getResourceAsStream(resourcePath);
            if (is != null) return is;
        }

        // 2. CodeSource direct JAR read
        try {
            var cs = getClass().getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path jarPath = Paths.get(cs.getLocation().toURI());
                if (Files.isRegularFile(jarPath)) {
                    ZipFile zf = new ZipFile(jarPath.toFile());
                    ZipEntry ze = zf.getEntry(resourcePath);
                    if (ze != null) {
                        byte[] bytes = DeltaEngine.readFully(zf.getInputStream(ze), (int) ze.getSize());
                        zf.close();
                        return new ByteArrayInputStream(bytes);
                    }
                    zf.close();
                }
            }
        } catch (Exception ignored) {}

        // 3. Scan mods directory for any JAR containing this resource
        if (modsDir != null && Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path jar : ds) {
                    try (ZipFile zf = new ZipFile(jar.toFile())) {
                        ZipEntry ze = zf.getEntry(resourcePath);
                        if (ze != null) {
                            byte[] bytes = DeltaEngine.readFully(zf.getInputStream(ze), (int) ze.getSize());
                            return new ByteArrayInputStream(bytes);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int r;
        while ((r = in.read(buffer)) != -1) {
            out.write(buffer, 0, r);
        }
    }
}
