package site.vinoff.market.bukkit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Logger;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.gui.Gui;
import site.vinoff.market.gui.GuiListener;
import site.vinoff.market.gui.Icons;
import site.vinoff.market.gui.WindowManager;
import site.vinoff.market.core.port.MarketClock;
import site.vinoff.market.http.ApiServer;
import site.vinoff.market.storage.Database;
import site.vinoff.market.storage.DeliveryRepository;
import site.vinoff.market.storage.MarketRepository;

/**
 * Wires the marketplace together: the database, the service that owns every item movement, the command players use and
 * the local API the website will use.
 *
 * <p>Nothing here decides anything about items. If this class fails to start, the plugin disables itself rather than
 * running half configured, because half a marketplace is how items go missing.
 */
public final class OurServerMarketPlugin extends JavaPlugin {

    private static final String CLEAN_SHUTDOWN = "clean_shutdown";

    private Database database;
    private ApiServer api;
    private WindowManager windows;
    private ChatPrompt prompts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Logger log = getLogger();
        try {
            Path file = getDataFolder().toPath().resolve(getConfig().getString("database.file", "market.db"));
            java.nio.file.Files.createDirectories(file.getParent());
            database = Database.open(file);
            log.info("Database ready at " + file.getFileName() + ", schema version " + database.schemaVersion());
        } catch (IOException | RuntimeException failure) {
            log.severe("The marketplace database could not be opened: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MarketRepository repository = new MarketRepository(database.bootId());
        DeliveryRepository deliveries = new DeliveryRepository();
        BukkitInventoryPort inventory = new BukkitInventoryPort(getServer(), log);
        MarketService market = new MarketService(database, repository, deliveries, inventory, MarketClock.system(), log);

        Icons.init(this);
        windows = new WindowManager(this, inventory, log);
        prompts = new ChatPrompt(this);
        Gui gui = new Gui(this, market, windows, prompts);

        PlayerListener listener = new PlayerListener(this, market, inventory, windows, log);
        inventory.setAuthmePresent(listener.hookAuthme());
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(new GuiListener(windows), this);
        getServer().getPluginManager().registerEvents(prompts, this);

        PluginCommand command = getCommand("market");
        if (command != null) {
            MarketCommand handler = new MarketCommand(market, gui);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        boolean cleanShutdown = database.read(connection -> deliveries.state(connection, CLEAN_SHUTDOWN)).map("true"::equals).orElse(false);
        database.inTransaction(connection -> {
            deliveries.putState(connection, CLEAN_SHUTDOWN, "false", Instant.now());
            return null;
        });
        MarketService.RecoveryReport report = market.recoverAtStartup();
        if (!cleanShutdown) {
            log.warning("The last run of the server did not stop cleanly; checking the marketplace.");
        }
        if (report.anything()) {
            log.warning("Recovery: " + report.escrowReturned() + " stack(s) returned to their owners, "
                    + report.handoversAwaitingLogin() + " handover(s) waiting for their player to log in.");
        }

        scheduleExpiry(market, log);
        startApi(market, deliveries, log);
        log.info("OurServerMarket is ready.");
    }

    /** Closes forgotten listings and returns their items, if the server asked for that in config.yml. */
    private void scheduleExpiry(MarketService market, Logger log) {
        int days = getConfig().getInt("listings.expire-after-days", 0);
        if (days <= 0) {
            return;
        }
        long everyTenMinutes = 20L * 60L * 10L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                int expired = market.expireListingsOlderThan(java.time.Duration.ofDays(days));
                if (expired > 0) {
                    log.info("Closed " + expired + " forgotten listing(s); the items are waiting for their owners.");
                }
            } catch (RuntimeException failure) {
                log.warning("The expiry sweep failed: " + failure.getMessage());
            }
        }, everyTenMinutes, everyTenMinutes);
        log.info("Listings are closed after " + days + " day(s) without a buyer.");
    }

    private void startApi(MarketService market, DeliveryRepository deliveries, Logger log) {
        if (!getConfig().getBoolean("api.enabled", true)) {
            log.info("The marketplace API is switched off in config.yml.");
            return;
        }
        String token = System.getenv("OUR_SERVER_MARKET_TOKEN");
        if (token == null || token.isBlank()) {
            token = getConfig().getString("api.token", "");
        }
        if (token == null || token.isBlank() || token.length() < 24) {
            log.warning("The marketplace API needs a token of at least 24 characters in config.yml or in "
                    + "OUR_SERVER_MARKET_TOKEN; the API stays off.");
            return;
        }
        api = new ApiServer(
                market, database, deliveries, token, getConfig().getInt("api.rate-limit-per-minute", 120), log);
        try {
            api.start(getConfig().getString("api.bind", "127.0.0.1"), getConfig().getInt("api.port", 8788));
        } catch (IOException failure) {
            log.severe("The marketplace API could not start: " + failure.getMessage());
            api = null;
        }
    }

    @Override
    public void onDisable() {
        if (windows != null) {
            // a window left open after the guard is gone would be an unguarded window
            windows.closeAll();
            windows = null;
        }
        if (prompts != null) {
            // an unanswered question would otherwise swallow the next thing that player says
            prompts.clear();
            prompts = null;
        }
        if (api != null) {
            api.stop();
            api = null;
        }
        if (database != null) {
            try {
                database.inTransaction(connection -> {
                    new DeliveryRepository().putState(connection, CLEAN_SHUTDOWN, "true", Instant.now());
                    return null;
                });
            } catch (RuntimeException failure) {
                getLogger().warning("Could not write the clean shutdown marker: " + failure.getMessage());
            }
            database.close();
            database = null;
        }
    }
}
