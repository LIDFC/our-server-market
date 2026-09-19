package site.vinoff.market.storage;

import static site.vinoff.market.storage.MarketRepository.generatedId;
import static site.vinoff.market.storage.MarketRepository.nullableLong;
import static site.vinoff.market.storage.MarketRepository.setNullableLong;
import static site.vinoff.market.storage.MarketRepository.setNullableString;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import site.vinoff.market.core.DeliveryReason;
import site.vinoff.market.core.DeliveryState;
import site.vinoff.market.core.IntentSource;
import site.vinoff.market.core.IntentState;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.model.Intent;
import site.vinoff.market.core.model.PendingDelivery;
import site.vinoff.market.core.model.StoredItem;

/**
 * Pending deliveries, intent records, the marketplace's own key value state and the answers already given to the API.
 *
 * <p>Everything the marketplace hands back to a player goes through {@code pending_deliveries}. There is no "gave it
 * directly" shortcut: one path in means one path to check after a crash.
 */
public final class DeliveryRepository {

    // deliveries ---------------------------------------------------------------------------------------------------

    /**
     * Queues items for a player. {@code sourceEscrowItemId} is unique in the schema, so even a bug that runs a
     * completion twice cannot create a second delivery for the same escrow row.
     */
    public long insertDelivery(
            Connection connection, UUID player, String itemUid, DeliveryReason reason, Long sourceEscrowItemId, Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO pending_deliveries (player_uuid, item_uid, reason, state, source_escrow_item_id, created_at)"
                        + " VALUES (?, ?, ?, 'PENDING', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, player.toString());
            insert.setString(2, itemUid);
            insert.setString(3, reason.name());
            setNullableLong(insert, 4, sourceEscrowItemId);
            insert.setString(5, now.toString());
            insert.executeUpdate();
            return generatedId(insert);
        } catch (SQLException failure) {
            throw new StorageException("Could not queue a delivery for " + player, failure);
        }
    }

    public List<PendingDelivery> deliveries(Connection connection, UUID player, DeliveryState state, int limit) {
        List<PendingDelivery> deliveries = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT d.id, d.player_uuid, d.reason, d.state, d.source_escrow_item_id, d.claim_tx, d.claim_boot_id, d.attempts,"
                        + " d.created_at, d.claimed_at, i.item_uid, i.blob, i.data_version, i.sha256, i.amount, i.summary"
                        + " FROM pending_deliveries d JOIN items i ON i.item_uid = d.item_uid WHERE d.player_uuid = ?");
        if (state != null) {
            sql.append(" AND d.state = ?");
        }
        sql.append(" ORDER BY d.id LIMIT ?");
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            int index = 1;
            select.setString(index++, player.toString());
            if (state != null) {
                select.setString(index++, state.name());
            }
            select.setInt(index, limit);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    deliveries.add(readDelivery(row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the deliveries of " + player, failure);
        }
        return deliveries;
    }

    public Optional<PendingDelivery> delivery(Connection connection, long id) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT d.id, d.player_uuid, d.reason, d.state, d.source_escrow_item_id, d.claim_tx, d.claim_boot_id, d.attempts,"
                        + " d.created_at, d.claimed_at, i.item_uid, i.blob, i.data_version, i.sha256, i.amount, i.summary"
                        + " FROM pending_deliveries d JOIN items i ON i.item_uid = d.item_uid WHERE d.id = ?")) {
            select.setLong(1, id);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readDelivery(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read delivery " + id, failure);
        }
    }

    /** Marks a delivery as being handed over right now. Written and committed before the inventory is touched. */
    public boolean markClaiming(Connection connection, long id, String txId, String bootId, Instant now) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE pending_deliveries SET state = 'CLAIMING', claim_tx = ?, claim_boot_id = ?, attempts = attempts + 1,"
                        + " claimed_at = ? WHERE id = ? AND state = 'PENDING'")) {
            update.setString(1, txId);
            update.setString(2, bootId);
            update.setString(3, now.toString());
            update.setLong(4, id);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not start delivery " + id, failure);
        }
    }

    public boolean markClaimed(Connection connection, long id, String txId, Instant now) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE pending_deliveries SET state = 'CLAIMED', claimed_at = ? WHERE id = ? AND state = 'CLAIMING' AND claim_tx = ?")) {
            update.setString(1, now.toString());
            update.setLong(2, id);
            update.setString(3, txId);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not finish delivery " + id, failure);
        }
    }

    /** Puts a delivery back in the queue after a handover that did not go through. */
    public boolean revertClaiming(Connection connection, long id, String txId) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE pending_deliveries SET state = 'PENDING', claim_tx = NULL, claim_boot_id = NULL, claimed_at = NULL"
                        + " WHERE id = ? AND state = 'CLAIMING' AND claim_tx = ?")) {
            update.setLong(1, id);
            update.setString(2, txId);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not put delivery " + id + " back in the queue", failure);
        }
    }

    /** Deliveries that were being handed over when an earlier run of the server stopped. */
    public List<PendingDelivery> claimingFromOtherBoots(Connection connection, String currentBootId) {
        List<PendingDelivery> deliveries = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT d.id, d.player_uuid, d.reason, d.state, d.source_escrow_item_id, d.claim_tx, d.claim_boot_id, d.attempts,"
                        + " d.created_at, d.claimed_at, i.item_uid, i.blob, i.data_version, i.sha256, i.amount, i.summary"
                        + " FROM pending_deliveries d JOIN items i ON i.item_uid = d.item_uid"
                        + " WHERE d.state = 'CLAIMING' AND (d.claim_boot_id IS NULL OR d.claim_boot_id <> ?)")) {
            select.setString(1, currentBootId);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    deliveries.add(readDelivery(row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not look for interrupted deliveries", failure);
        }
        return deliveries;
    }

    public int pendingCount(Connection connection, UUID player) {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT COUNT(*) FROM pending_deliveries WHERE player_uuid = ? AND state <> 'CLAIMED'")) {
            select.setString(1, player.toString());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not count the deliveries of " + player, failure);
        }
    }

    private PendingDelivery readDelivery(ResultSet row) throws SQLException {
        ItemBlob blob = new ItemBlob(row.getBytes("blob"), row.getInt("amount"), row.getString("summary"));
        StoredItem item = new StoredItem(row.getString("item_uid"), blob, row.getInt("data_version"), row.getString("sha256"));
        String claimedAt = row.getString("claimed_at");
        return new PendingDelivery(
                row.getLong("id"),
                UUID.fromString(row.getString("player_uuid")),
                item,
                DeliveryReason.valueOf(row.getString("reason")),
                DeliveryState.valueOf(row.getString("state")),
                nullableLong(row, "source_escrow_item_id"),
                row.getString("claim_tx"),
                row.getString("claim_boot_id"),
                row.getInt("attempts"),
                Instant.parse(row.getString("created_at")),
                claimedAt == null ? null : Instant.parse(claimedAt));
    }

    // intents ------------------------------------------------------------------------------------------------------

    /**
     * Records an intent. {@code source} says what the fingerprint in {@code preDigest} is of — a player's inventory or
     * a bound chest — and there is deliberately no overload that defaults it: the two are resolved by comparing
     * against completely different things, and picking the wrong one hands out items that are still in the chest.
     */
    public void insertIntent(
            Connection connection,
            String txId,
            String bootId,
            IntentSource source,
            String op,
            UUID player,
            Long listingId,
            Long tradeId,
            String preDigest,
            int dataVersion,
            String detail,
            Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO intents (tx_id, boot_id, source, op, player_uuid, listing_id, trade_id, state, pre_digest, data_version,"
                        + " detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'INTENT', ?, ?, ?, ?)")) {
            insert.setString(1, txId);
            insert.setString(2, bootId);
            insert.setString(3, source.name());
            insert.setString(4, op);
            insert.setString(5, player.toString());
            setNullableLong(insert, 6, listingId);
            setNullableLong(insert, 7, tradeId);
            insert.setString(8, preDigest);
            insert.setInt(9, dataVersion);
            setNullableString(insert, 10, detail);
            insert.setString(11, now.toString());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not write an intent record", failure);
        }
    }

    public boolean transitionIntent(Connection connection, String txId, IntentState from, IntentState to, Instant now) {
        try (PreparedStatement update =
                connection.prepareStatement("UPDATE intents SET state = ?, resolved_at = ? WHERE tx_id = ? AND state = ?")) {
            update.setString(1, to.name());
            update.setString(2, to == IntentState.INTENT ? null : now.toString());
            update.setString(3, txId);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not move intent " + txId + " from " + from + " to " + to, failure);
        }
    }

    /** Intents left open by an earlier run: these are the ones whose inventory digest has to be checked at login. */
    /** One intent by its transaction id, whatever its state. */
    public Optional<Intent> intent(Connection connection, String txId) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT tx_id, boot_id, op, player_uuid, listing_id, trade_id, state, pre_digest, data_version, detail,"
                        + " created_at, resolved_at FROM intents WHERE tx_id = ?")) {
            select.setString(1, txId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readIntent(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read intent " + txId, failure);
        }
    }

    private static Intent readIntent(ResultSet row) throws SQLException {
        String resolved = row.getString("resolved_at");
        return new Intent(
                row.getString("tx_id"),
                row.getString("boot_id"),
                row.getString("op"),
                UUID.fromString(row.getString("player_uuid")),
                nullableLong(row, "listing_id"),
                nullableLong(row, "trade_id"),
                IntentState.valueOf(row.getString("state")),
                row.getString("pre_digest"),
                row.getInt("data_version"),
                row.getString("detail"),
                Instant.parse(row.getString("created_at")),
                resolved == null ? null : Instant.parse(resolved));
    }

    /**
     * Intents left over from an earlier boot, for login recovery.
     *
     * <p>Only {@code source = 'PLAYER'}, and that filter is load bearing. A chest intent stores the fingerprint of a
     * chest; the caller compares what it gets back against the player's <em>inventory</em>, which would never match,
     * so every chest intent would look like "the removal went through" and its items would be handed to the owner
     * while they are still sitting in the chest. That is a duplication, triggered by nothing more exotic than logging
     * in after a crash.
     */
    public List<Intent> unresolvedIntents(Connection connection, UUID player, String currentBootId) {
        List<Intent> intents = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT tx_id, boot_id, op, player_uuid, listing_id, trade_id, state, pre_digest, data_version, detail, created_at,"
                        + " resolved_at FROM intents WHERE source = 'PLAYER' AND state IN ('INTENT','APPLIED')"
                        + " AND boot_id <> ?");
        if (player != null) {
            sql.append(" AND player_uuid = ?");
        }
        sql.append(" ORDER BY created_at");
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            select.setString(1, currentBootId);
            if (player != null) {
                select.setString(2, player.toString());
            }
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    intents.add(readIntent(row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the unresolved intents", failure);
        }
        return intents;
    }

    // server state and API answers ---------------------------------------------------------------------------------

    public void putState(Connection connection, String key, String value, Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO server_state (key, value, updated_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at")) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.setString(3, now.toString());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not store the state value " + key, failure);
        }
    }

    public Optional<String> state(Connection connection, String key) {
        try (PreparedStatement select = connection.prepareStatement("SELECT value FROM server_state WHERE key = ?")) {
            select.setString(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the state value " + key, failure);
        }
    }

    /** The answer already given to this idempotency key, if the website is repeating a request. */
    public Optional<StoredResponse> apiResponse(Connection connection, String idempotencyKey, String endpoint) {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT status, response, endpoint FROM api_requests WHERE idempotency_key = ?")) {
            select.setString(1, idempotencyKey);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String storedEndpoint = row.getString("endpoint");
                if (!storedEndpoint.equals(endpoint)) {
                    // the same key for a different action is a client bug, and answering the old body would be worse
                    throw new StorageException("Idempotency key reused for a different endpoint");
                }
                return Optional.of(new StoredResponse(row.getInt("status"), row.getString("response")));
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read a stored API answer", failure);
        }
    }

    public void storeApiResponse(Connection connection, String idempotencyKey, String endpoint, int status, String response, Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO api_requests (idempotency_key, endpoint, status, response, created_at) VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT(idempotency_key) DO NOTHING")) {
            insert.setString(1, idempotencyKey);
            insert.setString(2, endpoint);
            insert.setInt(3, status);
            insert.setString(4, response);
            insert.setString(5, now.toString());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not store an API answer", failure);
        }
    }

    /** An answer the API already gave, replayed for a repeated request. */
    public record StoredResponse(int status, String body) {}
}
