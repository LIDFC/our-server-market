package site.vinoff.market.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import site.vinoff.market.bukkit.BukkitInventoryPort;

/**
 * Keeps track of who has a marketplace window open.
 *
 * <p>Two rules live here. A window is always opened on the next tick, never from inside an inventory event, because
 * opening one in the middle of a click is the classic way to duplicate the item on the cursor. And while a window is
 * open the player counts as busy, so the marketplace will not hand them items behind their own screen.
 */
public final class WindowManager {

    private final Plugin plugin;
    private final BukkitInventoryPort inventory;
    private final Logger log;
    private final Map<UUID, MarketWindow> open = new HashMap<>();

    public WindowManager(Plugin plugin, BukkitInventoryPort inventory, Logger log) {
        this.plugin = plugin;
        this.inventory = inventory;
        this.log = log;
    }

    /** Opens a window on the next tick. Safe to call from inside a click handler. */
    public void open(Player player, MarketWindow window) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            window.refresh(player);
            open.put(player.getUniqueId(), window);
            inventory.setBusyInGui(player.getUniqueId(), true);
            player.openInventory(window.getInventory());
        });
    }

    /** Redraws what the player is looking at, if it is still ours. */
    public void refresh(Player player) {
        MarketWindow window = open.get(player.getUniqueId());
        if (window != null) {
            window.refresh(player);
        }
    }

    public void closed(Player player) {
        open.remove(player.getUniqueId());
        inventory.setBusyInGui(player.getUniqueId(), false);
    }

    public MarketWindow openWindow(Player player) {
        return open.get(player.getUniqueId());
    }

    /**
     * Closes every marketplace window. Called when the plugin is disabled: a window left open after the listener is
     * gone would be a window nobody is guarding.
     */
    public void closeAll() {
        for (UUID uuid : List.copyOf(open.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                // inline on purpose: during disable there is no next tick to schedule anything on
                player.closeInventory();
            }
            inventory.setBusyInGui(uuid, false);
        }
        open.clear();
    }

    /**
     * Throws away pictures from marketplace windows that somehow ended up in a player's inventory. They are worth
     * nothing, but a player holding one means something went wrong, so it is written to the log.
     */
    public void sweep(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int removed = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            if (Icons.isGuiItem(contents[slot])) {
                contents[slot] = null;
                removed++;
            }
        }
        if (removed > 0) {
            player.getInventory().setStorageContents(contents);
            player.updateInventory();
            log.warning("Removed " + removed + " marketplace window item(s) from the inventory of " + player.getName());
        }
    }
}
