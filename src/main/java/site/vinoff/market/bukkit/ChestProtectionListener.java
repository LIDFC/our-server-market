package site.vinoff.market.bukkit;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
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
 * fires every few ticks for every hopper on the server, which is why the answer comes from an in-memory index, why
 * the cheap question is asked first, and why the block is found without rebuilding one. {@code BlockPlaceEvent} is the
 * quiet one: a stranger placing an ordinary chest against a bound single chest would turn it into a double chest,
 * own half of somebody else's stock, and shift every slot number the website had just shown by twenty-seven.
 *
 * <p>One rule runs through all of them: <b>an event handler never reaches into the world itself.</b> Reconciling a
 * chest reads blocks and may load a chunk, and doing that while the server is in the middle of loading a chunk — or
 * in the middle of opening an inventory for somebody — re-enters a system that is not re-entrant. Anything of that
 * kind is handed to the next tick instead.
 */
public final class ChestProtectionListener implements Listener {

    private static final BlockFace[] NEIGHBOURS = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final MarketService market;
    private final ChestKeeper keeper;
    private final ChestIndex index;
    private final Logger log;
    /** so a bug in here cannot turn into a stack trace on every tick of every hopper */
    private long lastComplaint;

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
     *
     * <p>This is also the one place where a chest may be checked against the world on the spot: somebody is standing
     * in front of the block, so its chunk is loaded, nothing is being opened yet, and the alternative is an owner
     * whose own chest refuses to open until the next sweep.
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
        guard(event.getPlayer(), () -> refuse(event.getPlayer(), block, ChestGuard.Action.OPEN, true), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (index.isEmpty() || event.getInventory().getType() != InventoryType.CHEST) {
            return;
        }
        Block block = blockOf(event.getInventory());
        if (block == null) {
            return;
        }
        Player player = event.getPlayer() instanceof Player human ? human : null;
        // no checking against the world from here: the server is part way through opening this very inventory, and a
        // reconcile is allowed to change what is in it
        guard(player, () -> refuse(player, block, ChestGuard.Action.OPEN, false), event::setCancelled);
    }

    // breaking --------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (index.isEmpty() || event.getBlock().getType() != Material.CHEST) {
            return;
        }
        guard(event.getPlayer(), () -> {
            if (refuse(event.getPlayer(), event.getBlock(), ChestGuard.Action.BREAK, true)) {
                return true;
            }
            Optional<ChestIndex.Entry> entry = entryAt(event.getBlock());
            if (entry.isEmpty()) {
                return false;
            }
            // an administrator is allowed to clear away an abandoned chest, and the binding goes with the block
            try {
                market.releaseChest(entry.get().owner(), "ADMIN_BREAK");
                keeper.released(entry.get().chestId());
                event.getPlayer().sendMessage(Messages.info("Привязка склада #" + entry.get().chestId() + " снята"));
                log.warning("Chest #" + entry.get().chestId() + " was broken by " + event.getPlayer().getName());
                return false;
            } catch (MarketException refused) {
                event.getPlayer().sendMessage(Messages.of(refused.error(), refused.getMessage()));
                return true;
            }
        }, event::setCancelled);
    }

    // automation, blasts and pistons ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransfer(InventoryMoveItemEvent event) {
        if (index.isEmpty()) {
            return;
        }
        // the cheap question first: a hopper feeding a furnace or another hopper is most of what this event ever is,
        // and answering it without touching a holder is what keeps this off the tick budget
        if (event.getSource().getType() != InventoryType.CHEST
                && event.getDestination().getType() != InventoryType.CHEST) {
            return;
        }
        guard(null, () -> touchesBoundChest(event.getSource()) || touchesBoundChest(event.getDestination()),
                // no message: there is nobody to tell, and a hopper would ask again in a few ticks
                event::setCancelled);
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

    /**
     * A chunk with a bound chest in it has just appeared.
     *
     * <p>The check is handed to the next tick, and that is not tidiness. Reconciling reads the block and may load the
     * chunk it is in; doing that from inside a chunk load re-enters the chunk system, which ends in a stack trace on
     * the main thread and a player dropped from the server — and it would happen to the owner first, because the
     * owner is the one who walks to their own chest.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (index.isEmpty()) {
            return;
        }
        for (long chestId : index.inChunk(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ())) {
            if (!index.isSettled(chestId)) {
                keeper.checkSoon(chestId);
            }
        }
    }

    // plumbing --------------------------------------------------------------------------------------------------

    /**
     * Runs a decision and cancels if it says so.
     *
     * <p>The try/catch is the point. A refusal that fails to be computed must never cancel vanilla behaviour, and
     * must never print a stack trace on every hopper tick: one complaint a minute is enough to find the bug, and a
     * flooded console is itself an outage.
     */
    private void guard(Player player, BooleanSupplier decide, Consumer<Boolean> cancel) {
        try {
            if (decide.getAsBoolean()) {
                cancel.accept(true);
            }
        } catch (RuntimeException failure) {
            long now = System.currentTimeMillis();
            if (now - lastComplaint > 60_000) {
                lastComplaint = now;
                log.warning("A bound chest could not be checked: " + failure);
            }
            // a check that could not be made is refused, not allowed: a cancelled hopper is an inconvenience, and
            // an uncancelled one empties somebody stock while the marketplace is showing it as available
            cancel.accept(true);
            if (player != null) {
                player.sendMessage(Messages.bad("Рынок не смог проверить сундук, попробуйте ещё раз"));
            }
        }
    }

    /** Asks the table, and tells the player when the answer is no. True means the caller should cancel. */
    private boolean refuse(Player player, Block block, ChestGuard.Action action, boolean mayCheckNow) {
        Optional<ChestIndex.Entry> entry = entryAt(block);
        if (entry.isEmpty()) {
            return false;
        }
        if (!entry.get().settled()) {
            if (mayCheckNow) {
                // somebody is standing in front of the block, so its chunk is loaded and nothing else is in flight
                keeper.check(entry.get().chestId());
                entry = entryAt(block);
                if (entry.isEmpty()) {
                    return false;
                }
            } else {
                keeper.checkSoon(entry.get().chestId());
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
        Block block = blockOf(inventory);
        return block != null && isBound(block);
    }

    /**
     * Which block an inventory belongs to.
     *
     * <p>Asked of the inventory itself rather than of its holder. {@code getHolder()} rebuilds the whole block entity
     * to hand back a snapshot, and this runs in the hopper path, where the event fires for every hopper on the server
     * several times a second.
     *
     * <p>For a double chest this gives one of the two blocks, and which one does not matter: the index holds both and
     * points them at the same binding.
     */
    private static Block blockOf(Inventory inventory) {
        Location where = inventory.getLocation();
        return where == null ? null : where.getBlock();
    }
}
