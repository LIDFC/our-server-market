package site.vinoff.market.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import site.vinoff.market.core.EscrowState;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.ListingState;
import site.vinoff.market.core.ListingType;
import site.vinoff.market.core.TradeState;
import site.vinoff.market.core.model.EscrowItem;
import site.vinoff.market.core.model.Holder;
import site.vinoff.market.core.model.Identity;
import site.vinoff.market.core.model.ItemRole;
import site.vinoff.market.core.model.Listing;
import site.vinoff.market.core.model.ListingItem;
import site.vinoff.market.core.model.MarketEventRecord;
import site.vinoff.market.core.model.StoredItem;
import site.vinoff.market.core.model.Trade;
import site.vinoff.market.core.model.TradeParty;

/**
 * Every statement about identities, items, listings, trades and escrow. The caller always passes the connection, so a
 * whole operation is one transaction decided one level up, never here.
 *
 * <p>State changes are guarded updates: {@code ... WHERE id = ? AND state = ?} returning a row count. A caller that
 * gets {@code false} lost a race or is repeating itself, and must not touch any items.
 */
public final class MarketRepository {

    private final String bootId;

    public MarketRepository(String bootId) {
        this.bootId = bootId;
    }

    // identities ---------------------------------------------------------------------------------------------------

    /** Records that this UUID was seen under this exact name, and returns what the marketplace knows about it. */
    public Identity touchIdentity(Connection connection, UUID uuid, String nameExact, Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO identities (uuid, account_id, name_exact, name_lower, first_seen, last_seen)"
                        + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name_exact = excluded.name_exact,"
                        + " name_lower = excluded.name_lower, last_seen = excluded.last_seen")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, uuid.toString());
            insert.setString(3, nameExact);
            insert.setString(4, nameExact.toLowerCase(java.util.Locale.ROOT));
            insert.setString(5, now.toString());
            insert.setString(6, now.toString());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not record the identity of " + uuid, failure);
        }
        return identity(connection, uuid).orElseThrow(() -> new StorageException("Identity vanished right after it was written"));
    }

    public Optional<Identity> identity(Connection connection, UUID uuid) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT uuid, account_id, name_exact, name_lower, first_seen, last_seen, frozen_reason FROM identities WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readIdentity(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the identity of " + uuid, failure);
        }
    }

    public Optional<Identity> identityByName(Connection connection, String name) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT uuid, account_id, name_exact, name_lower, first_seen, last_seen, frozen_reason FROM identities"
                        + " WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
            select.setString(1, name.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readIdentity(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not look up the player " + name, failure);
        }
    }

    public void freezeIdentity(Connection connection, UUID uuid, String reason, Instant now) {
        try (PreparedStatement update = connection.prepareStatement("UPDATE identities SET frozen_reason = ?, last_seen = ? WHERE uuid = ?")) {
            update.setString(1, reason);
            update.setString(2, now.toString());
            update.setString(3, uuid.toString());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not freeze " + uuid, failure);
        }
    }

    private Identity readIdentity(ResultSet row) throws SQLException {
        return new Identity(
                UUID.fromString(row.getString("uuid")),
                row.getString("account_id"),
                row.getString("name_exact"),
                row.getString("name_lower"),
                Instant.parse(row.getString("first_seen")),
                Instant.parse(row.getString("last_seen")),
                row.getString("frozen_reason"));
    }

    // items --------------------------------------------------------------------------------------------------------

    /** Writes one stack and returns its id. The bytes are never changed again: a stack is split by amount, not by NBT. */
    public String insertItem(Connection connection, ItemBlob blob, int dataVersion, Instant now) {
        String itemUid = UUID.randomUUID().toString();
        byte[] data = blob.data();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO items (item_uid, blob, data_version, sha256, amount, summary, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, itemUid);
            insert.setBytes(2, data);
            insert.setInt(3, dataVersion);
            insert.setString(4, sha256(data));
            insert.setInt(5, blob.count());
            insert.setString(6, blob.summary());
            insert.setString(7, now.toString());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not store an item", failure);
        }
        return itemUid;
    }

    public Optional<StoredItem> item(Connection connection, String itemUid) {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT item_uid, blob, data_version, sha256, amount, summary FROM items WHERE item_uid = ?")) {
            select.setString(1, itemUid);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readItem(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read item " + itemUid, failure);
        }
    }

    private StoredItem readItem(ResultSet row) throws SQLException {
        ItemBlob blob = new ItemBlob(row.getBytes("blob"), row.getInt("amount"), row.getString("summary"));
        return new StoredItem(row.getString("item_uid"), blob, row.getInt("data_version"), row.getString("sha256"));
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is missing", impossible);
        }
    }

    // ledger and events --------------------------------------------------------------------------------------------

    /** Appends one line to the movement ledger. Triggers stop anything from ever changing or deleting it. */
    public void move(Connection connection, String txId, String itemUid, Holder from, Holder to, int amount, Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO item_movements (tx_id, ts, item_uid, from_holder, to_holder, amount, boot_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, txId);
            insert.setString(2, now.toString());
            insert.setString(3, itemUid);
            insert.setString(4, from.value());
            insert.setString(5, to.value());
            insert.setInt(6, amount);
            insert.setString(7, bootId);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not record an item movement", failure);
        }
    }

    public void event(
            Connection connection, Instant now, String type, UUID actor, Long listingId, Long tradeId, String txId, String detail) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO events (ts, type, actor_uuid, listing_id, trade_id, tx_id, detail) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, now.toString());
            insert.setString(2, type);
            setNullableString(insert, 3, actor == null ? null : actor.toString());
            setNullableLong(insert, 4, listingId);
            setNullableLong(insert, 5, tradeId);
            setNullableString(insert, 6, txId);
            setNullableString(insert, 7, detail);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not write a marketplace event", failure);
        }
    }

    public List<MarketEventRecord> events(Connection connection, long sinceId, int limit) {
        List<MarketEventRecord> events = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, ts, type, actor_uuid, listing_id, trade_id, tx_id, detail FROM events WHERE id > ? ORDER BY id LIMIT ?")) {
            select.setLong(1, sinceId);
            select.setInt(2, limit);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    String actor = row.getString("actor_uuid");
                    events.add(new MarketEventRecord(
                            row.getLong("id"),
                            Instant.parse(row.getString("ts")),
                            row.getString("type"),
                            actor == null ? null : UUID.fromString(actor),
                            nullableLong(row, "listing_id"),
                            nullableLong(row, "trade_id"),
                            row.getString("tx_id"),
                            row.getString("detail")));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the event feed", failure);
        }
        return events;
    }

    // listings -----------------------------------------------------------------------------------------------------

    public long insertListing(
            Connection connection,
            UUID owner,
            ListingType type,
            ListingState state,
            UUID recipient,
            String recipientNameLower,
            String note,
            Instant now,
            Instant expiresAt) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO listings (owner_uuid, type, state, recipient_uuid, recipient_name_lower, note, created_at, updated_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, owner.toString());
            insert.setString(2, type.name());
            insert.setString(3, state.name());
            setNullableString(insert, 4, recipient == null ? null : recipient.toString());
            setNullableString(insert, 5, recipientNameLower);
            setNullableString(insert, 6, note);
            insert.setString(7, now.toString());
            insert.setString(8, now.toString());
            setNullableString(insert, 9, expiresAt == null ? null : expiresAt.toString());
            insert.executeUpdate();
            return generatedId(insert);
        } catch (SQLException failure) {
            throw new StorageException("Could not create a listing", failure);
        }
    }

    public void addListingItem(Connection connection, long listingId, ItemRole role, int position, String itemUid) {
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO listing_items (listing_id, role, position, item_uid) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, listingId);
            insert.setString(2, role.name());
            insert.setInt(3, position);
            insert.setString(4, itemUid);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not attach an item to listing " + listingId, failure);
        }
    }

    public Optional<Listing> listing(Connection connection, long id) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, owner_uuid, type, state, recipient_uuid, recipient_name_lower, note, created_at, updated_at, expires_at"
                        + " FROM listings WHERE id = ?")) {
            select.setLong(1, id);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readListing(connection, row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read listing " + id, failure);
        }
    }

    public List<Listing> listings(Connection connection, Set<ListingState> states, ListingType type, UUID owner, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, owner_uuid, type, state, recipient_uuid, recipient_name_lower, note, created_at, updated_at, expires_at"
                        + " FROM listings WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (states != null && !states.isEmpty()) {
            sql.append(" AND state IN (").append("?,".repeat(states.size() - 1)).append("?)");
            states.forEach(state -> parameters.add(state.name()));
        }
        if (type != null) {
            sql.append(" AND type = ?");
            parameters.add(type.name());
        }
        if (owner != null) {
            sql.append(" AND owner_uuid = ?");
            parameters.add(owner.toString());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);

        List<Listing> listings = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                select.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    listings.add(readListing(connection, row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the listings", failure);
        }
        return listings;
    }

    private Listing readListing(Connection connection, ResultSet row) throws SQLException {
        long id = row.getLong("id");
        List<ListingItem> offered = listingItems(connection, id, ItemRole.OFFERED);
        List<ListingItem> wanted = listingItems(connection, id, ItemRole.WANTED);
        String recipient = row.getString("recipient_uuid");
        String expires = row.getString("expires_at");
        return new Listing(
                id,
                UUID.fromString(row.getString("owner_uuid")),
                ListingType.valueOf(row.getString("type")),
                ListingState.valueOf(row.getString("state")),
                recipient == null ? null : UUID.fromString(recipient),
                row.getString("recipient_name_lower"),
                row.getString("note"),
                Instant.parse(row.getString("created_at")),
                Instant.parse(row.getString("updated_at")),
                expires == null ? null : Instant.parse(expires),
                offered,
                wanted);
    }

    private List<ListingItem> listingItems(Connection connection, long listingId, ItemRole role) throws SQLException {
        List<ListingItem> items = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT li.position, i.item_uid, i.blob, i.data_version, i.sha256, i.amount, i.summary"
                        + " FROM listing_items li JOIN items i ON i.item_uid = li.item_uid"
                        + " WHERE li.listing_id = ? AND li.role = ? ORDER BY li.position")) {
            select.setLong(1, listingId);
            select.setString(2, role.name());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    items.add(new ListingItem(listingId, role, row.getInt("position"), readItem(row)));
                }
            }
        }
        return items;
    }

    /** Guarded transition. False means somebody else moved this listing first, and the caller must stop. */
    public boolean transitionListing(Connection connection, long id, ListingState from, ListingState to, Instant now) {
        try (PreparedStatement update =
                connection.prepareStatement("UPDATE listings SET state = ?, updated_at = ? WHERE id = ? AND state = ?")) {
            update.setString(1, to.name());
            update.setString(2, now.toString());
            update.setLong(3, id);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not move listing " + id + " from " + from + " to " + to, failure);
        }
    }

    // trades -------------------------------------------------------------------------------------------------------

    public long insertTrade(
            Connection connection, long listingId, UUID buyer, TradeState state, int expectedEscrowCount, Instant now, Instant expiresAt) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trades (listing_id, buyer_uuid, state, expected_escrow_count, created_at, updated_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, listingId);
            insert.setString(2, buyer.toString());
            insert.setString(3, state.name());
            insert.setInt(4, expectedEscrowCount);
            insert.setString(5, now.toString());
            insert.setString(6, now.toString());
            setNullableString(insert, 7, expiresAt == null ? null : expiresAt.toString());
            insert.executeUpdate();
            return generatedId(insert);
        } catch (SQLException failure) {
            throw new StorageException("Could not create a trade for listing " + listingId, failure);
        }
    }

    public Optional<Trade> trade(Connection connection, long id) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT t.id, t.listing_id, t.buyer_uuid, t.state, t.expected_escrow_count, t.created_at, t.updated_at, t.expires_at,"
                        + " l.owner_uuid FROM trades t JOIN listings l ON l.id = t.listing_id WHERE t.id = ?")) {
            select.setLong(1, id);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(readTrade(connection, row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read trade " + id, failure);
        }
    }

    public List<Trade> tradesOf(Connection connection, UUID player, Set<TradeState> states) {
        List<Trade> trades = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.listing_id, t.buyer_uuid, t.state, t.expected_escrow_count, t.created_at, t.updated_at, t.expires_at,"
                        + " l.owner_uuid FROM trades t JOIN listings l ON l.id = t.listing_id"
                        + " WHERE (t.buyer_uuid = ? OR l.owner_uuid = ?)");
        if (states != null && !states.isEmpty()) {
            sql.append(" AND t.state IN (").append("?,".repeat(states.size() - 1)).append("?)");
        }
        sql.append(" ORDER BY t.id DESC LIMIT 100");
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            select.setString(1, player.toString());
            select.setString(2, player.toString());
            int index = 3;
            if (states != null) {
                for (TradeState state : states) {
                    select.setString(index++, state.name());
                }
            }
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    trades.add(readTrade(connection, row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the trades of " + player, failure);
        }
        return trades;
    }

    public List<Trade> tradesForListing(Connection connection, long listingId, Set<TradeState> states) {
        List<Trade> trades = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.listing_id, t.buyer_uuid, t.state, t.expected_escrow_count, t.created_at, t.updated_at, t.expires_at,"
                        + " l.owner_uuid FROM trades t JOIN listings l ON l.id = t.listing_id WHERE t.listing_id = ?");
        if (states != null && !states.isEmpty()) {
            sql.append(" AND t.state IN (").append("?,".repeat(states.size() - 1)).append("?)");
        }
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            select.setLong(1, listingId);
            int index = 2;
            if (states != null) {
                for (TradeState state : states) {
                    select.setString(index++, state.name());
                }
            }
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    trades.add(readTrade(connection, row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read the trades of listing " + listingId, failure);
        }
        return trades;
    }

    private Trade readTrade(Connection connection, ResultSet row) throws SQLException {
        long id = row.getLong("id");
        String expires = row.getString("expires_at");
        return new Trade(
                id,
                row.getLong("listing_id"),
                UUID.fromString(row.getString("owner_uuid")),
                UUID.fromString(row.getString("buyer_uuid")),
                TradeState.valueOf(row.getString("state")),
                row.getInt("expected_escrow_count"),
                confirmations(connection, id),
                Instant.parse(row.getString("created_at")),
                Instant.parse(row.getString("updated_at")),
                expires == null ? null : Instant.parse(expires));
    }

    private Set<TradeParty> confirmations(Connection connection, long tradeId) throws SQLException {
        Set<TradeParty> parties = EnumSet.noneOf(TradeParty.class);
        try (PreparedStatement select = connection.prepareStatement("SELECT party FROM trade_confirmations WHERE trade_id = ?")) {
            select.setLong(1, tradeId);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    parties.add(TradeParty.valueOf(row.getString("party")));
                }
            }
        }
        return parties;
    }

    public boolean transitionTrade(Connection connection, long id, TradeState from, TradeState to, Instant now) {
        try (PreparedStatement update = connection.prepareStatement("UPDATE trades SET state = ?, updated_at = ? WHERE id = ? AND state = ?")) {
            update.setString(1, to.name());
            update.setString(2, now.toString());
            update.setLong(3, id);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not move trade " + id + " from " + from + " to " + to, failure);
        }
    }

    /** Records a confirmation. False means this side had already confirmed, which is not an error, just nothing new. */
    public boolean addConfirmation(Connection connection, long tradeId, TradeParty party, Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_confirmations (trade_id, party, confirmed_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            insert.setLong(1, tradeId);
            insert.setString(2, party.name());
            insert.setString(3, now.toString());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not record a confirmation for trade " + tradeId, failure);
        }
    }

    /**
     * Completes a trade only when both sides have confirmed, checked inside the same statement so no confirmation can
     * slip in between the check and the update.
     */
    public boolean completeConfirmedTrade(Connection connection, long tradeId, Instant now) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE trades SET state = 'COMPLETED', updated_at = ? WHERE id = ? AND state = 'CONFIRMED'"
                        + " AND (SELECT COUNT(*) FROM trade_confirmations WHERE trade_id = ?) = 2"
                        + " AND (SELECT COUNT(*) FROM escrow_items WHERE trade_id = ? AND state = 'HELD') = expected_escrow_count")) {
            update.setString(1, now.toString());
            update.setLong(2, tradeId);
            update.setLong(3, tradeId);
            update.setLong(4, tradeId);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not complete trade " + tradeId, failure);
        }
    }

    // escrow -------------------------------------------------------------------------------------------------------

    public long insertEscrow(
            Connection connection, String itemUid, UUID owner, Long listingId, Long tradeId, TradeParty side, Instant now) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO escrow_items (item_uid, owner_uuid, listing_id, trade_id, side, state, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'HELD', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, itemUid);
            insert.setString(2, owner.toString());
            setNullableLong(insert, 3, listingId);
            setNullableLong(insert, 4, tradeId);
            insert.setString(5, side.name());
            insert.setString(6, now.toString());
            insert.executeUpdate();
            return generatedId(insert);
        } catch (SQLException failure) {
            throw new StorageException("Could not put an item into escrow", failure);
        }
    }

    public List<EscrowItem> escrowOf(Connection connection, Long listingId, Long tradeId, EscrowState state) {
        List<EscrowItem> items = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT e.id, e.owner_uuid, e.listing_id, e.trade_id, e.side, e.state, e.released_to, e.released_tx, e.released_at,"
                        + " e.created_at, i.item_uid, i.blob, i.data_version, i.sha256, i.amount, i.summary"
                        + " FROM escrow_items e JOIN items i ON i.item_uid = e.item_uid WHERE 1 = 1");
        if (listingId != null) {
            sql.append(" AND e.listing_id = ?");
        }
        if (tradeId != null) {
            sql.append(" AND e.trade_id = ?");
        }
        if (state != null) {
            sql.append(" AND e.state = ?");
        }
        sql.append(" ORDER BY e.id");
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (listingId != null) {
                select.setLong(index++, listingId);
            }
            if (tradeId != null) {
                select.setLong(index++, tradeId);
            }
            if (state != null) {
                select.setString(index, state.name());
            }
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    items.add(readEscrow(row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not read escrow", failure);
        }
        return items;
    }

    /** Escrow still held although its listing or trade is already finished: exactly what recovery has to hand back. */
    public List<EscrowItem> orphanedEscrow(Connection connection) {
        List<EscrowItem> items = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT e.id, e.owner_uuid, e.listing_id, e.trade_id, e.side, e.state, e.released_to, e.released_tx, e.released_at,"
                        + " e.created_at, i.item_uid, i.blob, i.data_version, i.sha256, i.amount, i.summary"
                        + " FROM escrow_items e JOIN items i ON i.item_uid = e.item_uid"
                        + " LEFT JOIN listings l ON l.id = e.listing_id"
                        + " LEFT JOIN trades t ON t.id = e.trade_id"
                        + " WHERE e.state = 'HELD' AND ("
                        + "   (e.listing_id IS NOT NULL AND l.state IN ('COMPLETED','CANCELLED','EXPIRED'))"
                        + "   OR (e.trade_id IS NOT NULL AND t.state IN ('COMPLETED','REJECTED','CANCELLED','EXPIRED'))"
                        + "   OR (e.listing_id IS NULL AND e.trade_id IS NULL))")) {
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    items.add(readEscrow(row));
                }
            }
        } catch (SQLException failure) {
            throw new StorageException("Could not look for orphaned escrow", failure);
        }
        return items;
    }

    /** Guarded release. Only a row that is still HELD can leave escrow, and only once. */
    public boolean releaseEscrow(Connection connection, long escrowId, UUID releasedTo, String txId, Instant now) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE escrow_items SET state = 'RELEASED', released_to = ?, released_tx = ?, released_at = ?"
                        + " WHERE id = ? AND state = 'HELD'")) {
            update.setString(1, releasedTo.toString());
            update.setString(2, txId);
            update.setString(3, now.toString());
            update.setLong(4, escrowId);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new StorageException("Could not release escrow row " + escrowId, failure);
        }
    }

    private EscrowItem readEscrow(ResultSet row) throws SQLException {
        String releasedTo = row.getString("released_to");
        String releasedAt = row.getString("released_at");
        return new EscrowItem(
                row.getLong("id"),
                readItem(row),
                UUID.fromString(row.getString("owner_uuid")),
                nullableLong(row, "listing_id"),
                nullableLong(row, "trade_id"),
                TradeParty.valueOf(row.getString("side")),
                EscrowState.valueOf(row.getString("state")),
                releasedTo == null ? null : UUID.fromString(releasedTo),
                row.getString("released_tx"),
                releasedAt == null ? null : Instant.parse(releasedAt),
                Instant.parse(row.getString("created_at")));
    }

    // administration -----------------------------------------------------------------------------------------------

    /** Hands the open listings of one identity to another. Only open ones: history keeps the name it was made under. */
    public int reassignListings(Connection connection, UUID from, UUID to, Instant now) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE listings SET owner_uuid = ?, updated_at = ? WHERE owner_uuid = ? AND state IN ('DRAFT','ACTIVE','PENDING_TRADE')")) {
            update.setString(1, to.toString());
            update.setString(2, now.toString());
            update.setString(3, from.toString());
            return update.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not hand the listings of " + from + " to " + to, failure);
        }
    }

    public int reassignEscrow(Connection connection, UUID from, UUID to) {
        try (PreparedStatement update =
                connection.prepareStatement("UPDATE escrow_items SET owner_uuid = ? WHERE owner_uuid = ? AND state = 'HELD'")) {
            update.setString(1, to.toString());
            update.setString(2, from.toString());
            return update.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not hand the escrow of " + from + " to " + to, failure);
        }
    }

    public int reassignDeliveries(Connection connection, UUID from, UUID to) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE pending_deliveries SET player_uuid = ? WHERE player_uuid = ? AND state = 'PENDING'")) {
            update.setString(1, to.toString());
            update.setString(2, from.toString());
            return update.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("Could not hand the deliveries of " + from + " to " + to, failure);
        }
    }

    // helpers ------------------------------------------------------------------------------------------------------

    static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new StorageException("The database did not return the id of the new row");
            }
            return keys.getLong(1);
        }
    }

    static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }
}
