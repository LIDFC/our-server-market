package site.vinoff.market.storage;

import static site.vinoff.market.storage.MarketRepository.generatedId;
import static site.vinoff.market.storage.MarketRepository.nullableLong;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import site.vinoff.market.core.chest.ChestKind;
import site.vinoff.market.core.chest.ChestState;
import site.vinoff.market.core.model.BoundChest;
import site.vinoff.market.core.model.ChestIntent;

/**
 * Bound chests and the chest half of an intent.
 *
 * <p>Two of these methods carry the safety of the whole feature. {@link #reserveSeq} hands out journal numbers one at
 * a time inside the caller's transaction, so two requests can never be given the same one. {@link #markApplied}
 * records that a number reached the world, and the gap between it and the number stamped on the block is exactly what
 * tells a restarted server whether the world rolled back.
 */
public final class ChestRepository {

    private static final String COLUMNS =
            "id, owner_uuid, world_uuid, x, y, z, kind, pair_x, pair_y, pair_z, size, state, next_seq, applied_seq,"
                    + " verified_boot_id, bound_at, updated_at, released_at, released_reason";

    // bindings ------------------------------------------------------------------------------------------------------

    public long bind(
            Connection connection,
            UUID owner,
            UUID world,
            int x,
            int y,
            int z,
            ChestKind kind,
            int[] pair,
            Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO bound_chests (owner_uuid, world_uuid, x, y, z, kind, pair_x, pair_y, pair_z, size, state,"
                        + " bound_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOUND', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, owner.toString());
            insert.setString(2, world.toString());
            insert.setInt(3, x);
            insert.setInt(4, y);
            insert.setInt(5, z);
            insert.setString(6, kind.name());
            setNullableInt(insert, 7, pair == null ? null : pair[0]);
            setNullableInt(insert, 8, pair == null ? null : pair[1]);
            setNullableInt(insert, 9, pair == null ? null : pair[2]);
            insert.setInt(10, kind.size());
            insert.setString(11, now.toString());
            insert.setString(12, now.toString());
            insert.executeUpdate();
            return generatedId(insert);
        } catch (SQLException failure) {
            throw new StorageException("Could not bind a chest", failure);
        }
    }

    public Optional<BoundChest> byId(Connection connection, long id) {
        return one(connection, "SELECT " + COLUMNS + " FROM bound_chests WHERE id = ?", statement -> statement.setLong(1, id), id);
    }

    public Optional<BoundChest> byOwner(Connection connection, UUID owner) {
        return one(
                connection,
                "SELECT " + COLUMNS + " FROM bound_chests WHERE owner_uuid = ? AND state = 'BOUND'",
                statement -> statement.setString(1, owner.toString()),
                owner);
    }

    /**
     * The binding a block belongs to, whether it is the recorded block or the other half of a double chest. Both are
     * checked because breaking either half has to be noticed, and a plain lookup on the main coordinates would miss
     * half of every double chest.
     */
    public Optional<BoundChest> byBlock(Connection connection, UUID world, int x, int y, int z) {
        return one(
                connection,
                "SELECT " + COLUMNS + " FROM bound_chests WHERE world_uuid = ? AND state = 'BOUND'"
                        + " AND ((x = ? AND y = ? AND z = ?) OR (pair_x = ? AND pair_y = ? AND pair_z = ?))",
                statement -> {
                    statement.setString(1, world.toString());
                    for (int index : new int[] {2, 5}) {
                        statement.setInt(index, x);
                        statement.setInt(index + 1, y);
                        statement.setInt(index + 2, z);
                    }
                },
                world);
    }

    public List<BoundChest> bound(Connection connection) {
        return many(connection, "SELECT " + COLUMNS + " FROM bound_chests WHERE state = 'BOUND' ORDER BY id", statement -> {});
    }

    /** Bound chests this boot has not yet agreed with. Nothing may be taken from one until it has been. */
    public List<BoundChest> settling(Connection connection, String bootId) {
        return many(
                connection,
                "SELECT " + COLUMNS + " FROM bound_chests WHERE state = 'BOUND'"
                        + " AND (verified_boot_id IS NULL OR verified_boot_id <> ?) ORDER BY id",
                statement -> statement.setString(1, bootId));
    }

