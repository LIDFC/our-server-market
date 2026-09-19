package site.vinoff.market.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import site.vinoff.market.core.ItemBlob;

/**
 * What a player has put together while building a listing.
 *
 * <p>The two halves are built in completely different ways, and that is the point.
 *
 * <p><b>What I give</b> is a list of slot numbers in the player's own inventory plus a snapshot of what was seen in
 * them. Nothing here owns an item: a player who drops or moves something after ticking it simply gets a refusal at the
 * end, because the real removal is one transaction that checks the items are still there.
 *
 * <p><b>What I want</b> is not tied to the inventory at all — you are asking for something you do not have. It is a
 * plain list of item ids and amounts, chosen from the catalogue, and it never moves anything anywhere.
 */
public final class Selection {

    private final int maxItems;
    /** slot in the player's inventory to what was seen there */
    private final Map<Integer, ItemBlob> chosen = new LinkedHashMap<>();
    /** item id (e.g. {@code diamond}) to how many of it the player is asking for */
    private final Map<String, Integer> wanted = new LinkedHashMap<>();

    public Selection(int maxItems) {
        this.maxItems = maxItems;
    }

    // what I give ---------------------------------------------------------------------------------------------------

    /** Ticks or unticks an offered stack. Returns what happened, so the window can say it out loud. */
    public Result toggleOffered(int slot, ItemBlob blob) {
        if (chosen.remove(slot) != null) {
            return Result.REMOVED;
        }
        if (chosen.size() >= maxItems) {
            return Result.TOO_MANY;
        }
        chosen.put(slot, blob);
        return Result.ADDED;
    }

    public boolean isOffered(int slot) {
        return chosen.containsKey(slot);
    }

    public List<ItemBlob> offered() {
        return new ArrayList<>(chosen.values());
    }

    public int offeredCount() {
        return chosen.size();
    }

    public Optional<ItemBlob> offeredAt(int slot) {
        return Optional.ofNullable(chosen.get(slot));
    }

    /**
     * Drops a tick whose slot no longer holds what was ticked. Called before showing the window again, so a player who
     * moved something around sees the truth rather than a stale tick.
     */
    public void forget(int slot) {
        chosen.remove(slot);
    }

    // what I want ---------------------------------------------------------------------------------------------------

    /**
     * Changes how many of an item the player is asking for. A resulting amount of zero or less drops the line
     * entirely, and anything above {@code cap} is clamped, so a held-down mouse button cannot ask for a million
     * diamonds.
     */
    public Result want(String itemId, int change, int cap) {
        int current = wanted.getOrDefault(itemId, 0);
        int next = current + change;
        if (next <= 0) {
            return wanted.remove(itemId) != null ? Result.REMOVED : Result.UNCHANGED;
        }
        if (current == 0 && wanted.size() >= maxItems) {
            return Result.TOO_MANY;
        }
        next = Math.min(next, cap);
        if (next == current) {
            return Result.UNCHANGED;
        }
        wanted.put(itemId, next);
        return current == 0 ? Result.ADDED : Result.CHANGED;
    }

    /** Drops an item from the wish list whatever its amount. */
    public Result forgetWanted(String itemId) {
        return wanted.remove(itemId) != null ? Result.REMOVED : Result.UNCHANGED;
    }

    public int wantedAmount(String itemId) {
        return wanted.getOrDefault(itemId, 0);
    }

    /** The wish list, in the order it was built: item id to amount. */
    public Map<String, Integer> wanted() {
        return new LinkedHashMap<>(wanted);
    }

    public int wantedCount() {
        return wanted.size();
    }

    // both ----------------------------------------------------------------------------------------------------------

    public boolean empty() {
        return chosen.isEmpty() && wanted.isEmpty();
    }

    public void clear() {
        chosen.clear();
        wanted.clear();
    }

    /** What a change did. */
    public enum Result {
        ADDED,
        CHANGED,
        REMOVED,
        UNCHANGED,
        TOO_MANY
    }
}
