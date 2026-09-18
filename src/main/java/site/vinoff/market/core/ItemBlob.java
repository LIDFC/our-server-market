package site.vinoff.market.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * One or more Minecraft items as the marketplace stores them: the exact bytes Paper produced for the stack, plus a
 * count and a short human readable summary for chat, the website and the logs.
 *
 * <p>The core of the marketplace never looks inside {@code data}. Only the Bukkit layer turns it back into real
 * ItemStacks, which is what keeps every rule in this package testable without a running server.
 */
public final class ItemBlob {

    public static final int MAX_SUMMARY_LENGTH = 120;

    private final byte[] data;
    private final int count;
    private final String summary;

    public ItemBlob(byte[] data, int count, String summary) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new IllegalArgumentException("item data must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("item count must be positive, was " + count);
        }
        this.data = data.clone();
        this.count = count;
        this.summary = shorten(summary);
    }

    private static String shorten(String summary) {
        String value = summary == null || summary.isBlank() ? "unknown item" : summary.trim();
        return value.length() <= MAX_SUMMARY_LENGTH ? value : value.substring(0, MAX_SUMMARY_LENGTH);
    }

    /** The serialized items. A copy: nothing outside can change what is stored. */
    public byte[] data() {
        return data.clone();
    }

    public int count() {
        return count;
    }

    public String summary() {
        return summary;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItemBlob blob)) {
            return false;
        }
        return count == blob.count && summary.equals(blob.summary) && Arrays.equals(data, blob.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(data), count, summary);
    }

    @Override
    public String toString() {
        return "ItemBlob[" + count + "x " + summary + ", " + data.length + " bytes]";
    }
}