    public void release(Connection connection, long id, ChestState state, String reason, Instant now) {
        update(
                connection,
                "UPDATE bound_chests SET state = ?, released_at = ?, released_reason = ?, updated_at = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, state.name());
                    statement.setString(2, now.toString());
                    statement.setString(3, reason);
                    statement.setString(4, now.toString());
                    statement.setLong(5, id);
                });
    }

    /**
     * Takes the next journal number. Inside the caller's transaction, so two requests for the same chest cannot be
     * handed the same number even if they arrive at the same moment.
     */
    public long reserveSeq(Connection connection, long id) {
        long next = byId(connection, id)
                .orElseThrow(() -> new StorageException("Chest " + id + " disappeared while reserving a number"))
                .nextSeq();
        update(
                connection,
                "UPDATE bound_chests SET next_seq = ?, updated_at = ? WHERE id = ? AND next_seq = ?",
                statement -> {
                    statement.setLong(1, next + 1);
                    statement.setString(2, Instant.now().toString());
                    statement.setLong(3, id);
                    statement.setLong(4, next);
                });
        return next;
    }

    /** Records that a journal number reached the world. */
    public void markApplied(Connection connection, long id, long seq, Instant now) {
        update(
                connection,
                "UPDATE bound_chests SET applied_seq = ?, updated_at = ? WHERE id = ?",
                statement -> {
                    statement.setLong(1, seq);
                    statement.setString(2, now.toString());
                    statement.setLong(3, id);
                });
    }

    public void markVerified(Connection connection, long id, String bootId, Instant now) {
        update(
                connection,
                "UPDATE bound_chests SET verified_boot_id = ?, updated_at = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, bootId);
                    statement.setString(2, now.toString());
                    statement.setLong(3, id);
                });
    }

    // chest intents -------------------------------------------------------------------------------------------------

    public void insertIntent(
            Connection connection,
            String txId,
            long chestId,
            long seq,
            String preDigest,
            String postDigest,
            String plan,
            Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO chest_intents (tx_id, chest_id, seq, pre_digest, post_digest, plan, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, txId);
            insert.setLong(2, chestId);
            insert.setLong(3, seq);
            insert.setString(4, preDigest);
            insert.setString(5, postDigest);
            insert.setString(6, plan);
            insert.setString(7, now.toString());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not record a chest intent", failure);
        }
    }

    public void linkListing(Connection connection, String txId, long listingId) {
        update(connection, "UPDATE chest_intents SET listing_id = ? WHERE tx_id = ?", statement -> {
            statement.setLong(1, listingId);
            statement.setString(2, txId);
        });
    }

    public Optional<ChestIntent> intent(Connection connection, String txId) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT tx_id, chest_id, seq, pre_digest, post_digest, plan, listing_id, created_at"
                        + " FROM chest_intents WHERE tx_id = ?")) {
            select.setString(1, txId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readIntent(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read chest intent " + txId, failure);
        }
    }

    /**
     * Chest intents of one chest that are still open, oldest first. Joined against {@code intents} because the state
     * machine lives there — this table only carries the chest specific fields.
     */
    public List<ChestIntent> unresolved(Connection connection, long chestId) {
        List<ChestIntent> intents = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT c.tx_id, c.chest_id, c.seq, c.pre_digest, c.post_digest, c.plan, c.listing_id, c.created_at"
                        + " FROM chest_intents c JOIN intents i ON i.tx_id = c.tx_id"
                        + " WHERE c.chest_id = ? AND i.state IN ('INTENT','APPLIED') ORDER BY c.seq")) {
            select.setLong(1, chestId);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    intents.add(readIntent(row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the open chest intents", failure);
        }
        return intents;
    }

    /** The chest intent that carried one journal number, however it ended. Used when the world turns out to be behind. */
    public Optional<ChestIntent> bySeq(Connection connection, long chestId, long seq) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT tx_id, chest_id, seq, pre_digest, post_digest, plan, listing_id, created_at"
                        + " FROM chest_intents WHERE chest_id = ? AND seq = ?")) {
            select.setLong(1, chestId);
            select.setLong(2, seq);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readIntent(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read chest intent " + chestId + "/" + seq, failure);
        }
    }

    public boolean hasOpenIntent(Connection connection, long chestId) {
        return !unresolved(connection, chestId).isEmpty();
    }

    // plumbing ------------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private Optional<BoundChest> one(Connection connection, String sql, Binder binder, Object what) {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            binder.bind(select);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the bound chest of " + what, failure);
        }
    }

    private List<BoundChest> many(Connection connection, String sql, Binder binder) {
        List<BoundChest> chests = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            binder.bind(select);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    chests.add(read(row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not list bound chests", failure);
        }
        return chests;
    }

    private void update(Connection connection, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not update a bound chest", failure);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static BoundChest read(ResultSet row) throws SQLException {
        String released = row.getString("released_at");
        return new BoundChest(
                row.getLong("id"),
                UUID.fromString(row.getString("owner_uuid")),
                UUID.fromString(row.getString("world_uuid")),
                row.getInt("x"),
                row.getInt("y"),
                row.getInt("z"),
                ChestKind.valueOf(row.getString("kind")),
                nullableInt(row, "pair_x"),
                nullableInt(row, "pair_y"),
                nullableInt(row, "pair_z"),
                row.getInt("size"),
                ChestState.valueOf(row.getString("state")),
                row.getLong("next_seq"),
                row.getLong("applied_seq"),
                row.getString("verified_boot_id"),
                Instant.parse(row.getString("bound_at")),
                Instant.parse(row.getString("updated_at")),
                released == null ? null : Instant.parse(released),
                row.getString("released_reason"));
    }

    private static ChestIntent readIntent(ResultSet row) throws SQLException {
        return new ChestIntent(
                row.getString("tx_id"),
                row.getLong("chest_id"),
                row.getLong("seq"),
                row.getString("pre_digest"),
                row.getString("post_digest"),
                row.getString("plan"),
                nullableLong(row, "listing_id"),
                Instant.parse(row.getString("created_at")));
    }
}
