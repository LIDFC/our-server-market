package site.vinoff.market.gui;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.bukkit.Messages;

/**
 * The way into the marketplace windows. Every window is opened through here, so opening one always happens on the next
 * tick and the manager always knows who is looking at what.
 */
public final class Gui {

    private final Plugin plugin;
    private final MarketService market;
    private final WindowManager windows;

    public Gui(Plugin plugin, MarketService market, WindowManager windows) {
        this.plugin = plugin;
        this.market = market;
        this.windows = windows;
    }

    public MarketService market() {
        return market;
    }

    public WindowManager windows() {
        return windows;
    }

    public Plugin plugin() {
        return plugin;
    }

    public void openMain(Player player) {
        windows.open(player, new MainMenuWindow(this));
    }

    public void openBrowse(Player player, int page) {
        windows.open(player, new BrowseWindow(this, page));
    }

    public void openMine(Player player) {
        windows.open(player, new MyListingsWindow(this));
    }

    public void openTrades(Player player) {
        windows.open(player, new TradesWindow(this));
    }

    public void openDeliveries(Player player) {
        windows.open(player, new DeliveriesWindow(this));
    }

    public void openCreate(Player player, site.vinoff.market.core.ListingType type, String recipient) {
        windows.open(player, SelectionWindow.forCreating(this, type, recipient));
    }

    public void openOffer(Player player, long listingId) {
        windows.open(player, SelectionWindow.forOffering(this, listingId));
    }

    /**
     * Closes the window and then hands over whatever is waiting. Two ticks on purpose: the marketplace refuses to put
     * items into an inventory while the player is looking at a marketplace screen, because the client would show the
     * old contents.
     */
    public void closeAndDeliver(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.closeInventory();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                int handed = market.claimDeliveries(player.getUniqueId());
                int left = market.pendingCount(player.getUniqueId());
                if (handed > 0) {
                    player.sendMessage(Messages.good("Предметы у вас"));
                }
                if (left > 0) {
                    player.sendMessage(Messages.info("Ещё ждут: " + left + ". Освободите место и наберите /market deliveries"));
                }
            });
        });
    }

    /** Runs something that may refuse, and tells the player in their own language if it does. */
    public boolean run(Player player, Runnable action) {
        try {
            action.run();
            return true;
        } catch (MarketException refused) {
            player.sendMessage(Messages.of(refused.error(), refused.getMessage()));
            return false;
        }
    }
}
