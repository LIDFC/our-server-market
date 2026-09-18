package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import site.vinoff.market.core.port.MarketClock;
import site.vinoff.market.storage.Database;
import site.vinoff.market.storage.DeliveryRepository;
import site.vinoff.market.storage.MarketRepository;
import site.vinoff.market.storage.StorageException;

/**
 * A marketplace on a real SQLite file, with an inventory the test controls. Restarting the server is modelled by
 * {@link #restart()}: the same file, a new boot id, a new service — which is exactly what recovery has to cope with.
 */
public final class MarketFixture implements AutoCloseable {

    public final Path databaseFile;
    public final FakeInventory inventory = new FakeInventory();
    public final UUID alice = UUID.nameUUIDFromBytes("OfflinePlayer:Alice".getBytes(StandardCharsets.UTF_8));
    public final UUID bob = UUID.nameUUIDFromBytes("OfflinePlayer:Bob".getBytes(StandardCharsets.UTF_8));
    public final UUID carol = UUID.nameUUIDFromBytes("OfflinePlayer:Carol".getBytes(StandardCharsets.UTF_8));

    private Database database;
    private MarketService service;

    public MarketFixture() {
        try {
            Path directory = Files.createTempDirectory("market-test");
            databaseFile = directory.resolve("market.db");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        start();
    }

    private void start() {
        database = Database.open(databaseFile);
        MarketRepository market = new MarketRepository(database.bootId());
        service = new MarketService(database, market, new DeliveryRepository(), inventory, MarketClock.system(), Logger.getLogger("market-test"));
        service.seePlayer(alice, "Alice");
        service.seePlayer(bob, "Bob");
        service.seePlayer(carol, "Carol");
    }

    /** Closes and opens the database again, as a server restart would. */
    public void restart() {
        database.close();
        start();
    }

    public MarketService service() {
        return service;
    }

    public Database database() {
        return database;
    }

    public static ItemBlob item(String name, int count) {
        return new ItemBlob(("item:" + name).getBytes(StandardCharsets.UTF_8), count, count + "x " + name);
    }

    /** Every item the marketplace knows about, and where the ledger says it is now. */
    public List<String> holders() {
        return database.read(connection -> {
            List<String> holders = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT m.item_uid, m.to_holder FROM item_movements m"
                            + " WHERE m.id = (SELECT MAX(id) FROM item_movements WHERE item_uid = m.item_uid) ORDER BY m.item_uid");
                    ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    holders.add(row.getString("item_uid") + " -> " + row.getString("to_holder"));
                }
            } catch (SQLException failure) {
                throw new StorageException("Could not read the ledger", failure);
            }
            return holders;
        });
    }

    /**
     * Checks the ledger tells one story: every movement starts where the previous one ended, and no item is in two
     * places. Called after anything interesting happens.
     */
    public void assertLedgerIsConsistent() {
        database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT item_uid, from_holder, to_holder, amount FROM item_movements ORDER BY item_uid, id");
                    ResultSet row = select.executeQuery()) {
                String currentItem = null;
                String currentHolder = null;
                while (row.next()) {
                    String itemUid = row.getString("item_uid");
                    String from = row.getString("from_holder");
                    String to = row.getString("to_holder");
                    if (!itemUid.equals(currentItem)) {
                        currentItem = itemUid;
                        currentHolder = from;
                    }
                    assertEquals(currentHolder, from, "item " + itemUid + " moved out of a place it was not in");
                    currentHolder = to;
                }
            } catch (SQLException failure) {
                throw new StorageException("Could not check the ledger", failure);
            }
            return null;
        });
    }

    /** How many stacks are recorded as held in escrow right now. */
    public int escrowCount() {
        return count("SELECT COUNT(*) FROM escrow_items WHERE state = 'HELD'");
    }

    public int deliveryCount(UUID player) {
        return database.read(connection -> new DeliveryRepository().pendingCount(connection, player));
    }

    public int count(String sql) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(sql);
                    ResultSet row = select.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            } catch (SQLException failure) {
                throw new StorageException("Could not count with " + sql, failure);
            }
        });
    }

    @Override
    public void close() {
        database.close();
    }
}
