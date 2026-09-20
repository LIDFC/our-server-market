package site.vinoff.market.bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.core.Digest;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.chest.ChestContents;
import site.vinoff.market.core.chest.ChestPlan;
import site.vinoff.market.core.chest.ChestSlot;
import site.vinoff.market.core.chest.ChestSnapshot;
import site.vinoff.market.core.model.BoundChest;
import site.vinoff.market.core.port.ContainerPort;

/**
 * The hands of the marketplace in the world, for chests.
 *
 * <p>Every method here hops to the server thread and does its whole job inside one tick. That is not a detail: the
 * world is written to disk between ticks, so contents and the journal number stamped on the block can never be saved
 * half apart from each other. It is the substitute for a flush, and the reason no chunk is ever forced to disk.
 *
 * <p>The class has no database and no service. It cannot get one without somebody adding a field, and the comment on
 * {@link MainThread} explains why that field would be a deadlock: the server thread would wait for the database
 * connection while the thread holding it waits for the server thread.
 */
public final class BukkitContainerPort implements ContainerPort {

    private final Server server;
    private final MainThread main;

    public BukkitContainerPort(Server server, MainThread main) {
        this.server = server;
        this.main = main;
    }

    @Override
    public ChestContents read(BoundChest chest) {
        return main.call("read chest #" + chest.id(), () -> {
            Opened opened = open(chest);
            Map<Integer, ItemBlob> items = new LinkedHashMap<>();
            List<ChestSlot> filled = new ArrayList<>();
            for (int slot = 0; slot < opened.inventory().getSize(); slot++) {
                ItemStack stack = opened.inventory().getItem(slot);
                if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                    continue;
                }
                ItemBlob blob = PaperItemCodec.encode(stack);
                items.put(slot, blob);
                filled.add(new ChestSlot(slot, Digest.sha256(blob.data()), blob.count(), blob.summary()));
            }
            return new ChestContents(
                    new ChestSnapshot(chest.size(), filled), items, ChestLocator.readSeq(opened.found()));
        });
    }

    @Override
    public List<ItemBlob> take(BoundChest chest, ChestPlan plan, long seq) {
        return main.call("take from chest #" + chest.id(), () -> {
            Opened opened = open(chest);
            if (!opened.inventory().getViewers().isEmpty()) {
                // a stack held on somebody's cursor is in neither the chest nor their inventory, and would drop back
                // the moment they closed the window, after the marketplace had already decided what the chest held
                throw new MarketException(MarketError.CHEST_IN_USE, "Somebody has this chest open");
            }
            // the plan is checked against the live chest, not against the snapshot the caller was working from
            snapshotOf(chest, opened).verify(plan);

            // everything is read before anything is written, so a refusal cannot leave half a plan applied
            List<ItemBlob> taken = new ArrayList<>(plan.takes().size());
            for (ChestPlan.Take take : plan.takes()) {
                ItemStack portion = opened.inventory().getItem(take.slot()).clone();
                portion.setAmount(take.amount());
                taken.add(PaperItemCodec.encode(portion));
            }
            for (ChestPlan.Take take : plan.takes()) {
                ItemStack whole = opened.inventory().getItem(take.slot());
                int left = whole.getAmount() - take.amount();
                if (left <= 0) {
                    opened.inventory().setItem(take.slot(), null);
                } else {
                    ItemStack rest = whole.clone();
                    rest.setAmount(left);
                    opened.inventory().setItem(take.slot(), rest);
                }
            }
            ChestLocator.writeSeq(opened.found(), seq);
            return taken;
        });
    }

    @Override
    public void stamp(BoundChest chest, long seq) {
        main.run("stamp chest #" + chest.id(), () -> ChestLocator.writeSeq(open(chest).found(), seq));
    }

    @Override
    public int dataVersion() {
        return PaperItemCodec.serverDataVersion();
    }

    /** The chest as the world has it right now, with the inventory the marketplace may read and change. */
    private record Opened(ChestLocator.Found found, Inventory inventory) {}

    private ChestSnapshot snapshotOf(BoundChest chest, Opened opened) {
        List<ChestSlot> filled = new ArrayList<>();
        for (int slot = 0; slot < opened.inventory().getSize(); slot++) {
            ItemStack stack = opened.inventory().getItem(slot);
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            ItemBlob blob = PaperItemCodec.encode(stack);
            filled.add(new ChestSlot(slot, Digest.sha256(blob.data()), blob.count(), blob.summary()));
        }
        return new ChestSnapshot(chest.size(), filled);
    }

    /**
     * Finds the chest and checks it is still the chest the marketplace bound.
     *
     * <p>The checks are not paranoia. A double chest that lost a half, or gained one on the other side, has the same
     * coordinates and different slot numbers, so acting on it would move whichever item happens to sit where the
     * website last saw something else.
     */
    private Opened open(BoundChest chest) {
        World world = server.getWorld(chest.worldUuid());
        if (world == null) {
            throw new MarketException(MarketError.CHEST_UNAVAILABLE, "That world is not loaded");
        }
        int chunkX = chest.x() >> 4;
        int chunkZ = chest.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ) && !world.loadChunk(chunkX, chunkZ, false)) {
            // false means do not generate: the chunk of a chest somebody placed always exists already, and asking
            // the server to invent terrain in order to answer a web request is never the right trade
            throw new MarketException(MarketError.CHEST_UNAVAILABLE, "The chest is in a chunk that is not there");
        }
        Block block = world.getBlockAt(chest.x(), chest.y(), chest.z());
        if (block.getType() != Material.CHEST) {
            throw new MarketException(MarketError.CHEST_MISSING, "There is no chest at those coordinates any more");
        }
        ChestLocator.Found found = ChestLocator.describe(block);
        if (found.kind() != chest.kind()) {
            throw new MarketException(MarketError.CHEST_CHANGED, "This chest is not the size it was bound as");
        }
        if (found.main().getX() != chest.x() || found.main().getY() != chest.y() || found.main().getZ() != chest.z()) {
            throw new MarketException(MarketError.CHEST_CHANGED, "This chest is paired differently than it was bound");
        }
        if (chest.isDouble()) {
            Block pair = found.pair();
            if (pair == null
                    || chest.pairX() == null
                    || pair.getX() != chest.pairX()
                    || pair.getY() != chest.pairY()
                    || pair.getZ() != chest.pairZ()) {
                throw new MarketException(MarketError.CHEST_CHANGED, "The other half of this chest has changed");
            }
        }
        Inventory inventory = ((Chest) block.getState()).getInventory();
        if (inventory.getSize() != chest.size()) {
            throw new MarketException(MarketError.CHEST_CHANGED, "This chest holds a different number of slots now");
        }
        return new Opened(found, inventory);
    }
}
