package site.vinoff.market.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The schema steps, against a database that already has rows in it.
 *
 * <p>This matters more than it looks: the marketplace on the server is already running with schema 1 and real items
 * in it. A step that only works on an empty file would be found out in production, once.
 */
class MigrationTest {

    @Test
    @DisplayName("a live schema 1 database moves to 2 and keeps what was in it")
    void upgradesInPlace(@TempDir Path directory) throws SQLException {
        Path file = directory.resolve("market.db");

        // a database as it is on the server today: step 1 only, with an intent already recorded
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (Statement statement = connection.createStatement()) {
                for (String part : Migrations.STEPS.get(0).split(";\s*\n")) {
                    if (!part.isBlank()) {
                        statement.execute(part);
                    }
                }
                statement.execute("PRAGMA user_version = 1");
                statement.execute(
                        "INSERT INTO intents (tx_id, boot_id, op, player_uuid, state, pre_digest, data_version, created_at)"
                                + " VALUES ('tx-old', 'boot-old', 'LIST_OFFER', 'uuid-old', 'INTENT', 'digest-old', 1, '2026-01-01T00:00:00Z')");
            }
        }

        try (Database database = Database.open(file)) {
            assertEquals(Migrations.latestVersion(), database.schemaVersion());
            assertEquals(2, database.schemaVersion(), "step 2 adds bound chests");

            database.read(connection -> {
                try (Statement statement = connection.createStatement()) {
                    // the intent that was there before the upgrade must now count as a player intent
                    try (ResultSet row = statement.executeQuery("SELECT source FROM intents WHERE tx_id = 'tx-old'")) {
                        assertTrue(row.next(), "the existing intent survived the upgrade");
                        assertEquals("PLAYER", row.getString("source"), "everything that existed before was a player intent");
                    }
                    try (ResultSet row = statement.executeQuery("SELECT COUNT(*) AS n FROM bound_chests")) {
                        row.next();
                        assertEquals(0, row.getInt("n"));
                    }
                    try (ResultSet row = statement.executeQuery("SELECT COUNT(*) AS n FROM chest_intents")) {
                        row.next();
                        assertEquals(0, row.getInt("n"));
                    }
                } catch (SQLException failure) {
                    throw new StorageException("query failed", failure);
                }
                return null;
            });
        }
    }

    @Test
    @DisplayName("a fresh database arrives at the same schema as an upgraded one")
    void freshMatchesUpgraded(@TempDir Path directory) {
        try (Database database = Database.open(directory.resolve("fresh.db"))) {
            assertEquals(Migrations.latestVersion(), database.schemaVersion());
            assertTrue(Files.exists(directory.resolve("fresh.db")));
        }
    }

    @Test
    @DisplayName("opening an already current database changes nothing")
    void secondOpenIsQuiet(@TempDir Path directory) {
        Path file = directory.resolve("twice.db");
        try (Database first = Database.open(file)) {
            assertEquals(Migrations.latestVersion(), first.schemaVersion());
        }
        try (Database again = Database.open(file)) {
            assertEquals(Migrations.latestVersion(), again.schemaVersion());
        }
    }
}
