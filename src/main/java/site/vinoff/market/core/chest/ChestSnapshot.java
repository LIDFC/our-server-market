package site.vinoff.market.core.chest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import site.vinoff.market.core.Digest;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;

/**
 * What a bound chest held at one moment, and the fingerprint of it.
 *
 * <p>This is the whole reason the chest can be trusted without the owner being online. The marketplace cannot force a
 * chunk to disk the way it forces a player file, so instead it records what the chest looked like before it touched
 * it. Any later read — at startup, when the chunk loads, before the next request — compares the two and knows whether
 * the world kept the change or rolled back to before it.
 *
 * <p>Every method here is pure, which is the point: the rules that decide whether an item may leave a chest are
 * tested without a server anywhere in sight.
 */
public record ChestSnapshot(int size, List<ChestSlot> filled) {

    public ChestSnapshot {
        if (size <= 0) {
            throw new IllegalArgumentException("a chest has slots, size was " + size);
        }
        List<ChestSlot> sorted = new ArrayList<>(filled == null ? List.of() : filled);
        sorted.sort(Comparator.comparingInt(ChestSlot::slot));
        int previous = -1;
        for (ChestSlot slot : sorted) {
            if (slot.slot() >= size) {
                throw new IllegalArgumentException("slot " + slot.slot() + " is outside a chest of " + size);
            }
            if (slot.slot() == previous) {
                throw new IllegalArgumentException("slot " + slot.slot() + " appears twice");
            }
            previous = slot.slot();
        }
        filled = List.copyOf(sorted);
    }

    public static ChestSnapshot empty(int size) {
        return new ChestSnapshot(size, List.of());
    }

    public Optional<ChestSlot> at(int slot) {
        return filled.stream().filter(entry -> entry.slot() == slot).findFirst();
    }

    /**
     * The fingerprint: every slot in order, whether or not anything is in it.
     *
     * <p>Empty slots are written out explicitly rather than skipped, so moving a stack from slot 3 to slot 4 changes
     * the fingerprint. A hash over only the occupied slots would call those two chests identical, and the whole point
     * is to notice that somebody rearranged the box.
     */
    public String digest() {
        StringBuilder text = new StringBuilder();
        Map<Integer, ChestSlot> bySlot = new LinkedHashMap<>();
        for (ChestSlot slot : filled) {
            bySlot.put(slot.slot(), slot);
        }
        for (int slot = 0; slot < size; slot++) {
            ChestSlot entry = bySlot.get(slot);
            text.append(slot).append(':');
            if (entry == null) {
                text.append('-');
            } else {
                text.append(entry.sha256()).append('x').append(entry.amount());
            }
            text.append(';');
        }
        return Digest.sha256(text.toString());
    }

    /**
     * Checks that a plan still matches this chest. Throws rather than returning false: every caller treats a mismatch
     * as a refusal, and a boolean here would eventually be ignored somewhere.
     */
    public void verify(ChestPlan plan) {
        for (ChestPlan.Take take : plan.takes()) {
            if (take.slot() >= size) {
                throw new MarketException(MarketError.CHEST_CHANGED, "Slot " + take.slot() + " is outside this chest");
            }
            ChestSlot entry = at(take.slot()).orElseThrow(
                    () -> new MarketException(MarketError.CHEST_CHANGED, "Slot " + take.slot() + " is empty now"));
            if (!entry.sha256().equals(take.sha256())) {
                throw new MarketException(MarketError.CHEST_CHANGED, "Slot " + take.slot() + " holds something else now");
            }
            if (entry.amount() < take.amount()) {
                throw new MarketException(
                        MarketError.CHEST_CHANGED,
                        "Slot " + take.slot() + " holds " + entry.amount() + ", not " + take.amount());
            }
        }
    }

    /**
     * What the chest will look like once the plan has run. Used to record the expected fingerprint before anything is
     * touched, so recovery can tell "the removal happened" from "the removal was rolled back".
     */
    public ChestSnapshot apply(ChestPlan plan) {
        verify(plan);
        Map<Integer, ChestSlot> bySlot = new LinkedHashMap<>();
        for (ChestSlot slot : filled) {
            bySlot.put(slot.slot(), slot);
        }
        for (ChestPlan.Take take : plan.takes()) {
            ChestSlot entry = bySlot.get(take.slot());
            int left = entry.amount() - take.amount();
            if (left == 0) {
                bySlot.remove(take.slot());
            } else {
                // the hash is of the item, not of the pile, so taking some of a stack leaves the same item behind
                bySlot.put(take.slot(), entry.withAmount(left));
            }
        }
        return new ChestSnapshot(size, List.copyOf(bySlot.values()));
    }

    public boolean isEmpty() {
        return filled.isEmpty();
    }

    public int totalItems() {
        return filled.stream().mapToInt(ChestSlot::amount).sum();
    }
}
