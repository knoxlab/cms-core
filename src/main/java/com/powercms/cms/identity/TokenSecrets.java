package com.powercms.cms.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * API Token plaintext is {@code pcm_<publicId>_<secret>}; only the SHA-256 hex is stored.
 */
public final class TokenSecrets {

    public static final String PREFIX = "pcm";

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String publicId, String plaintext, String sha256Hex) {}

    private TokenSecrets() {}

    public static Issued issue() {
        String publicId = UUID.randomUUID().toString().replace("-", "");
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String plaintext = PREFIX + "_" + publicId + "_" + HexFormat.of().formatHex(secret);
        return new Issued(publicId, plaintext, sha256Hex(plaintext));
    }

    public static Optional<String> publicIdOf(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return Optional.empty();
        String[] parts = plaintext.trim().split("_", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0]) || parts[1].length() != 32 || parts[2].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parts[1]);
    }

    public static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean matches(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null) return false;
        byte[] left = sha256Hex(plaintext).getBytes(StandardCharsets.UTF_8);
        byte[] right = storedHash.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
