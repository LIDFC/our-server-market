package site.vinoff.market.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import site.vinoff.market.core.chest.ChestContents;
import site.vinoff.market.core.chest.ChestPlan;
import site.vinoff.market.core.chest.ChestSlot;
import site.vinoff.market.core.chest.ChestSnapshot;
import site.vinoff.market.core.model.BoundChest;
import site.vinoff.market.core.port.ContainerPort;

/**
 * A chest in memory.
 *
 * <p>The one method that matters is {@link #rollbackTo}: it puts the contents <em>and</em> the stamped number back
 * the way they were, which is exactly what a chunk that was never written to disk does. Without it the most dangerous
 * case in the whole feature — the database committed, the world did not — could not be tested at all.
 */
public final class FakeContainer implements ContainerPort {

    /** A chest as the world holds it: what is in each slot, and the number stamped on the block. */
    public record World(Map<Integer, ItemBlob> slots, long seq) {
        public World {
            slots = new LinkedHashMap<>(slots);
        }
    }

    private final Map<Long, World> chests = new LinkedHashMap<>();
    private int dataVersion = 4000;
    private boolean missing;
    private boolean unreachable;
    private boolean occupied;
    private boolean crashAfterTake;

    /**
     * Models the server dying the instant after the chest was changed.
     *
     * <p>An {@link Error} rather than an exception on purpose: the take is wrapped in a catch for
     * {@code RuntimeException}, which unwinds cleanly and aborts the operation. A crash does no such thing — it stops
     * the process where it stands and leaves the intent exactly as it was. Only an Error models that honestly.
     */
    public static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("the server stopped right after the chest changed");
        }
    }

    /** The next take changes the world and then stops dead. One shot. */
    public void crashAfterTake() {
        crashAfterTake = true;
    }

    public static ItemBlob item(String name, int count) {
        return new ItemBlob(("item:" + name).getBytes(StandardCharsets.UTF_8), count, count + "x " + name);
    }

    public void put(long chestId, int slot, ItemBlob blob) {
        World world = chests.computeIfAbsent(chestId, id -> new World(Map.of(), 0));
        Map<Integer, ItemBlob> slots = new LinkedHashMap<>(world.slots());
        slots.put(slot, blob);
        chests.put(chestId, new World(slots, world.seq()));
    }

    /** A copy of the world's side of a chest, to be handed back to {@link #rollbackTo} later. */
    public World snapshot(long chestId) {
        World world = chests.getOrDefault(chestId, new World(Map.of(), 0));
        return new World(world.slots(), world.seq());
    }

    /** The chunk was never saved: contents and stamp both go back together, because they live in the same block. */
    public void rollbackTo(long chestId, World world) {
        chests.put(chestId, new World(world.slots(), world.seq()));
    }

    public Map<Integer, ItemBlob> contentsOf(long chestId) {
        return chests.getOrDefault(chestId, new World(Map.of(), 0)).slots();
    }

    public long seqOf(long chestId) {
        return chests.getOrDefault(chestId, new World(Map.of(), 0)).seq();
    }

    public int totalItems() {
        return chests.values().stream()
                .flatMap(world -> world.slots().values().stream())
                .mapToInt(ItemBlob::count)
                .sum();
    }

    public void setDataVersion(int version) {
        dataVersion = version;
    }

    public void vanish() {
        missing = true;
    }

    public void unreachable(boolean value) {
        unreachable = value;
    }

    public void occupied(boolean value) {
        occupied = value;
    }

    @Override
    public ChestContents read(BoundChest chest) {
        refuseIfUnusable();
        if (occupied) {
            throw new MarketException(MarketError.CHEST_IN_USE, "Somebody has it open");
        }
        World world = chests.getOrDefault(chest.id(), new World(Map.of(), 0));
        List<ChestSlot> filled = new ArrayList<>();
        for (Map.Entry<Integer, ItemBlob> entry : world.slots().entrySet()) {
            ItemBlob blob = entry.getValue();
            filled.add(new ChestSlot(entry.getKey(), Digest.sha256(blob.data()), blob.count(), blob.summary()));
        }
        return new ChestContents(new ChestSnapshot(chest.size(), filled), world.slots(), world.seq());
    }

    @Override
    public List<ItemBlob> take(BoundChest chest, ChestPlan plan, long seq) {
        refuseIfUnusable();
        World world = chests.getOrDefault(chest.id(), new World(Map.of(), 0));
        read(chest).snapshot().verify(plan);

        Map<Integer, ItemBlob> slots = new LinkedHashMap<>(world.slots());
        List<ItemBlob> taken = new ArrayList<>();
        for (ChestPlan.Take entry : plan.takes()) {
            ItemBlob whole = slots.get(entry.slot());
            taken.add(new ItemBlob(whole.data(), entry.amount(), whole.summary()));
            int left = whole.count() - entry.amount();
            if (left == 0) {
                slots.remove(entry.slot());
            } else {
                slots.put(entry.slot(), new ItemBlob(whole.data(), left, whole.summary()));
            }
        }
        chests.put(chest.id(), new World(slots, seq));
        if (crashAfterTake) {
            crashAfterTake = false;
            throw new SimulatedCrash();
        }
        return taken;
    }

    @Override
    public void stamp(BoundChest chest, long seq) {
        refuseIfUnusable();
        World world = chests.getOrDefault(chest.id(), new World(Map.of(), 0));
        chests.put(chest.id(), new World(world.slots(), seq));
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    private void refuseIfUnusable() {
        if (missing) {
            throw new MarketException(MarketError.CHEST_MISSING, "The block is gone");
        }
        if (unreachable) {
            throw new MarketException(MarketError.CHEST_UNAVAILABLE, "The chunk is not loaded");
        }
    }
}
