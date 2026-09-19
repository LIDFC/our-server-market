package site.vinoff.market.core.model;

import java.util.UUID;

/**
 * Who holds an item right now, as written in the movement ledger. Encoded as text so the ledger stays readable during
 * an incident: PLAYER:&lt;uuid&gt;, CHEST:&lt;id&gt;, LISTING:&lt;id&gt;, TRADE:&lt;id&gt;, PENDING:&lt;id&gt;, CONSUMED.
 */
public record Holder(String value) {

    public static final Holder CONSUMED = new Holder("CONSUMED");

    public static Holder player(UUID uuid) {
        return new Holder("PLAYER:" + uuid);
    }

    /**
     * A bound chest, kept apart from its owner on purpose: "did this item come out of the world or out of a player
     * file?" is the first question asked when something looks wrong, and this makes it one query.
     */
    public static Holder chest(long chestId) {
        return new Holder("CHEST:" + chestId);
    }

    public static Holder listing(long listingId) {
        return new Holder("LISTING:" + listingId);
    }

    public static Holder trade(long tradeId) {
        return new Holder("TRADE:" + tradeId);
    }

    public static Holder pending(long deliveryId) {
        return new Holder("PENDING:" + deliveryId);
    }

    @Override
    public String toString() {
        return value;
    }
}
