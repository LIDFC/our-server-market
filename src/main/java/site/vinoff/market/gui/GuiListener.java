package site.vinoff.market.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/**
 * Guards marketplace windows.
 *
 * <p>The listener itself decides nothing: it translates the event into the plain question {@link GuiPolicy} answers,
 * cancels first, and only then lets a button run. The interesting rules therefore sit in a class that can be tested
 * without a server, and this file stays small enough to read in one go.
 */
public final class GuiListener implements Listener {

    private final WindowManager windows;

    public GuiListener(WindowManager windows) {
        this.windows = windows;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        MarketWindow window = windowOf(event.getView().getTopInventory());
        if (window == null) {
            return;
        }
        GuiPolicy.Decision decision =
                GuiPolicy.decide(true, event.getRawSlot(), window.size(), translate(event.getClick().name()));
        if (decision == GuiPolicy.Decision.IGNORE) {
            return;
        }
        // cancelled before anything else, always, whatever the click was
        event.setCancelled(true);
        if (decision == GuiPolicy.Decision.RUN_BUTTON && event.getWhoClicked() instanceof Player player) {
            window.click(player, event.getRawSlot(), translate(event.getClick().name()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        MarketWindow window = windowOf(event.getView().getTopInventory());
        if (window == null) {
            return;
        }
        int[] slots = event.getRawSlots().stream().mapToInt(Integer::intValue).toArray();
        if (GuiPolicy.decideDrag(true, slots, window.size()) != GuiPolicy.Decision.IGNORE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (windowOf(event.getView().getTopInventory()) != null && event.getPlayer() instanceof Player player) {
            // nothing to give back: a marketplace window never held anything of the player's
            windows.closed(player);
        }
    }

    /** A hopper or another plugin trying to pull items out of a window. There is nothing to pull, but no. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (windowOf(event.getSource()) != null || windowOf(event.getDestination()) != null) {
            event.setCancelled(true);
        }
    }

    private MarketWindow windowOf(Inventory inventory) {
        if (inventory == null || inventory.getType() == InventoryType.PLAYER) {
            return null;
        }
        // getHolder(false): no block snapshot, this runs on every click
        return inventory.getHolder(false) instanceof MarketWindow window ? window : null;
    }

    /** Bukkit's click type by name, so an unknown one from a future version lands on UNKNOWN instead of crashing. */
    private static GuiPolicy.Click translate(String bukkitName) {
        try {
            return GuiPolicy.Click.valueOf(bukkitName);
        } catch (IllegalArgumentException unknown) {
            return GuiPolicy.Click.UNKNOWN;
        }
    }
}
