package site.vinoff.market.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import site.vinoff.market.core.ItemBlob;

/**
 * The only way the marketplace touches a player's inventory. The Bukkit layer implements it on the main thread; tests
 * implement it with a fake, which is what lets every rule below be tested without a server.
 */
public interface InventoryPort {

    /**
     * A fingerprint of everything the player is carrying. Taken before items are removed and compared after a crash:
     * if it still matches, the removal never happened.
     */
    Optional<String> digest(UUID player);

    /** True when items may be handed over right now: online, logged in, alive, and not inside a marketplace screen. */
    boolean readyForItems(UUID player);

    /** Whether all of these stacks fit in the player's inventory as it is right now. */
    boolean fits(UUID player, List<ItemBlob> items);

    /** Removes exactly these stacks. False means nothing was removed: this is all or nothing. */
    boolean removeExactly(UUID player, List<ItemBlob> items);

    /** Adds stacks that {@link #fits} has already approved. False means nothing was added. */
    boolean addAll(UUID player, List<ItemBlob> items);

    /**
     * Writes the player's data to disk. This is the durability barrier: without it the server can crash with the item
     * gone from the database's point of view but still present in the player file, which is a duplicate.
     */
    boolean save(UUID player);

    /** Data version of the running server, stored beside every item so a rollback can be detected. */
    int dataVersion();
}
