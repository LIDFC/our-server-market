package site.vinoff.market.core.chest;

/**
 * One occupied slot of a bound chest.
 *
 * <p>{@code sha256} is the hash of the item's one-count bytes — the very same value the items table stores — so it
 * says <em>what</em> the item is and says nothing about how many there are. That is what lets a player sell 16 out of
 * a stack of 64 without the fingerprint of the item changing.
 */
public record ChestSlot(int slot, String sha256, int amount, String summary) {

    public ChestSlot {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must not be negative, was " + slot);
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("a slot with an item needs its hash");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        summary = summary == null ? "" : summary;
    }

    public ChestSlot withAmount(int newAmount) {
        return new ChestSlot(slot, sha256, newAmount, summary);
    }
}
