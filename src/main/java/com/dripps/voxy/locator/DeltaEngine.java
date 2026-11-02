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
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * High-performance, cryptographically masked binary delta patch engine (VXP2).
 * Eliminates the bsdiff/xdelta "extra block" vulnerability and prevents constant-pool index
 * shift leaks by masking all target bytes using a cyclic original byte stream XORed with an
 * AES-256-CTR keystream derived strictly from the official unmodified upstream JAR's SHA-256 hash.
 *
 * Mathematical properties:
 * - Every byte in the delta is dependent on the official upstream JAR's byte stream and hash.
 * - Zero unmasked plaintext tail bytes when targetLen > origLen (cyclic wrapping + keystream).
 * - Zero recognizable strings, method signatures, or constant-pool entries in the decompressed diff.
 * - Hardware AES-NI acceleration via standard Java SE (javax.crypto), zero external dependencies.
 */
public class DeltaEngine {
    public static final byte[] MAGIC_V1 = new byte[]{'V', 'X', 'P', '1'};
    public static final byte[] MAGIC = new byte[]{'V', 'X', 'P', '2'};

    public static byte[] generateKeystream(byte[] originalBytes, int length) {
        if (originalBytes == null || originalBytes.length == 0 || length <= 0) {
            return new byte[length];
        }
        try {
            byte[] key = sha256(originalBytes);
            byte[] iv = new byte[16];
            System.arraycopy(key, 0, iv, 0, 16);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            return cipher.update(new byte[length]);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate cryptographic keystream", e);
        }
    }

    public static byte[] createDelta(byte[] originalBytes, byte[] targetBytes) throws IOException {
        byte[] targetHash = sha256(targetBytes);
        int targetLen = targetBytes.length;
        int origLen = (originalBytes != null) ? originalBytes.length : 0;
        byte[] keystream = generateKeystream(originalBytes, targetLen);

        byte[] diff = new byte[targetLen];
        for (int i = 0; i < targetLen; i++) {
            byte orig = (origLen > 0) ? originalBytes[i % origLen] : 0;
            byte mask = (byte) (orig ^ keystream[i]);
            diff[i] = (byte) (targetBytes[i] ^ mask);
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
        boolean isV2 = Arrays.equals(magic, MAGIC);
        if (!isV2 && !Arrays.equals(magic, MAGIC_V1)) {
            throw new IOException("Invalid delta patch magic header: " + Arrays.toString(magic));
        }

        int targetLen = dis.readInt();
        byte[] expectedHash = new byte[32];
        dis.readFully(expectedHash);

        GZIPInputStream gzis = new GZIPInputStream(dis);
        byte[] diff = readFully(gzis, targetLen);

        int origLen = (originalBytes != null) ? originalBytes.length : 0;
        byte[] keystream = isV2 ? generateKeystream(originalBytes, targetLen) : null;

        byte[] restored = new byte[targetLen];
        for (int i = 0; i < targetLen; i++) {
            if (isV2) {
                byte orig = (origLen > 0) ? originalBytes[i % origLen] : 0;
                byte mask = (byte) (orig ^ keystream[i]);
                restored[i] = (byte) (diff[i] ^ mask);
            } else {
                byte orig = (i < origLen) ? originalBytes[i] : 0;
                restored[i] = (byte) (diff[i] ^ orig);
            }
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
