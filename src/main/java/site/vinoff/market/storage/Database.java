package site.vinoff.market.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Function;

/**
 * The single SQLite connection the marketplace writes through, plus the pragmas and migrations that make the promises
 * in the ledger true.
 *
 * <p>One connection, used under the object monitor: SQLite has one writer anyway, and serialising here means a
 * transaction can never interleave with another. Writes are small (a few rows) and happen on deliberate player
 * actions, so holding the main thread for the length of a commit is the price of not losing items.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;
    private final String bootId = UUID.randomUUID().toString();
    private boolean inTransaction;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path file) {
        try {
            // the driver is loaded by the server from plugin.yml "libraries"; name it so a missing library fails loudly
            Class.forName("org.sqlite.JDBC");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            connection.setAutoCommit(true);
            Database database = new Database(connection);
            database.applyPragmas();
            database.migrate();
            return database;
        } catch (ClassNotFoundException missingDriver) {
            throw new StorageException("The SQLite driver is missing; Paper downloads it from plugin.yml libraries", missingDriver);
        } catch (SQLException failure) {
            throw new StorageException("Could not open " + file, failure);
        }
    }

    private void applyPragmas() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // WAL keeps readers going during a write; FULL is deliberate: NORMAL may lose the last commits on power
            // loss, and the last commit is exactly the one that says where a player's items went
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_size_limit = 16777216");
        }
        assertForeignKeys();
    }

    /** Foreign keys are a per connection setting and silently do nothing if the pragma did not take. */
    private void assertForeignKeys() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new StorageException("SQLite refused to enable foreign keys");
            }
        }
    }

    /**
     * Applies the pending steps. Statements inside a step are separated by a semicolon at the end of a line, so a
     * trigger body has to keep its inner semicolons on the same line as its END.
     */
    private void migrate() throws SQLException {
        int version = schemaVersion();
        while (version < Migrations.latestVersion()) {
            String step = Migrations.STEPS.get(version);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String part : step.split(";\\s*\\n")) {
                    if (!part.isBlank()) {
                        statement.execute(part);
                    }
                }
                // the value is a loop counter, not input
                statement.execute("PRAGMA user_version = " + (version + 1));
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw new StorageException("Migration to version " + (version + 1) + " failed", failure);
            } finally {
                connection.setAutoCommit(true);
            }
            version++;
        }
    }

    public int schemaVersion() {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException failure) {
            throw new StorageException("Could not read the schema version", failure);
        }
    }

    /** Identifier of this run of the server. Rows left behind by an older boot id are what recovery looks for. */
    public String bootId() {
        return bootId;
    }

    /**
     * Runs the work in one transaction and hands back its result. Nested calls are refused rather than silently
     * joining the outer transaction, because a "commit" that is really a no-op is how items go missing.
     */
    public synchronized <T> T inTransaction(Function<Connection, T> work) {
        if (inTransaction) {
            throw new StorageException("A transaction is already open on this connection");
        }
        inTransaction = true;
        try {
            connection.setAutoCommit(false);
            T result;
            try {
                result = work.apply(connection);
                connection.commit();
            } catch (RuntimeException | Error failure) {
                safeRollback();
                throw failure;
            }
            return result;
        } catch (SQLException failure) {
            safeRollback();
            throw new StorageException("Transaction failed", failure);
        } finally {
            inTransaction = false;
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // the connection is going away anyway
            }
        }
    }

    /** Read only work outside a transaction. */
    public synchronized <T> T read(Function<Connection, T> work) {
        return work.apply(connection);
    }

    private void safeRollback() {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            throw new StorageException("Rollback failed", failure);
        }
    }

    public synchronized void checkpoint() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException failure) {
            throw new StorageException("Checkpoint failed", failure);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (!connection.isClosed()) {
                checkpoint();
                connection.close();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not close the database", failure);
        }
    }
}
