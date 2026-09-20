package site.vinoff.market.bukkit;

import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.chest.ChestKind;
import site.vinoff.market.core.model.BoundChest;

/**
 * Finding a chest in the world, and the number written on it.
 *
 * <p>The number is the whole reason the marketplace can trust a chest it cannot flush to disk. It is kept in the
 * block's own persistent data, which means it is saved with the chunk and — this is the part that matters — lost
 * with the chunk when the server dies before the chunk is written. Contents and number therefore always travel
 * together: there is no way for one to survive a crash without the other.
 *
 * <p>Both halves of a double chest are stamped, and the number read back is the lower of the two. Two halves can sit
 * in different chunks and be saved at different moments, so believing the higher one would mean believing a write
 * that may not have reached the disk.
 */
public final class ChestLocator {

    /** How far a player may be from the chest they are pointing at. */
    private static final int REACH = 6;

    private static NamespacedKey seqKey;

    private ChestLocator() {}

    public static void init(Plugin plugin) {
        seqKey = new NamespacedKey(plugin, "chest_seq");
    }

    /** A chest in the world: the block the marketplace records, the other half if there is one, and which kind. */
    public record Found(Block main, Block pair, ChestKind kind) {

        public int[] pairCoordinates() {
            return pair == null ? null : new int[] {pair.getX(), pair.getY(), pair.getZ()};
        }
    }

    /**
     * The chest a player is looking at.
     *
     * <p>A trapped chest is refused on purpose: it powers redstone when opened, so "is somebody in the stock right
     * now" would become an observable signal anybody could wire up next to it.
     */
    public static Found lookingAt(Player player) {
        Block block = player.getTargetBlockExact(REACH);
        if (block == null || block.getType() != Material.CHEST) {
            if (block != null && block.getType() == Material.TRAPPED_CHEST) {
                throw new MarketException(MarketError.INVALID_REQUEST, "Ловушечный сундук привязать нельзя: он даёт сигнал редстоуна");
            }
            throw new MarketException(MarketError.INVALID_REQUEST, "Посмотрите на сундук и повторите");
        }
        return describe(block);
    }

    /** Works out whether a chest block is half of a double chest, and which block the other half is. */
    public static Found describe(Block block) {
        if (!(block.getState() instanceof Chest chest)) {
            throw new MarketException(MarketError.CHEST_MISSING, "Этот блок — не сундук");
        }
        if (chest.getInventory().getHolder() instanceof DoubleChest both) {
            Block left = sideBlock(both.getLeftSide());
            Block right = sideBlock(both.getRightSide());
            if (left == null || right == null) {
                throw new MarketException(MarketError.CHEST_MISSING, "Не удалось разобрать большой сундук");
            }
            // the left half is the one recorded, so the same double chest is always described the same way,
            // whichever of its two blocks the player happened to look at
            return new Found(left, right, ChestKind.DOUBLE);
        }
        return new Found(block, null, ChestKind.SINGLE);
    }

    private static Block sideBlock(Object side) {
        return side instanceof Chest half ? half.getBlock() : null;
    }

    /** The blocks of a binding, or empty when the world is not loaded or the block is not a chest any more. */
    public static Optional<Found> blocksOf(Server server, BoundChest chest) {
        World world = server.getWorld(chest.worldUuid());
        if (world == null) {
            return Optional.empty();
        }
        Block main = world.getBlockAt(chest.x(), chest.y(), chest.z());
        if (main.getType() != Material.CHEST) {
            return Optional.empty();
        }
        if (!chest.isDouble()) {
            return Optional.of(new Found(main, null, ChestKind.SINGLE));
        }
        if (chest.pairX() == null) {
            return Optional.empty();
        }
        Block pair = world.getBlockAt(chest.pairX(), chest.pairY(), chest.pairZ());
        if (pair.getType() != Material.CHEST) {
            return Optional.empty();
        }
        return Optional.of(new Found(main, pair, ChestKind.DOUBLE));
    }

    /** The journal number on the block, or the lower of the two when the chest has two halves. */
    public static long readSeq(Found found) {
        long main = readSeq(found.main());
        return found.pair() == null ? main : Math.min(main, readSeq(found.pair()));
    }

    private static long readSeq(Block block) {
        if (!(block.getState() instanceof Chest chest)) {
            return 0L;
        }
        return chest.getPersistentDataContainer().getOrDefault(seqKey, PersistentDataType.LONG, 0L);
    }

    /** Writes the journal number onto every block of the chest. */
    public static void writeSeq(Found found, long seq) {
        writeSeq(found.main(), seq);
        if (found.pair() != null) {
            writeSeq(found.pair(), seq);
        }
    }

    private static void writeSeq(Block block, long seq) {
        if (block.getState() instanceof Chest chest) {
            chest.getPersistentDataContainer().set(seqKey, PersistentDataType.LONG, seq);
            // getState gives a snapshot; without update the number never reaches the block
            chest.update();
        }
    }
}
