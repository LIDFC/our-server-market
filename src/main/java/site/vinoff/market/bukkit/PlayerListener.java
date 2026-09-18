package site.vinoff.market.bukkit;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import site.vinoff.market.core.MarketService;

/**
 * Follows players in and out.
 *
 * <p>The moment that matters is when a player may be given items. With AuthMe installed that is not when they join:
 * AuthMe empties the inventory until the password is entered and puts it back afterwards, so anything handed over or
 * any fingerprint taken at join would be wrong. The plugin therefore waits for AuthMe's own login event, which it
 * subscribes to by name so AuthMe does not become a build dependency.
 */
public final class PlayerListener implements Listener {

    private final Plugin plugin;
    private final MarketService market;
    private final BukkitInventoryPort inventory;
    private final Logger log;

    public PlayerListener(Plugin plugin, MarketService market, BukkitInventoryPort inventory, Logger log) {
        this.plugin = plugin;
        this.market = market;
        this.inventory = inventory;
        this.log = log;
    }

    /** Subscribes to AuthMe's login event if AuthMe is there. Returns whether it was found. */
    @SuppressWarnings("unchecked")
    public boolean hookAuthme() {
        Class<?> loginEvent;
        try {
            loginEvent = Class.forName("fr.xephi.authme.events.LoginEvent");
        } catch (ClassNotFoundException noAuthme) {
            return false;
        }
        if (!Event.class.isAssignableFrom(loginEvent)) {
            return false;
        }
        plugin.getServer()
                .getPluginManager()
                .registerEvent(
                        (Class<? extends Event>) loginEvent,
                        this,
                        EventPriority.MONITOR,
                        (listener, event) -> onAuthmeLogin(event),
                        plugin,
                        true);
        log.info("AuthMe found: items are only handed over after a player has logged in.");
        return true;
    }

    private void onAuthmeLogin(Event event) {
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object player = getPlayer.invoke(event);
            if (player instanceof Player online) {
                welcome(online);
            }
        } catch (ReflectiveOperationException | RuntimeException unexpected) {
            log.warning("Could not read the player out of AuthMe's login event: " + unexpected);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        market.seePlayer(player.getUniqueId(), player.getName());
        if (!authmeInstalled()) {
            welcome(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        inventory.forget(event.getPlayer().getUniqueId());
    }

    /** Finishes whatever an earlier run of the server left open for this player, then tells them what is waiting. */
    private void welcome(Player player) {
        UUID uuid = player.getUniqueId();
        inventory.markAuthenticated(uuid);
        // a tick later: the inventory is settled and nothing else is writing to it
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                market.recoverPlayer(uuid);
            } catch (RuntimeException failure) {
                log.warning("Could not finish an interrupted marketplace operation for " + player.getName() + ": " + failure);
            }
            int waiting = market.pendingCount(uuid);
            if (waiting > 0) {
                player.sendMessage(Messages.info("Вас ждут предметы с рынка: " + waiting));
                player.sendMessage(Messages.hint("/market deliveries — забрать"));
            }
        }, 20L);
    }

    private boolean authmeInstalled() {
        return plugin.getServer().getPluginManager().isPluginEnabled("AuthMe");
    }
}
