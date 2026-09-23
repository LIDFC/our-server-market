package site.vinoff.market.bukkit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.core.chest.ChestIndex;
import site.vinoff.market.core.model.BoundChest;

/**
 * Keeps the index of bound chest blocks in step with the database, and makes sure every chest is checked against the
 * world before anybody is allowed to touch it.
 *
 * <p>There is no login event to hang this on. A chest belongs to nobody who has to be present, so the moments when a
 * chest and the marketplace can be brought back into agreement are: the server starting, a chunk appearing, somebody
 * asking about the chest, and, for everything that none of those reached, a sweep every minute.
 *
 * <p>Until that agreement is reached the chest is frozen — the guard refuses to open it and the service refuses to
 * take from it. That is what closes the gap between a chunk appearing and the check running, and it is the reason the
 * check can afford to be lazy at all.
 */
public final class ChestKeeper {

    /** How often chests nobody has walked near are looked at. Ticks, so: every minute. */
    private static final long SWEEP_TICKS = 20L * 60L;

    /** How long to leave a chest alone after a check that did not settle it. */
    private static final long RETRY_MS = 10_000;

    private final Plugin plugin;
    private final MarketService market;
    private final Logger log;
    private final ChestIndex index = new ChestIndex();
    /** chests already waiting for the next tick, so a hundred chunk loads do not book a hundred checks */
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastTried = new ConcurrentHashMap<>();
    private long lastComplaint;

    public ChestKeeper(Plugin plugin, MarketService market, Logger log) {
        this.plugin = plugin;
        this.market = market;
        this.log = log;
    }

    public ChestIndex index() {
        return index;
    }

    /** Fills the index from the database and starts the sweep. Called once, while the server is still starting. */
    public void start() {
        List<BoundChest> all = market.boundChests();
        int waiting = 0;
        for (BoundChest chest : all) {
            boolean settled = !chest.settling(market.bootId());
            index.remember(chest, settled);
            if (!settled) {
                waiting++;
            }
        }
        if (!all.isEmpty()) {
            log.info(all.size() + " bound chest(s), " + waiting + " waiting to be checked against the world.");
        }
        // a chest with an operation left open by the last run is the one case worth loading a chunk for: until it is
        // settled the marketplace does not know whether those items are in the box or gone from it
        for (BoundChest chest : all) {
            if (chest.settling(market.bootId()) && market.chestHasOpenIntent(chest.id())) {
                log.warning("Chest #" + chest.id() + " has an operation left open by the last run; checking it now.");
                check(chest.id());
            }
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_TICKS, SWEEP_TICKS);
    }

    /** A chest has just been bound. */
    public void bound(BoundChest chest) {
        index.remember(chest, !chest.settling(market.bootId()));
    }

    public void released(long chestId) {
        index.forget(chestId);
        lastTried.remove(chestId);
    }

    /**
     * Checks a chest on the next tick rather than now.
     *
     * <p>For callers that are in the middle of something the server does not want re-entered: a chunk being loaded, an
     * inventory being opened. Reconciling reads blocks and may load a chunk, and doing either from inside those is
     * how a plugin takes the main thread down with it.
     */
    public void checkSoon(long chestId) {
        if (!queued.add(chestId)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                queued.remove(chestId);
                Long tried = lastTried.get(chestId);
                if (tried != null && System.currentTimeMillis() - tried < RETRY_MS) {
                    // it failed a moment ago; the sweep will come back to it rather than us trying on every event
                    return;
                }
                check(chestId);
            });
        } catch (RuntimeException stopping) {
            // the scheduler refuses new work while the plugin is being disabled; the next run will pick it up
            queued.remove(chestId);
        }
    }

    /**
     * Brings one chest and the world back into agreement, and updates the index with whatever came of it.
     *
     * <p>Never throws. Every caller is an event handler or a scheduled task, and a chest that cannot be checked right
     * now stays frozen, which is the safe state; the next sweep tries again.
     */
    public void check(long chestId) {
        lastTried.put(chestId, System.currentTimeMillis());
        try {
            market.reconcileChest(chestId);
        } catch (RuntimeException failure) {
            complain(chestId, failure);
        }
        Optional<BoundChest> after = market.chestById(chestId);
        if (after.isEmpty() || !after.get().usable()) {
            index.forget(chestId);
            return;
        }
        index.setSettled(chestId, !after.get().settling(market.bootId()));
    }

    /** One line a minute is enough to find a problem; a line per attempt is an outage of its own. */
    private void complain(long chestId, RuntimeException failure) {
        long now = System.currentTimeMillis();
        if (now - lastComplaint > 60_000) {
            lastComplaint = now;
            log.warning("Chest #" + chestId + " could not be checked: " + failure);
        }
    }

    /** Picks up the chests nobody walked near: those still waiting whose chunk happens to be loaded. */
    private void sweep() {
        if (index.isEmpty()) {
            return;
        }
        List<BoundChest> all;
        try {
            all = market.boundChests();
        } catch (RuntimeException failure) {
            complain(0, failure);
            return;
        }
        for (BoundChest chest : all) {
            index.remember(chest, !chest.settling(market.bootId()));
        }
        for (BoundChest chest : all) {
            if (!chest.settling(market.bootId())) {
                continue;
            }
            World world = plugin.getServer().getWorld(chest.worldUuid());
            if (world == null || !world.isChunkLoaded(chest.x() >> 4, chest.z() >> 4)) {
                // nobody is near it, so nothing is asking about it either; it stays frozen and costs nothing
                continue;
            }
            check(chest.id());
        }
    }
}
