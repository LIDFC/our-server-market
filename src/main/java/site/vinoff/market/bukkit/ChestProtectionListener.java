package site.vinoff.market.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.core.chest.ChestGuard;
import site.vinoff.market.core.chest.ChestIndex;

/**
 * The bound chest, defended.
 *
 * <p>Every decision here is made by {@link ChestGuard}, which is a table with no Bukkit in it and is tested
 * exhaustively. This class only translates: it works out which block an event is about, asks the table, and cancels.
 * Rules split across event handlers can only be tested by example, and a rule about somebody else reaching into your
 * stock deserves better than examples.
 *
 * <p>Two of these handlers are easy to underestimate. {@code InventoryMoveItemEvent} is what a hopper does, and it
 * fires every few ticks for every hopper on the server, which is why the answer comes from an in-memory index and
 * never from the database. {@code BlockPlaceEvent} is the quiet one: a stranger placing an ordinary chest against a
 * bound single chest would turn it into a double chest, own half of somebody else's stock, and shift every slot
 * number the website had just shown by twenty-seven.
 */
public final class ChestProtectionListener implements Listener {

    private static final BlockFace[] NEIGHBOURS = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final MarketService market;
    private final ChestKeeper keeper;
    private final ChestIndex index;
    private final Logger log;

    public ChestProtectionListener(MarketService market, ChestKeeper keeper, Logger log) {
        this.market = market;
        this.keeper = keeper;
        this.index = keeper.index();
        this.log = log;
    }

    // opening ---------------------------------------------------------------------------------------------------

    /**
     * The polite refusal: the player never sees the chest open, and is told why.
     *
     * <p>Kept alongside the inventory handler below rather than instead of it. This one gives the message; that one
     * catches anything opened by code, where there is no click to intercept.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (index.isEmpty() || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CHEST) {
            return;
        }
        if (refuse(event.getPlayer(), block, ChestGuard.Action.OPEN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (index.isEmpty()) {
            return;
        }
        Player player = event.getPlayer() instanceof Player human ? human : null;
        for (Block block : blocksOf(event.getInventory())) {
            if (refuse(player, block, ChestGuard.Action.OPEN)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // breaking --------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (index.isEmpty() || event.getBlock().getType() != Material.CHEST) {
            return;
        }
        if (refuse(event.getPlayer(), event.getBlock(), ChestGuard.Action.BREAK)) {
            event.setCancelled(true);
            return;
        }
        Optional<ChestIndex.Entry> entry = entryAt(event.getBlock());
        if (entry.isEmpty()) {
            return;
        }
        // an administrator is allowed to clear away an abandoned chest, and the binding goes with the block
        try {
            market.releaseChest(entry.get().owner(), "ADMIN_BREAK");
            keeper.released(entry.get().chestId());
            event.getPlayer().sendMessage(Messages.info("Привязка склада #" + entry.get().chestId() + " снята"));
            log.warning("Chest #" + entry.get().chestId() + " was broken by " + event.getPlayer().getName());
        } catch (MarketException refused) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Messages.of(refused.error(), refused.getMessage()));
        }
    }

    // automation, blasts and pistons ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransfer(InventoryMoveItemEvent event) {
        if (index.isEmpty()) {
            return;
        }
        if (touchesBoundChest(event.getSource()) || touchesBoundChest(event.getDestination())) {
            // no message: there is nobody to tell, and a hopper would ask again every few ticks
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!index.isEmpty()) {
            // removing only the bound blocks, so the rest of the blast still happens as it should
            event.blockList().removeIf(this::isBound);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!index.isEmpty()) {
            event.blockList().removeIf(this::isBound);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!index.isEmpty() && event.getBlocks().stream().anyMatch(this::isBound)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!index.isEmpty() && event.getBlocks().stream().anyMatch(this::isBound)) {
            event.setCancelled(true);
        }
    }

    // pairing ---------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (index.isEmpty() || event.getBlockPlaced().getType() != Material.CHEST) {
            return;
        }
        for (BlockFace side : NEIGHBOURS) {
            Block neighbour = event.getBlockPlaced().getRelative(side);
            if (neighbour.getType() != Material.CHEST) {
                continue;
            }
            Optional<ChestIndex.Entry> entry = entryAt(neighbour);
            if (entry.isEmpty() || !index.isSingleBlock(entry.get().chestId())) {
                // a chest that is already double cannot take a third block, so vanilla refuses that on its own
                continue;
            }
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Messages.bad("Здесь нельзя ставить сундук: он стал бы половиной привязанного склада"));
            return;
        }
    }

    // keeping up with the world -----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        keeper.chunkLoaded(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    // plumbing --------------------------------------------------------------------------------------------------

    /** Asks the table, and tells the player when the answer is no. True means the caller should cancel. */
    private boolean refuse(Player player, Block block, ChestGuard.Action action) {
        Optional<ChestIndex.Entry> entry = entryAt(block);
        if (entry.isEmpty()) {
            return false;
        }
        if (!entry.get().settled()) {
            // somebody is standing in front of the block, so its chunk is loaded and the check can happen now rather
            // than leaving the owner staring at a chest that will not open until the next sweep
            keeper.check(entry.get().chestId());
            entry = entryAt(block);
            if (entry.isEmpty()) {
                return false;
            }
        }
        UUID who = player == null ? null : player.getUniqueId();
        ChestGuard.Verdict verdict = ChestGuard.decide(
                true,
                entry.get().owner().equals(who),
                player != null && player.hasPermission("ourserver.market.admin"),
                !entry.get().settled(),
                action);
        if (verdict == ChestGuard.Verdict.ALLOW) {
            return false;
        }
        if (player != null) {
            player.sendMessage(Messages.bad(ChestGuard.message(verdict)));
        }
        return true;
    }

    private Optional<ChestIndex.Entry> entryAt(Block block) {
        return index.at(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private boolean isBound(Block block) {
        return entryAt(block).isPresent();
    }

    private boolean touchesBoundChest(Inventory inventory) {
        for (Block block : blocksOf(inventory)) {
            if (isBound(block)) {
                return true;
            }
        }
        return false;
    }

    /** The blocks behind an inventory, which for a double chest is both of them and for anything else is none. */
    private static List<Block> blocksOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest both) {
            List<Block> blocks = new ArrayList<>(2);
            if (both.getLeftSide() instanceof Chest left) {
                blocks.add(left.getBlock());
            }
            if (both.getRightSide() instanceof Chest right) {
                blocks.add(right.getBlock());
            }
            return blocks;
        }
        if (holder instanceof Chest chest) {
            return List.of(chest.getBlock());
        }
        return List.of();
    }
}
