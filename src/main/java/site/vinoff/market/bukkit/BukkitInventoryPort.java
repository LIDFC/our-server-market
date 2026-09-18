package site.vinoff.market.bukkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.port.InventoryPort;
import site.vinoff.market.storage.MarketRepository;

/**
 * The marketplace's hands. Everything here runs on the server thread, and every change to a player's inventory goes
 * through one of these four methods.
 *
 * <p>Two habits keep it honest: a change is always tried on a scratch copy first, so "it did not fit" is decided
 * before anything real moves, and {@link #save} really flushes the player file, which is the only way the database and
 * the world can agree after a crash.
 */
public final class BukkitInventoryPort implements InventoryPort {

    private static final int STORAGE_SLOTS = 36;

    private final Server server;
    private final Logger log;
    /** players who are logged in as far as AuthMe is concerned; empty means "no AuthMe, everybody counts" */
    private final Set<UUID> authenticated = new HashSet<>();
    private final Set<UUID> busyInGui = new HashSet<>();
    private boolean authmePresent;

    public BukkitInventoryPort(Server server, Logger log) {
        this.server = server;
        this.log = log;
    }

    public void setAuthmePresent(boolean present) {
        this.authmePresent = present;
    }

    public void markAuthenticated(UUID player) {
        authenticated.add(player);
    }

    public void forget(UUID player) {
        authenticated.remove(player);
        busyInGui.remove(player);
    }

    public void setBusyInGui(UUID player, boolean busy) {
        if (busy) {
            busyInGui.add(player);
        } else {
            busyInGui.remove(player);
        }
    }

    private Optional<Player> online(UUID uuid) {
        Player player = server.getPlayer(uuid);
        return player != null && player.isOnline() && player.isValid() ? Optional.of(player) : Optional.empty();
    }

    @Override
    public Optional<String> digest(UUID player) {
        return online(player).map(online -> {
            PlayerInventory inventory = online.getInventory();
            List<ItemStack> everything = new ArrayList<>();
            // the cursor is deliberately left out: it is not part of the saved player file
            everything.addAll(java.util.Arrays.asList(inventory.getStorageContents()));
            everything.addAll(java.util.Arrays.asList(inventory.getArmorContents()));
            everything.add(inventory.getItemInOffHand());
            byte[] bytes = ItemStack.serializeItemsAsBytes(everything);
            return MarketRepository.sha256(bytes);
        });
    }

    @Override
    public boolean readyForItems(UUID player) {
        return online(player)
                .map(online -> !online.isDead()
                        && !busyInGui.contains(player)
                        && (!authmePresent || authenticated.contains(player)))
                .orElse(false);
    }

    @Override
    public boolean fits(UUID player, List<ItemBlob> items) {
        return online(player).map(online -> simulate(online, items).isEmpty()).orElse(false);
    }

    /** Runs the same merge the server would run, on a copy, and returns what would not fit. */
    private List<ItemStack> simulate(Player player, List<ItemBlob> items) {
        Inventory scratch = Bukkit.createInventory(null, STORAGE_SLOTS);
        scratch.setContents(player.getInventory().getStorageContents());
        List<ItemStack> stacks = decode(items);
        return new ArrayList<>(scratch.addItem(stacks.toArray(new ItemStack[0])).values());
    }

    @Override
    public boolean removeExactly(UUID player, List<ItemBlob> items) {
        Optional<Player> found = online(player);
        if (found.isEmpty()) {
            return false;
        }
        Player online = found.get();
        List<ItemStack> stacks = decode(items);

        // check on a copy first: removeItem takes what it finds and leaves the rest, which would be half a removal
        Inventory scratch = Bukkit.createInventory(null, STORAGE_SLOTS);
        scratch.setContents(online.getInventory().getStorageContents());
        if (!scratch.removeItem(stacks.toArray(new ItemStack[0])).isEmpty()) {
            return false;
        }

        ItemStack[] before = copyOf(online.getInventory().getStorageContents());
        if (!online.getInventory().removeItem(stacks.toArray(new ItemStack[0])).isEmpty()) {
            // should not happen after the simulation, so put everything back exactly as it was
            online.getInventory().setStorageContents(before);
            log.warning("Removing items from " + player + " did not match the simulation; the inventory was restored");
            return false;
        }
        online.updateInventory();
        return true;
    }

    @Override
    public boolean addAll(UUID player, List<ItemBlob> items) {
        Optional<Player> found = online(player);
        if (found.isEmpty()) {
            return false;
        }
        Player online = found.get();
        if (!simulate(online, items).isEmpty()) {
            return false;
        }
        List<ItemStack> stacks = decode(items);
        ItemStack[] before = copyOf(online.getInventory().getStorageContents());
        if (!online.getInventory().addItem(stacks.toArray(new ItemStack[0])).isEmpty()) {
            online.getInventory().setStorageContents(before);
            log.warning("Handing items to " + player + " did not match the simulation; the inventory was restored");
            return false;
        }
        online.updateInventory();
        return true;
    }

    @Override
    public boolean save(UUID player) {
        Optional<Player> found = online(player);
        if (found.isEmpty()) {
            return false;
        }
        try {
            // the durability barrier: after this the player file on disk matches what the database believes
            found.get().saveData();
            return true;
        } catch (RuntimeException failure) {
            log.warning("Could not flush the player file of " + player + ": " + failure.getMessage());
            return false;
        }
    }

    @Override
    public int dataVersion() {
        return PaperItemCodec.serverDataVersion();
    }

    private static List<ItemStack> decode(List<ItemBlob> items) {
        List<ItemStack> stacks = new ArrayList<>(items.size());
        for (ItemBlob blob : items) {
            stacks.add(PaperItemCodec.decode(blob));
        }
        return stacks;
    }

    private static ItemStack[] copyOf(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    /** Used by the plugin when it needs a stable id for a player it has only seen by name. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
