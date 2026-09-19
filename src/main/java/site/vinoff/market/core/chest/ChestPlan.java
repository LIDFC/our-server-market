package site.vinoff.market.core.chest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;

/**
 * Exactly what is to be taken out of a chest: which slot, which item, how many.
 *
 * <p>An item is never identified by "find me some diamonds somewhere". It is a slot number plus the hash of what is
 * supposed to be in it, so a chest whose contents shifted under the request is refused rather than guessed at. The
 * same plan is replayed during recovery, which is why it has to be written down rather than recomputed.
 *
 * <p>Stored as text — {@code slot:sha256:amount;…} — for the same reason the movement ledger stores holders as text:
 * during an incident somebody reads this column with their eyes. It needs no escaping, since none of the three parts
 * can contain a separator.
 */
public record ChestPlan(List<Take> takes) {

    public record Take(int slot, String sha256, int amount) {
        public Take {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative, was " + slot);
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("a take needs the item's sha256");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive, was " + amount);
            }
        }
    }

    public ChestPlan {
        if (takes == null || takes.isEmpty()) {
            throw new MarketException(MarketError.EMPTY_LISTING, "Nothing was chosen from the chest");
        }
        Map<Integer, Take> bySlot = new LinkedHashMap<>();
        for (Take take : takes) {
            if (bySlot.put(take.slot(), take) != null) {
                // two lines for one slot would make "how many are left" depend on the order they were applied
                throw new MarketException(MarketError.INVALID_REQUEST, "Slot " + take.slot() + " is listed twice");
            }
        }
        takes = List.copyOf(takes);
    }

    public int totalStacks() {
        return takes.size();
    }

    public String encode() {
        StringBuilder text = new StringBuilder();
        for (Take take : takes) {
            if (!text.isEmpty()) {
                text.append(';');
            }
            text.append(take.slot()).append(':').append(take.sha256()).append(':').append(take.amount());
        }
        return text.toString();
    }

    public static ChestPlan decode(String text) {
        if (text == null || text.isBlank()) {
            throw new MarketException(MarketError.STORAGE_FAILURE, "A chest plan was stored empty");
        }
        List<Take> takes = new ArrayList<>();
        for (String part : text.split(";")) {
            String[] fields = part.split(":");
            if (fields.length != 3) {
                throw new MarketException(MarketError.STORAGE_FAILURE, "A chest plan is malformed: " + part);
            }
            try {
                takes.add(new Take(Integer.parseInt(fields[0]), fields[1], Integer.parseInt(fields[2])));
            } catch (IllegalArgumentException broken) {
                throw new MarketException(MarketError.STORAGE_FAILURE, "A chest plan is malformed: " + part, broken);
            }
        }
        return new ChestPlan(takes);
    }
}
