package site.vinoff.market.storage;

import java.util.List;

/**
 * Schema steps, applied in order, tracked by {@code PRAGMA user_version}. Never edit a step that has shipped: add a new
 * one. Every step is applied inside its own transaction.
 *
 * <p>Two ideas carry the safety of the whole marketplace:
 *
 * <ul>
 *   <li>{@code items} holds one row per stack as a single item prototype plus an amount, so splitting and counting are
 *       integer arithmetic rather than surgery on NBT;
 *   <li>{@code item_movements} is an append only ledger, enforced by triggers. Every other table is an index over it,
 *       which makes "every item has exactly one holder" a claim the code can check instead of hope for.
 * </ul>
 */
public final class Migrations {

    private Migrations() {}

    public static final List<String> STEPS = List.of(
            """
            CREATE TABLE identities (
              uuid TEXT PRIMARY KEY,
              account_id TEXT NOT NULL,
              name_exact TEXT NOT NULL,
              name_lower TEXT NOT NULL,
              first_seen TEXT NOT NULL,
              last_seen TEXT NOT NULL,
              frozen_reason TEXT
            );
            CREATE INDEX identities_account ON identities(account_id);
            CREATE INDEX identities_name ON identities(name_lower);

            CREATE TABLE items (
              item_uid TEXT PRIMARY KEY,
              blob BLOB NOT NULL,
              data_version INTEGER NOT NULL,
              sha256 TEXT NOT NULL,
              amount INTEGER NOT NULL CHECK (amount > 0),
              summary TEXT NOT NULL,
              created_at TEXT NOT NULL
            );

            CREATE TABLE listings (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              owner_uuid TEXT NOT NULL REFERENCES identities(uuid),
              type TEXT NOT NULL,
              state TEXT NOT NULL,
              recipient_uuid TEXT,
              recipient_name_lower TEXT,
              note TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              expires_at TEXT
            );
            CREATE INDEX listings_state ON listings(state);
            CREATE INDEX listings_owner ON listings(owner_uuid, state);

            CREATE TABLE listing_items (
              listing_id INTEGER NOT NULL REFERENCES listings(id),
              role TEXT NOT NULL,
              position INTEGER NOT NULL,
              item_uid TEXT NOT NULL REFERENCES items(item_uid),
              PRIMARY KEY (listing_id, role, position)
            );

            CREATE TABLE trades (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              listing_id INTEGER NOT NULL REFERENCES listings(id),
              buyer_uuid TEXT NOT NULL REFERENCES identities(uuid),
              state TEXT NOT NULL,
              expected_escrow_count INTEGER NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              expires_at TEXT
            );
            CREATE INDEX trades_listing ON trades(listing_id, state);
            CREATE INDEX trades_buyer ON trades(buyer_uuid, state);

            CREATE TABLE trade_confirmations (
              trade_id INTEGER NOT NULL REFERENCES trades(id),
              party TEXT NOT NULL,
              confirmed_at TEXT NOT NULL,
              PRIMARY KEY (trade_id, party)
            );

            CREATE TABLE escrow_items (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              item_uid TEXT NOT NULL REFERENCES items(item_uid),
              owner_uuid TEXT NOT NULL,
              listing_id INTEGER REFERENCES listings(id),
              trade_id INTEGER REFERENCES trades(id),
              side TEXT NOT NULL,
              state TEXT NOT NULL,
              released_to TEXT,
              released_tx TEXT,
              released_at TEXT,
              created_at TEXT NOT NULL
            );
            CREATE INDEX escrow_state ON escrow_items(state);
            CREATE INDEX escrow_listing ON escrow_items(listing_id, state);
            CREATE INDEX escrow_trade ON escrow_items(trade_id, state);
            CREATE INDEX escrow_owner ON escrow_items(owner_uuid, state);

            CREATE TABLE pending_deliveries (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              player_uuid TEXT NOT NULL,
              item_uid TEXT NOT NULL REFERENCES items(item_uid),
              reason TEXT NOT NULL,
              state TEXT NOT NULL,
              source_escrow_item_id INTEGER UNIQUE REFERENCES escrow_items(id),
              claim_tx TEXT,
              claim_boot_id TEXT,
              attempts INTEGER NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL,
              claimed_at TEXT
            );
            CREATE INDEX deliveries_player ON pending_deliveries(player_uuid, state);

            CREATE TABLE intents (
              tx_id TEXT PRIMARY KEY,
              boot_id TEXT NOT NULL,
              op TEXT NOT NULL,
              player_uuid TEXT NOT NULL,
              listing_id INTEGER,
              trade_id INTEGER,
              state TEXT NOT NULL,
              pre_digest TEXT NOT NULL,
              data_version INTEGER NOT NULL,
              detail TEXT,
              created_at TEXT NOT NULL,
              resolved_at TEXT
            );
            CREATE INDEX intents_open ON intents(player_uuid, state);

            CREATE TABLE item_movements (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              tx_id TEXT NOT NULL,
              ts TEXT NOT NULL,
              item_uid TEXT NOT NULL REFERENCES items(item_uid),
              from_holder TEXT NOT NULL,
              to_holder TEXT NOT NULL,
              amount INTEGER NOT NULL CHECK (amount > 0),
              boot_id TEXT NOT NULL
            );
            CREATE INDEX movements_item ON item_movements(item_uid, id);
            CREATE TRIGGER item_movements_no_update BEFORE UPDATE ON item_movements
              BEGIN SELECT RAISE(ABORT, 'item_movements is append only'); END;
            CREATE TRIGGER item_movements_no_delete BEFORE DELETE ON item_movements
              BEGIN SELECT RAISE(ABORT, 'item_movements is append only'); END;

            CREATE TABLE events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts TEXT NOT NULL,
              type TEXT NOT NULL,
              actor_uuid TEXT,
              listing_id INTEGER,
              trade_id INTEGER,
              tx_id TEXT,
              detail TEXT
            );
            CREATE INDEX events_listing ON events(listing_id);
            CREATE TRIGGER events_no_update BEFORE UPDATE ON events
              BEGIN SELECT RAISE(ABORT, 'events is append only'); END;
            CREATE TRIGGER events_no_delete BEFORE DELETE ON events
              BEGIN SELECT RAISE(ABORT, 'events is append only'); END;

            CREATE TABLE api_requests (
              idempotency_key TEXT PRIMARY KEY,
              endpoint TEXT NOT NULL,
              status INTEGER NOT NULL,
              response TEXT NOT NULL,
              created_at TEXT NOT NULL
            );

            CREATE TABLE server_state (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL,
              updated_at TEXT NOT NULL
            );
            """);

    public static int latestVersion() {
        return STEPS.size();
    }
}
