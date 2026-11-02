package com.dripps.voxy.locator;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * High-performance, zero-external-dependency binary delta patch engine.
 * Encodes XOR byte differences between original and modified class/resource files,
 * compressed using standard GZIP (level 9), with target length and SHA-256 integrity verification.
 */
public class DeltaEngine {
    public static final byte[] MAGIC = new byte[]{'V', 'X', 'P', '1'};

    public static byte[] createDelta(byte[] originalBytes, byte[] targetBytes) throws IOException {
        byte[] targetHash = sha256(targetBytes);
        int targetLen = targetBytes.length;
        int origLen = (originalBytes != null) ? originalBytes.length : 0;

        byte[] diff = new byte[targetLen];
        for (int i = 0; i < targetLen; i++) {
            byte orig = (i < origLen) ? originalBytes[i] : 0;
            diff[i] = (byte) (targetBytes[i] ^ orig);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.write(MAGIC);
        dos.writeInt(targetLen);
        dos.write(targetHash);
        dos.flush();

        GZIPOutputStream gzos = new GZIPOutputStream(baos) {
            {
                def.setLevel(Deflater.BEST_COMPRESSION);
            }
        };
        gzos.write(diff);
        gzos.finish();
        gzos.flush();

        return baos.toByteArray();
    }

    public static byte[] applyDelta(byte[] originalBytes, byte[] deltaBytes) throws IOException {
        return applyDelta(originalBytes, new ByteArrayInputStream(deltaBytes));
    }

    public static byte[] applyDelta(byte[] originalBytes, InputStream deltaStream) throws IOException {
        DataInputStream dis = (deltaStream instanceof DataInputStream) ?
                (DataInputStream) deltaStream : new DataInputStream(deltaStream);

        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Invalid delta patch magic header: " + Arrays.toString(magic));
        }

        int targetLen = dis.readInt();
        byte[] expectedHash = new byte[32];
        dis.readFully(expectedHash);

        GZIPInputStream gzis = new GZIPInputStream(dis);
        byte[] diff = readFully(gzis, targetLen);

        int origLen = (originalBytes != null) ? originalBytes.length : 0;
        byte[] restored = new byte[targetLen];
        for (int i = 0; i < targetLen; i++) {
            byte orig = (i < origLen) ? originalBytes[i] : 0;
            restored[i] = (byte) (diff[i] ^ orig);
        }

        byte[] actualHash = sha256(restored);
        if (!Arrays.equals(expectedHash, actualHash)) {
            throw new IllegalStateException("Delta patch verification failed! Expected SHA-256 " +
                    bytesToHex(expectedHash) + " but got " + bytesToHex(actualHash));
        }

        return restored;
    }

    public static byte[] readFully(InputStream is, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = is.read(data, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Premature EOF reading stream: expected " + length + " bytes, read " + offset);
            }
            offset += count;
        }
        return data;
    }

    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return bytesToHex(sha256(data));
    }

    public static String sha256File(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public static String sha512File(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 algorithm not available", e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
