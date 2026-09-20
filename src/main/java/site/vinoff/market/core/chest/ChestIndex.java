package site.vinoff.market.core.chest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import site.vinoff.market.core.model.BoundChest;

/**
 * Which blocks in the world are somebody's marketplace stock, held in memory.
 *
 * <p>This exists for one reason: {@code InventoryMoveItemEvent} fires on every tick of every hopper on the server,
 * and answering "is this a bound chest" from SQLite there would put a database query in the hot path of the main
 * thread. The index answers from two maps instead, and the common case — a server where nobody has bound anything —
 * is a single volatile read.
 *
 * <p>No Bukkit types on purpose. A block is a world UUID and three numbers here, which is all the rules need and all
 * a test needs to state them.
 */
public final class ChestIndex {

    /** One block of a bound chest. A double chest puts both of its blocks in, pointing at the same entry. */
    public record Entry(long chestId, UUID owner, boolean settled) {

        public Entry settled(boolean value) {
            return new Entry(chestId, owner, value);
        }
    }

    private record Block(UUID world, int x, int y, int z) {}

    private record Chunk(UUID world, int x, int z) {}

    private final Map<Block, Entry> blocks = new ConcurrentHashMap<>();
    private final Map<Chunk, List<Long>> chunks = new ConcurrentHashMap<>();
    private final Map<Long, List<Block>> byChest = new ConcurrentHashMap<>();

    /** True when nothing is bound at all, which lets every listener leave immediately. */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public void remember(BoundChest chest, boolean settled) {
        forget(chest.id());
        Entry entry = new Entry(chest.id(), chest.ownerUuid(), settled);
        List<Block> mine = new ArrayList<>(2);
        mine.add(new Block(chest.worldUuid(), chest.x(), chest.y(), chest.z()));
        if (chest.isDouble() && chest.pairX() != null) {
            mine.add(new Block(chest.worldUuid(), chest.pairX(), chest.pairY(), chest.pairZ()));
        }
        for (Block block : mine) {
            blocks.put(block, entry);
            chunks.compute(chunkOf(block), (key, ids) -> {
                // both halves of a double chest are often in the same chunk, and the same id written twice would
                // never clear out again when the binding is dropped
                List<Long> here = ids == null ? new ArrayList<>(1) : new ArrayList<>(ids);
                if (!here.contains(chest.id())) {
                    here.add(chest.id());
                }
                return here;
            });
        }
        byChest.put(chest.id(), mine);
    }

    public void forget(long chestId) {
        List<Block> mine = byChest.remove(chestId);
        if (mine == null) {
            return;
        }
        for (Block block : mine) {
            blocks.remove(block);
            chunks.computeIfPresent(chunkOf(block), (key, ids) -> {
                List<Long> left = new ArrayList<>(ids);
                left.remove(Long.valueOf(chestId));
                return left.isEmpty() ? null : left;
            });
        }
    }

    /** Marks a chest as agreed with, or as needing checking again. Absent chests are ignored, not created. */
    public void setSettled(long chestId, boolean settled) {
        List<Block> mine = byChest.get(chestId);
        if (mine == null) {
            return;
        }
        for (Block block : mine) {
            blocks.computeIfPresent(block, (key, entry) -> entry.settled(settled));
        }
    }

    public Optional<Entry> at(UUID world, int x, int y, int z) {
        return Optional.ofNullable(blocks.get(new Block(world, x, y, z)));
    }

    /** The bound chests with a block in one chunk, so a chunk that has just loaded can be checked at once. */
    public List<Long> inChunk(UUID world, int chunkX, int chunkZ) {
        List<Long> ids = chunks.get(new Chunk(world, chunkX, chunkZ));
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /** Whether the marketplace has already agreed with the world about this chest during this run. */
    public boolean isSettled(long chestId) {
        List<Block> mine = byChest.get(chestId);
        if (mine == null || mine.isEmpty()) {
            return true;
        }
        Entry entry = blocks.get(mine.get(0));
        return entry == null || entry.settled();
    }

    /** True when the chest is one block. Only a single chest can be turned into a double by a neighbour. */
    public boolean isSingleBlock(long chestId) {
        List<Block> mine = byChest.get(chestId);
        return mine != null && mine.size() == 1;
    }

    public int size() {
        return byChest.size();
    }

    public void clear() {
        blocks.clear();
        chunks.clear();
        byChest.clear();
    }

    private static Chunk chunkOf(Block block) {
        return new Chunk(block.world(), block.x() >> 4, block.z() >> 4);
    }
}
