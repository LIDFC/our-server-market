package site.vinoff.market.core.port;

import java.util.List;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.chest.ChestContents;
import site.vinoff.market.core.chest.ChestPlan;
import site.vinoff.market.core.model.BoundChest;

/**
 * The only way the marketplace touches a chest in the world.
 *
 * <p>A separate port from {@link InventoryPort} rather than more methods on it, because the two have almost nothing
 * in common: a player's inventory can only be reached while they are online and is flushed with {@code saveData()},
 * a chest can be read at any hour and cannot be flushed at all. Keeping them apart also keeps the honest reminder in
 * sight that a chest has no durability barrier, which is why every operation carries a sequence number.
 *
 * <p>Implementations run on the server's main thread. Refusals are {@code MarketException}s: {@code CHEST_MISSING}
 * when the block is not the chest it was, {@code CHEST_UNAVAILABLE} when the world or chunk cannot be reached,
 * {@code CHEST_IN_USE} when somebody has it open, {@code CHEST_CHANGED} when the contents no longer match a plan.
 */
public interface ContainerPort {

    /** What is in the chest right now, together with the journal number stamped on the block. */
    ChestContents read(BoundChest chest);

    /**
     * Takes exactly the plan out of the chest and stamps {@code seq} onto the block, in that order and all or
     * nothing. Returns what was removed, so the caller can record it.
     *
     * <p>The plan is checked against the live chest again here, even though the caller already checked it against a
     * snapshot: between the two the owner may have opened the box. Refusing is always right; taking the wrong stack
     * never is.
     */
    List<ItemBlob> take(BoundChest chest, ChestPlan plan, long seq);

    /** Writes the journal number without touching the contents. Used when a reconcile agrees with the world. */
    void stamp(BoundChest chest, long seq);

    /** Data version of the running server, so a rollback to an older Minecraft is noticed rather than guessed at. */
    int dataVersion();
}
