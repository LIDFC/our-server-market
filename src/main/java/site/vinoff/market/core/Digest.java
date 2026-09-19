package site.vinoff.market.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one hash the marketplace uses.
 *
 * <p>It lives here rather than in storage because two very different things must agree on it: the {@code sha256}
 * column of the items table, and the fingerprint of a chest slot. If they were hashed by different code they would
 * drift apart, and the website would be comparing one vocabulary against another.
 */
public final class Digest {

    private Digest() {}

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is missing", impossible);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
