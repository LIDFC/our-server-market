package site.vinoff.market.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import site.vinoff.market.core.ItemBlob;

/**
 * What a player has ticked while building a listing.
 *
 * <p>Nothing here owns an item. A selection is a list of slot numbers in the player's own inventory plus a snapshot of
 * what was in them, so a player who drops or moves an item after ticking it simply gets a refusal at the end: the real
 * removal is one transaction that checks the items are still there.
 */
public final class Selection {

    private final int maxItems;
    /** slot in the player's inventory to what was seen there */
    private final Map<Integer, ItemBlob> chosen = new LinkedHashMap<>();
    private final Map<Integer, ItemBlob> wanted = new LinkedHashMap<>();

    public Selection(int maxItems) {
        this.maxItems = maxItems;
    }

    /** Ticks or unticks an offered stack. Returns what happened, so the window can say it out loud. */
    public Result toggleOffered(int slot, ItemBlob blob) {
        if (wanted.containsKey(slot)) {
            return Result.ALREADY_WANTED;
        }
        if (chosen.remove(slot) != null) {
            return Result.REMOVED;
        }
        if (chosen.size() >= maxItems) {
            return Result.TOO_MANY;
        }
        chosen.put(slot, blob);
        return Result.ADDED;
    }

    /** Ticks or unticks a stack as "this is what I want in return". The item stays with the player either way. */
    public Result toggleWanted(int slot, ItemBlob blob) {
        if (chosen.containsKey(slot)) {
            return Result.ALREADY_OFFERED;
        }
        if (wanted.remove(slot) != null) {
            return Result.REMOVED;
        }
        if (wanted.size() >= maxItems) {
            return Result.TOO_MANY;
        }
        wanted.put(slot, blob);
        return Result.ADDED;
    }

    public boolean isOffered(int slot) {
        return chosen.containsKey(slot);
    }

    public boolean isWanted(int slot) {
        return wanted.containsKey(slot);
    }

    public List<ItemBlob> offered() {
        return new ArrayList<>(chosen.values());
    }

    public List<ItemBlob> wantedItems() {
        return new ArrayList<>(wanted.values());
    }

    public boolean empty() {
        return chosen.isEmpty() && wanted.isEmpty();
    }

    public int offeredCount() {
        return chosen.size();
    }

    public int wantedCount() {
        return wanted.size();
    }

    public void clear() {
        chosen.clear();
        wanted.clear();
    }

    /**
     * Drops ticks whose slot no longer holds what was ticked. Called before showing the window again, so a player who
     * moved something around sees the truth rather than a stale tick.
     */
    public void forget(int slot) {
        chosen.remove(slot);
        wanted.remove(slot);
    }

    public Optional<ItemBlob> offeredAt(int slot) {
        return Optional.ofNullable(chosen.get(slot));
    }

    /** What a tick did. */
    public enum Result {
        ADDED,
        REMOVED,
        TOO_MANY,
        ALREADY_OFFERED,
        ALREADY_WANTED
    }
}
