package com.dripps.voxy.locator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Manifest representation for patches.json containing checksums,
 * download URLs, patch lists, stripped files, and additions.
 */
public class PatchesManifest {
    public static class PatchInfo {
        public String deltaPath;
        public String targetSha256;
        public int targetSize;

        public PatchInfo() {}

        public PatchInfo(String deltaPath, String targetSha256, int targetSize) {
            this.deltaPath = deltaPath;
            this.targetSha256 = targetSha256;
            this.targetSize = targetSize;
        }
    }

    public String modVersion = "";
    public String originalSha256 = "";
    public String originalSha512 = "";
    public String modrinthUrl = "";
    public List<String> strippedFiles = new ArrayList<>();
    public Map<String, PatchInfo> patches = new LinkedHashMap<>();
    public List<String> additions = new ArrayList<>();

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"modVersion\": \"").append(escapeJson(modVersion)).append("\",\n");
        sb.append("  \"originalSha256\": \"").append(escapeJson(originalSha256)).append("\",\n");
        sb.append("  \"originalSha512\": \"").append(escapeJson(originalSha512)).append("\",\n");
        sb.append("  \"modrinthUrl\": \"").append(escapeJson(modrinthUrl)).append("\",\n");

        sb.append("  \"strippedFiles\": [\n");
        for (int i = 0; i < strippedFiles.size(); i++) {
            sb.append("    \"").append(escapeJson(strippedFiles.get(i))).append("\"");
            if (i < strippedFiles.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"patches\": {\n");
        int patchIdx = 0;
        int totalPatches = patches.size();
        for (Map.Entry<String, PatchInfo> entry : patches.entrySet()) {
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {\n");
            sb.append("      \"deltaPath\": \"").append(escapeJson(entry.getValue().deltaPath)).append("\",\n");
            sb.append("      \"targetSha256\": \"").append(escapeJson(entry.getValue().targetSha256)).append("\",\n");
            sb.append("      \"targetSize\": ").append(entry.getValue().targetSize).append("\n");
            sb.append("    }");
            patchIdx++;
            if (patchIdx < totalPatches) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");

        sb.append("  \"additions\": [\n");
        for (int i = 0; i < additions.size(); i++) {
            sb.append("    \"").append(escapeJson(additions.get(i))).append("\"");
            if (i < additions.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    public static PatchesManifest fromJson(String json) {
        PatchesManifest manifest = new PatchesManifest();
        manifest.modVersion = extractString(json, "modVersion");
        manifest.originalSha256 = extractString(json, "originalSha256");
        manifest.originalSha512 = extractString(json, "originalSha512");
        manifest.modrinthUrl = extractString(json, "modrinthUrl");
        manifest.strippedFiles = extractStringList(json, "strippedFiles");
        manifest.additions = extractStringList(json, "additions");
        manifest.patches = extractPatchesMap(json);
        return manifest;
    }

    public static PatchesManifest fromStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        String json = baos.toString(StandardCharsets.UTF_8);
        return fromJson(json);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return "";
    }

    private static List<String> extractStringList(String json, String key) {
        List<String> list = new ArrayList<>();
        String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            String arrayContent = m.group(1);
            java.util.regex.Matcher itemMatcher = java.util.regex.Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"").matcher(arrayContent);
            while (itemMatcher.find()) {
                list.add(itemMatcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
        }
        return list;
    }

    private static Map<String, PatchInfo> extractPatchesMap(String json) {
        Map<String, PatchInfo> map = new LinkedHashMap<>();
        int patchesStart = json.indexOf("\"patches\"");
        if (patchesStart == -1) return map;
        int braceStart = json.indexOf('{', patchesStart);
        if (braceStart == -1) return map;

        // Parse key-value entries in patches object
        int cur = braceStart + 1;
        while (cur < json.length()) {
            // Find next key
            int keyQuoteStart = json.indexOf('"', cur);
            if (keyQuoteStart == -1) break;
            int keyQuoteEnd = json.indexOf('"', keyQuoteStart + 1);
            if (keyQuoteEnd == -1) break;

            String key = json.substring(keyQuoteStart + 1, keyQuoteEnd).replace("\\\"", "\"").replace("\\\\", "\\");
            int objStart = json.indexOf('{', keyQuoteEnd);
            if (objStart == -1) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd == -1) break;

            String objContent = json.substring(objStart, objEnd + 1);
            PatchInfo info = new PatchInfo();
            info.deltaPath = extractString(objContent, "deltaPath");
            info.targetSha256 = extractString(objContent, "targetSha256");

            String sizePattern = "\"targetSize\"\\s*:\\s*(\\d+)";
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile(sizePattern).matcher(objContent);
            if (sm.find()) {
                info.targetSize = Integer.parseInt(sm.group(1));
            }

            map.put(key, info);
            cur = objEnd + 1;

            // Check if reached end of patches map
            int nextChar = -1;
            for (int k = cur; k < json.length(); k++) {
                char c = json.charAt(k);
                if (!Character.isWhitespace(c) && c != ',') {
                    nextChar = c;
                    break;
                }
            }
            if (nextChar == '}') {
                break;
            }
        }
        return map;
    }
}
