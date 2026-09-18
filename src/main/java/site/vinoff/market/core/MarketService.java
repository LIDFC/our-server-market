package site.vinoff.market.core;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import site.vinoff.market.core.model.EscrowItem;
import site.vinoff.market.core.model.Holder;
import site.vinoff.market.core.model.Identity;
import site.vinoff.market.core.model.Intent;
import site.vinoff.market.core.model.ItemRole;
import site.vinoff.market.core.model.Listing;
import site.vinoff.market.core.model.PendingDelivery;
import site.vinoff.market.core.model.StoredItem;
import site.vinoff.market.core.model.Trade;
import site.vinoff.market.core.model.TradeParty;
import site.vinoff.market.core.port.InventoryPort;
import site.vinoff.market.core.port.MarketClock;
import site.vinoff.market.storage.Database;
import site.vinoff.market.storage.DeliveryRepository;
import site.vinoff.market.storage.MarketRepository;

/**
 * Everything the marketplace can do with items. One class on purpose: there is exactly one place where an item changes
 * hands, so there is exactly one place to get right.
 *
 * <p>Two protocols carry the safety:
 *
 * <ul>
 *   <li><b>Taking items</b> writes an intent with a fingerprint of the player's inventory, removes the items, flushes
 *       the player file, and only then records the escrow. A crash anywhere in the middle leaves an intent that the
 *       next login resolves by comparing the fingerprint, so the items are neither duplicated nor lost.
 *   <li><b>Giving items</b> always goes through a pending delivery, marked as being handed over before the inventory
 *       is touched. Nothing is ever "given directly", so recovery has one kind of row to look at.
 * </ul>
 */
public final class MarketService {

    /** How many stacks one listing may hold. Caps the damage a single mistake can do. */
    public static final int MAX_ITEMS_PER_LISTING = 27;

    private final Database database;
    private final MarketRepository market;
    private final DeliveryRepository deliveries;
    private final InventoryPort inventory;
    private final MarketClock clock;
    private final Logger log;

    public MarketService(
            Database database,
            MarketRepository market,
            DeliveryRepository deliveries,
            InventoryPort inventory,
            MarketClock clock,
            Logger log) {
        this.database = database;
        this.market = market;
        this.deliveries = deliveries;
        this.inventory = inventory;
        this.clock = clock;
        this.log = log;
    }

    // identities ---------------------------------------------------------------------------------------------------

    public Identity seePlayer(UUID uuid, String name) {
        Instant now = clock.now();
        return database.inTransaction(connection -> market.touchIdentity(connection, uuid, name, now));
    }

    public Optional<Identity> findPlayer(String name) {
        return database.read(connection -> market.identityByName(connection, name));
    }

    // listings -----------------------------------------------------------------------------------------------------

    /** Starts a listing. Nothing is escrowed yet: items join it one at a time through {@link #addOffer}. */
    public long createDraft(UUID owner, String ownerName, ListingType type, UUID recipient, String recipientName, String note) {
        Instant now = clock.now();
        return database.inTransaction(connection -> {
            market.touchIdentity(connection, owner, ownerName, now);
            long id = market.insertListing(
                    connection,
                    owner,
                    type,
                    ListingState.DRAFT,
                    recipient,
                    recipientName == null ? null : recipientName.toLowerCase(java.util.Locale.ROOT),
                    note,
                    now,
                    null);
            market.event(connection, now, "LISTING_DRAFTED", owner, id, null, null, type.name());
            return id;
        });
    }

    /** Moves one stack from the player into the draft. This is the only moment the player's inventory shrinks. */
    public void addOffer(UUID owner, long listingId, List<ItemBlob> items) {
        Listing listing = requireListing(listingId);
        requireOwner(listing, owner);
        if (listing.state() != ListingState.DRAFT) {
            throw new MarketException(MarketError.LISTING_NOT_DRAFT, "This listing is not a draft any more");
        }
        if (listing.offered().size() + items.size() > MAX_ITEMS_PER_LISTING) {
            throw new MarketException(MarketError.TOO_MANY_ITEMS, "A listing holds at most " + MAX_ITEMS_PER_LISTING + " stacks");
        }
        takeItems(owner, items, "LIST_OFFER", listingId, null, (connection, itemUids, now, txId) -> {
            int position = listing.offered().size();
            for (String itemUid : itemUids) {
                market.addListingItem(connection, listingId, ItemRole.OFFERED, position++, itemUid);
                market.insertEscrow(connection, itemUid, owner, listingId, null, TradeParty.OWNER, now);
                market.move(connection, txId, itemUid, Holder.player(owner), Holder.listing(listingId), amountOf(connection, itemUid), now);
            }
            market.event(connection, now, "ESCROW_IN", owner, listingId, null, txId, items.size() + " stack(s)");
        });
    }

    /** Records what the author is looking for. The item never leaves the player: this is a description, not an offer. */
    public void addWanted(UUID owner, long listingId, List<ItemBlob> items) {
        Listing listing = requireListing(listingId);
        requireOwner(listing, owner);
        if (listing.state() != ListingState.DRAFT) {
            throw new MarketException(MarketError.LISTING_NOT_DRAFT, "This listing is not a draft any more");
        }
        Instant now = clock.now();
        database.inTransaction(connection -> {
            int position = listing.wanted().size();
            for (ItemBlob blob : items) {
                String itemUid = market.insertItem(connection, blob, inventory.dataVersion(), now);
                market.addListingItem(connection, listingId, ItemRole.WANTED, position++, itemUid);
            }
            market.event(connection, now, "LISTING_WANTED_ADDED", owner, listingId, null, null, items.size() + " stack(s)");
            return null;
        });
    }

    /** Publishes a draft. Pure state change: the items are already in escrow. */
    public void publish(UUID owner, long listingId) {
        Listing listing = requireListing(listingId);
        requireOwner(listing, owner);
        if (listing.type() == ListingType.WANTED ? listing.wanted().isEmpty() : listing.offered().isEmpty()) {
            throw new MarketException(MarketError.EMPTY_LISTING, "Put at least one item into the listing first");
        }
        Instant now = clock.now();
        database.inTransaction(connection -> {
            if (!market.transitionListing(connection, listingId, ListingState.DRAFT, ListingState.ACTIVE, now)) {
                throw new MarketException(MarketError.LISTING_NOT_DRAFT, "This listing is not a draft any more");
            }
            market.event(connection, now, "LISTING_CREATED", owner, listingId, null, null, listing.type().name());
            return null;
        });
    }

    /** Cancels a listing and queues everything it holds back to its owner. Repeating it does nothing. */
    public void cancel(UUID actor, long listingId, boolean admin) {
        Listing listing = requireListing(listingId);
        if (!admin) {
            requireOwner(listing, actor);
        }
        Instant now = clock.now();
        String txId = newTx();
        database.inTransaction(connection -> {
            ListingState from = listing.state();
            if (from.terminal()) {
                // already cancelled, taken or expired: nothing to do, and saying so twice is not an error
                return null;
            }
            if (from != ListingState.DRAFT && from != ListingState.ACTIVE) {
                throw new MarketException(MarketError.LISTING_NOT_ACTIVE, "A trade is running on this listing");
            }
            if (!market.transitionListing(connection, listingId, from, ListingState.CANCELLED, now)) {
                // somebody else got there first; that is not an error for the player
                return null;
            }
            returnEscrow(connection, market.escrowOf(connection, listingId, null, EscrowState.HELD), DeliveryReason.LISTING_CANCELLED, txId, now);
            market.event(connection, now, "LISTING_CANCELLED", actor, listingId, null, txId, admin ? "by an administrator" : null);
            return null;
        });
    }

    /** Takes a free listing (giveaway or a gift addressed to this player). */
    public void claim(UUID claimer, String claimerName, long listingId) {
        Listing listing = requireListing(listingId);
        if (listing.type() != ListingType.GIVEAWAY && listing.type() != ListingType.GIFT) {
            throw new MarketException(MarketError.INVALID_REQUEST, "This listing is a trade, use an offer instead");
        }
        if (listing.ownedBy(claimer)) {
            throw new MarketException(MarketError.OWN_LISTING, "This is your own listing");
        }
        if (listing.type() == ListingType.GIFT) {
            boolean uuidMatches = listing.recipient().map(claimer::equals).orElse(false);
            boolean nameMatches = listing.recipientNameLower() == null
                    || listing.recipientNameLower().equals(claimerName.toLowerCase(java.util.Locale.ROOT));
            if (!uuidMatches || !nameMatches) {
                throw new MarketException(MarketError.NOT_RECIPIENT, "This gift is addressed to somebody else");
            }
        }
        Instant now = clock.now();
        String txId = newTx();
        database.inTransaction(connection -> {
            market.touchIdentity(connection, claimer, claimerName, now);
            if (!market.transitionListing(connection, listingId, ListingState.ACTIVE, ListingState.COMPLETED, now)) {
                throw new MarketException(MarketError.LISTING_ALREADY_TAKEN, "Somebody was faster");
            }
            List<EscrowItem> held = market.escrowOf(connection, listingId, null, EscrowState.HELD);
            for (EscrowItem item : held) {
                releaseTo(connection, item, claimer, DeliveryReason.GIVEAWAY_CLAIMED, txId, now);
            }
            market.event(connection, now, "LISTING_CLAIMED", claimer, listingId, null, txId, held.size() + " stack(s)");
            return null;
        });
    }

    // trades -------------------------------------------------------------------------------------------------------

    /**
     * Offers a trade on a listing. The listing is claimed first with a guarded update, so two players cannot both take
     * it; only then do the buyer's items move.
     */
    public long offerTrade(UUID buyer, String buyerName, long listingId, List<ItemBlob> items) {
        Listing listing = requireListing(listingId);
        if (listing.ownedBy(buyer)) {
            throw new MarketException(MarketError.OWN_LISTING, "This is your own listing");
        }
        if (listing.state() == ListingState.PENDING_TRADE) {
            // somebody else is already trading for it; the guarded update below says the same thing on a closer race
            throw new MarketException(MarketError.LISTING_ALREADY_TAKEN, "Somebody is already trading for this");
        }
        if (listing.state() != ListingState.ACTIVE) {
            throw new MarketException(MarketError.LISTING_NOT_ACTIVE, "This listing is not open");
        }
        if (items.isEmpty()) {
            throw new MarketException(MarketError.EMPTY_LISTING, "Offer at least one item");
        }
        Instant now = clock.now();

        // step one: win the listing. Nothing has moved yet, so losing here costs nothing.
        long tradeId = database.inTransaction(connection -> {
            market.touchIdentity(connection, buyer, buyerName, now);
            if (!market.transitionListing(connection, listingId, ListingState.ACTIVE, ListingState.PENDING_TRADE, now)) {
                throw new MarketException(MarketError.LISTING_ALREADY_TAKEN, "Somebody is already trading for this");
            }
            long id = market.insertTrade(connection, listingId, buyer, TradeState.PENDING, items.size(), now, null);
            market.event(connection, now, "TRADE_CREATED", buyer, listingId, id, null, items.size() + " stack(s) offered");
            return id;
        });

        // step two: take the items. If this fails the claim is given back, so the listing does not stay stuck.
        try {
            takeItems(buyer, items, "TRADE_OFFER", listingId, tradeId, (connection, itemUids, at, txId) -> {
                for (String itemUid : itemUids) {
                    market.insertEscrow(connection, itemUid, buyer, listingId, tradeId, TradeParty.BUYER, at);
                    market.move(connection, txId, itemUid, Holder.player(buyer), Holder.trade(tradeId), amountOf(connection, itemUid), at);
                }
                market.event(connection, at, "ESCROW_IN", buyer, listingId, tradeId, txId, itemUids.size() + " stack(s)");
            });
        } catch (RuntimeException failure) {
            compensateFailedOffer(listingId, tradeId);
            throw failure;
        }
        return tradeId;
    }

    /** Undoes a trade claim whose items never made it into escrow. */
    private void compensateFailedOffer(long listingId, long tradeId) {
        Instant now = clock.now();
        try {
            database.inTransaction(connection -> {
                market.transitionTrade(connection, tradeId, TradeState.PENDING, TradeState.CANCELLED, now);
                market.transitionListing(connection, listingId, ListingState.PENDING_TRADE, ListingState.ACTIVE, now);
                market.event(connection, now, "TRADE_CANCELLED", null, listingId, tradeId, null, "the offered items never arrived");
                return null;
            });
        } catch (RuntimeException secondFailure) {
            log.severe("Could not undo trade " + tradeId + " after a failed offer: " + secondFailure.getMessage());
        }
    }

    public void acceptTrade(UUID owner, long tradeId) {
        Trade trade = requireTrade(tradeId);
        if (!trade.ownerUuid().equals(owner)) {
            throw new MarketException(MarketError.NOT_OWNER, "Only the owner of the listing can accept an offer");
        }
        Instant now = clock.now();
        database.inTransaction(connection -> {
            if (!market.transitionTrade(connection, tradeId, TradeState.PENDING, TradeState.ACCEPTED, now)) {
                throw new MarketException(MarketError.TRADE_NOT_PENDING, "This offer is not waiting for an answer any more");
            }
            market.event(connection, now, "TRADE_ACCEPTED", owner, trade.listingId(), tradeId, null, null);
            return null;
        });
    }

    /** Refuses a trade and gives the buyer their items back. Either side may do it before both have confirmed. */
    public void declineTrade(UUID actor, long tradeId, boolean admin) {
        Trade trade = requireTrade(tradeId);
        if (!admin && !trade.involves(actor)) {
            throw new MarketException(MarketError.NOT_PARTICIPANT, "This is not your trade");
        }
        Instant now = clock.now();
        String txId = newTx();
        database.inTransaction(connection -> {
            TradeState from = trade.state();
            if (from != TradeState.PENDING && from != TradeState.ACCEPTED && from != TradeState.CONFIRMED) {
                return null;
            }
            if (!market.transitionTrade(connection, tradeId, from, TradeState.REJECTED, now)) {
                return null;
            }
            returnEscrow(
                    connection,
                    market.escrowOf(connection, null, tradeId, EscrowState.HELD),
                    DeliveryReason.TRADE_REJECTED,
                    txId,
                    now);
            // the listing goes back on the market with its own items still in escrow
            market.transitionListing(connection, trade.listingId(), ListingState.PENDING_TRADE, ListingState.ACTIVE, now);
            market.event(connection, now, "TRADE_REJECTED", actor, trade.listingId(), tradeId, txId, admin ? "by an administrator" : null);
            return null;
        });
    }

    /**
     * Records one side's confirmation and, when both sides have confirmed, completes the trade. Pressing confirm twice
     * changes nothing.
     */
    public boolean confirmTrade(UUID actor, long tradeId) {
        Trade trade = requireTrade(tradeId);
        TradeParty party = trade.partyOf(actor);
        if (party == null) {
            throw new MarketException(MarketError.NOT_PARTICIPANT, "This is not your trade");
        }
        if (trade.state() == TradeState.COMPLETED) {
            // pressing confirm again after the trade went through is not an error, it is simply already done
            return true;
        }
        if (trade.state() != TradeState.ACCEPTED && trade.state() != TradeState.CONFIRMED) {
            throw new MarketException(
                    MarketError.TRADE_NOT_ACCEPTED,
                    trade.state().terminal() ? "This trade is already finished" : "The owner has not accepted the offer yet");
        }
        Instant now = clock.now();
        boolean bothConfirmed = database.inTransaction(connection -> {
            market.addConfirmation(connection, tradeId, party, now);
            market.event(connection, now, "TRADE_CONFIRMED", actor, trade.listingId(), tradeId, null, party.name());
            Trade current = market.trade(connection, tradeId).orElseThrow();
            if (!current.bothConfirmed()) {
                return false;
            }
            return market.transitionTrade(connection, tradeId, TradeState.ACCEPTED, TradeState.CONFIRMED, now)
                    || current.state() == TradeState.CONFIRMED;
        });
        if (bothConfirmed) {
            completeTrade(tradeId);
        }
        return bothConfirmed;
    }

    /**
     * Hands both sides what they earned. Guarded so that running it twice cannot deliver anything twice: the trade can
     * only leave CONFIRMED once, and each escrow row can only be released once.
     */
    public void completeTrade(long tradeId) {
        Trade trade = requireTrade(tradeId);
        if (trade.state() == TradeState.COMPLETED) {
            return;
        }
        Instant now = clock.now();
        String txId = newTx();
        database.inTransaction(connection -> {
            if (!market.completeConfirmedTrade(connection, tradeId, now)) {
                Trade current = market.trade(connection, tradeId).orElseThrow();
                if (current.state() == TradeState.COMPLETED) {
                    return null;
                }
                throw new MarketException(MarketError.TRADE_NOT_ACCEPTED, "This trade is not ready to be completed");
            }
            for (EscrowItem item : market.escrowOf(connection, null, tradeId, EscrowState.HELD)) {
                // the buyer's items go to the owner of the listing and the other way round
                UUID recipient = item.side() == TradeParty.BUYER ? trade.ownerUuid() : trade.buyerUuid();
                releaseTo(connection, item, recipient, DeliveryReason.TRADE_COMPLETED, txId, now);
            }
            for (EscrowItem item : market.escrowOf(connection, trade.listingId(), null, EscrowState.HELD)) {
                if (item.tradeId() == null) {
                    releaseTo(connection, item, trade.buyerUuid(), DeliveryReason.TRADE_COMPLETED, txId, now);
                }
            }
            market.transitionListing(connection, trade.listingId(), ListingState.PENDING_TRADE, ListingState.COMPLETED, now);
            market.event(connection, now, "TRADE_COMPLETED", null, trade.listingId(), tradeId, txId, null);
            return null;
        });
    }

    // deliveries ---------------------------------------------------------------------------------------------------

    /**
     * Hands over what is waiting for a player, stack by stack. Each one is marked as being handed over and committed
     * before the inventory is touched, so a crash leaves a row that recovery can decide about.
     */
    public int claimDeliveries(UUID player) {
        if (!inventory.readyForItems(player)) {
            return 0;
        }
        List<PendingDelivery> waiting = database.read(connection -> deliveries.deliveries(connection, player, DeliveryState.PENDING, 36));
        int handed = 0;
        for (PendingDelivery delivery : waiting) {
            if (!inventory.readyForItems(player)) {
                break;
            }
            List<ItemBlob> one = List.of(delivery.item().blob());
            if (!inventory.fits(player, one)) {
                continue;
            }
            if (giveItems(player, one, delivery)) {
                handed++;
            }
        }
        return handed;
    }

    public List<PendingDelivery> pendingDeliveries(UUID player) {
        return database.read(connection -> deliveries.deliveries(connection, player, DeliveryState.PENDING, 100));
    }

    public int pendingCount(UUID player) {
        return database.read(connection -> deliveries.pendingCount(connection, player));
    }

    // reading ------------------------------------------------------------------------------------------------------

    public Optional<Listing> listing(long id) {
        return database.read(connection -> market.listing(connection, id));
    }

    public List<Listing> activeListings(ListingType type, int limit, int offset) {
        return database.read(connection -> market.listings(connection, Set.of(ListingState.ACTIVE), type, null, limit, offset));
    }

    public List<Listing> listingsOf(UUID owner, boolean includeFinished) {
        Set<ListingState> states = includeFinished
                ? EnumSet.allOf(ListingState.class)
                : EnumSet.of(ListingState.DRAFT, ListingState.ACTIVE, ListingState.PENDING_TRADE);
        return database.read(connection -> market.listings(connection, states, null, owner, 100, 0));
    }

    public List<Trade> tradesOf(UUID player, boolean includeFinished) {
        Set<TradeState> states = includeFinished
                ? EnumSet.allOf(TradeState.class)
                : EnumSet.of(TradeState.PENDING, TradeState.ACCEPTED, TradeState.CONFIRMED);
        return database.read(connection -> market.tradesOf(connection, player, states));
    }

    public Optional<Trade> trade(long id) {
        return database.read(connection -> market.trade(connection, id));
    }

    public List<site.vinoff.market.core.model.MarketEventRecord> events(long sinceId, int limit) {
        return database.read(connection -> market.events(connection, sinceId, limit));
    }

    // the two protocols --------------------------------------------------------------------------------------------

    /** Work to run once the items are safely out of the player's inventory. */
    @FunctionalInterface
    private interface AfterTaken {
        void apply(Connection connection, List<String> itemUids, Instant now, String txId);
    }

    /**
     * Takes items from a player: intent first, then the inventory, then the player file, then the escrow. The
     * fingerprint in the intent is what lets the next login tell a crash before the removal from a crash after it.
     */
    private void takeItems(UUID player, List<ItemBlob> items, String op, Long listingId, Long tradeId, AfterTaken afterTaken) {
        if (items.isEmpty()) {
            throw new MarketException(MarketError.EMPTY_LISTING, "There is nothing to put up");
        }
        String digest = inventory
                .digest(player)
                .orElseThrow(() -> new MarketException(MarketError.INVALID_REQUEST, "Your inventory is not available right now"));
        String txId = newTx();
        Instant started = clock.now();

        List<String> itemUids = database.inTransaction(connection -> {
            List<String> uids = new ArrayList<>();
            for (ItemBlob blob : items) {
                uids.add(market.insertItem(connection, blob, inventory.dataVersion(), started));
            }
            deliveries.insertIntent(
                    connection, txId, database.bootId(), op, player, listingId, tradeId, digest, inventory.dataVersion(),
                    String.join(",", uids), started);
            return uids;
        });

        if (!inventory.removeExactly(player, items)) {
            database.inTransaction(connection -> {
                deliveries.transitionIntent(connection, txId, IntentState.INTENT, IntentState.ABORTED, clock.now());
                return null;
            });
            throw new MarketException(MarketError.INVALID_REQUEST, "Those items are not in your inventory any more");
        }
        // the barrier: from here on the player file on disk no longer holds these items
        if (!inventory.save(player)) {
            log.warning("Could not flush the inventory of " + player + " during " + op + "; intent " + txId + " will be checked at login");
        }

        database.inTransaction(connection -> {
            Instant now = clock.now();
            if (!deliveries.transitionIntent(connection, txId, IntentState.INTENT, IntentState.APPLIED, now)) {
                throw new MarketException(MarketError.STORAGE_FAILURE, "The intent record disappeared");
            }
            afterTaken.apply(connection, itemUids, now, txId);
            deliveries.transitionIntent(connection, txId, IntentState.APPLIED, IntentState.FINALIZED, now);
            return null;
        });
    }

    /**
     * Gives one delivery to a player: mark it as being handed over, commit, then touch the inventory. If the handover
     * does not go through, the row goes back into the queue and the player simply tries again.
     */
    private boolean giveItems(UUID player, List<ItemBlob> items, PendingDelivery delivery) {
        String txId = newTx();
        String digest = inventory.digest(player).orElse(null);
        if (digest == null) {
            return false;
        }
        Instant started = clock.now();
        boolean claiming = database.inTransaction(connection -> {
            if (!deliveries.markClaiming(connection, delivery.id(), txId, database.bootId(), started)) {
                return false;
            }
            deliveries.insertIntent(
                    connection, txId, database.bootId(), "CLAIM", player, null, null, digest, inventory.dataVersion(),
                    String.valueOf(delivery.id()), started);
            return true;
        });
        if (!claiming) {
            return false;
        }

        boolean added;
        try {
            added = inventory.addAll(player, items);
        } catch (RuntimeException failure) {
            log.warning("Handing items to " + player + " failed: " + failure.getMessage());
            added = false;
        }
        if (!added) {
            database.inTransaction(connection -> {
                deliveries.revertClaiming(connection, delivery.id(), txId);
                deliveries.transitionIntent(connection, txId, IntentState.INTENT, IntentState.ABORTED, clock.now());
                return null;
            });
            return false;
        }
        inventory.save(player);

        Instant now = clock.now();
        database.inTransaction(connection -> {
            deliveries.transitionIntent(connection, txId, IntentState.INTENT, IntentState.FINALIZED, now);
            if (deliveries.markClaimed(connection, delivery.id(), txId, now)) {
                market.move(connection, txId, delivery.item().itemUid(), Holder.pending(delivery.id()), Holder.player(player),
                        delivery.item().amount(), now);
                market.event(connection, now, "DELIVERY_CLAIMED", player, null, null, txId, delivery.item().summary());
            }
            return null;
        });
        return true;
    }

    /** Moves escrow rows into the delivery queue of their own owners, used when something is cancelled. */
    private void returnEscrow(Connection connection, List<EscrowItem> items, DeliveryReason reason, String txId, Instant now) {
        for (EscrowItem item : items) {
            releaseTo(connection, item, item.ownerUuid(), reason, txId, now);
        }
    }

    /** Releases one escrow row to a player. Guarded, so the same row can never be handed out twice. */
    private void releaseTo(Connection connection, EscrowItem item, UUID recipient, DeliveryReason reason, String txId, Instant now) {
        if (!market.releaseEscrow(connection, item.id(), recipient, txId, now)) {
            return;
        }
        long deliveryId = deliveries.insertDelivery(connection, recipient, item.item().itemUid(), reason, item.id(), now);
        Holder from = item.tradeId() != null ? Holder.trade(item.tradeId()) : Holder.listing(item.listingId());
        market.move(connection, txId, item.item().itemUid(), from, Holder.pending(deliveryId), item.item().amount(), now);
        market.event(
                connection, now, "PENDING_DELIVERY_CREATED", recipient, item.listingId(), item.tradeId(), txId, item.item().summary());
    }

    private int amountOf(Connection connection, String itemUid) {
        return market.item(connection, itemUid).map(StoredItem::amount).orElse(1);
    }

    // administration -----------------------------------------------------------------------------------------------

    /**
     * Moves everything one identity owns to another one. On an offline server a rename makes a new UUID, so this is
     * how a player gets their own escrow back; it is one transaction and it writes down who did it.
     *
     * @return how many listings, escrow rows and deliveries were moved
     */
    public ReassignReport reassign(UUID from, UUID to, UUID admin) {
        if (from.equals(to)) {
            throw new MarketException(MarketError.INVALID_REQUEST, "Those are the same player");
        }
        Instant now = clock.now();
        String txId = newTx();
        return database.inTransaction(connection -> {
            if (market.identity(connection, to).isEmpty()) {
                throw new MarketException(MarketError.PLAYER_NOT_FOUND, "The new player has never been seen on this server");
            }
            int listings = market.reassignListings(connection, from, to, now);
            int escrow = market.reassignEscrow(connection, from, to);
            int pending = market.reassignDeliveries(connection, from, to);
            market.event(
                    connection, now, "ADMIN_REASSIGN", admin, null, null, txId,
                    from + " → " + to + ": " + listings + " listing(s), " + escrow + " escrow row(s), " + pending + " delivery(ies)");
            return new ReassignReport(listings, escrow, pending);
        });
    }

    /** What an administrator sees about a player. */
    public String inspect(UUID player) {
        return database.read(connection -> {
            int listings = market.listings(connection, EnumSet.of(ListingState.ACTIVE, ListingState.PENDING_TRADE, ListingState.DRAFT), null, player, 100, 0).size();
            int escrow = market.escrowOf(connection, null, null, EscrowState.HELD).stream()
                    .filter(item -> item.ownerUuid().equals(player))
                    .mapToInt(item -> 1)
                    .sum();
            int waiting = deliveries.pendingCount(connection, player);
            String name = market.identity(connection, player).map(Identity::nameExact).orElse("unknown");
            return name + " (" + player + "): " + listings + " open listing(s), " + escrow + " stack(s) in escrow, " + waiting
                    + " waiting delivery(ies)";
        });
    }

    /** What a reassignment moved. */
    public record ReassignReport(int listings, int escrowRows, int deliveries) {}

    // recovery -----------------------------------------------------------------------------------------------------

    /**
     * What the marketplace can decide on its own at startup: escrow whose listing or trade is already finished, and
     * handovers that an earlier run of the server started. Anything that needs to look at an inventory waits for the
     * player to log in.
     */
    public RecoveryReport recoverAtStartup() {
        Instant now = clock.now();
        String txId = newTx();
        return database.inTransaction(connection -> {
            int orphans = 0;
            for (EscrowItem item : market.orphanedEscrow(connection)) {
                releaseTo(connection, item, item.ownerUuid(), DeliveryReason.RECOVERED, txId, now);
                orphans++;
            }
            // Handovers interrupted by the crash are deliberately left alone. Whether the items reached the player can
            // only be told by looking at their inventory, and that happens when they log in. Queueing them again here
            // would hand out a second copy of everything that did arrive.
            int handovers = deliveries.claimingFromOtherBoots(connection, database.bootId()).size();
            if (orphans > 0 || handovers > 0) {
                market.event(
                        connection, now, "RECOVERY", null, null, null, txId,
                        orphans + " escrow row(s) returned, " + handovers + " handover(s) waiting for their player to log in");
            }
            return new RecoveryReport(orphans, handovers);
        });
    }

    /**
     * Resolves what an earlier run left open for one player, by comparing the fingerprint in the intent with the
     * inventory the player just logged in with. Equal means the removal never survived; different means it did.
     */
    public void recoverPlayer(UUID player) {
        List<Intent> open = database.read(connection -> deliveries.unresolvedIntents(connection, player, database.bootId()));
        if (open.isEmpty()) {
            return;
        }
        String digest = inventory.digest(player).orElse(null);
        if (digest == null) {
            return;
        }
        for (Intent intent : open) {
            Instant now = clock.now();
            boolean removalSurvived = !digest.equals(intent.preDigest());
            database.inTransaction(connection -> {
                if (intent.dataVersion() != inventory.dataVersion()) {
                    deliveries.transitionIntent(connection, intent.txId(), intent.state(), IntentState.MANUAL, now);
                    market.event(
                            connection, now, "RECOVERY_MANUAL", player, intent.listingId(), intent.tradeId(), intent.txId(),
                            "the server data version changed since this operation started");
                    return null;
                }
                if ("CLAIM".equals(intent.op())) {
                    resolveInterruptedClaim(connection, intent, removalSurvived, now);
                } else {
                    resolveInterruptedTake(connection, intent, removalSurvived, player, now);
                }
                return null;
            });
        }
    }

    private void resolveInterruptedClaim(Connection connection, Intent intent, boolean inventoryChanged, Instant now) {
        long deliveryId = Long.parseLong(intent.detail());
        PendingDelivery delivery = deliveries.delivery(connection, deliveryId).orElse(null);
        if (delivery == null) {
            deliveries.transitionIntent(connection, intent.txId(), intent.state(), IntentState.MANUAL, now);
            return;
        }
        if (inventoryChanged) {
            // the items did reach the player before the server stopped
            deliveries.markClaimed(connection, deliveryId, intent.txId(), now);
            market.move(
                    connection, intent.txId(), delivery.item().itemUid(), Holder.pending(deliveryId), Holder.player(intent.playerUuid()),
                    delivery.item().amount(), now);
        } else {
            deliveries.revertClaiming(connection, deliveryId, intent.txId());
        }
        deliveries.transitionIntent(connection, intent.txId(), intent.state(), IntentState.FINALIZED, now);
        market.event(
                connection, now, "RECOVERY", intent.playerUuid(), null, null, intent.txId(),
                inventoryChanged ? "interrupted handover finished" : "interrupted handover queued again");
    }

    private void resolveInterruptedTake(Connection connection, Intent intent, boolean removalSurvived, UUID player, Instant now) {
        if (!removalSurvived) {
            deliveries.transitionIntent(connection, intent.txId(), intent.state(), IntentState.ABORTED, now);
            market.event(
                    connection, now, "RECOVERY", player, intent.listingId(), intent.tradeId(), intent.txId(),
                    "the items never left the inventory, nothing was created");
            return;
        }
        // the items are gone from the player but the escrow was never written: give them back through the queue
        int returned = 0;
        for (String itemUid : intent.detail().split(",")) {
            if (itemUid.isBlank() || hasMovement(connection, itemUid)) {
                continue;
            }
            long deliveryId = deliveries.insertDelivery(connection, player, itemUid, DeliveryReason.RECOVERED, null, now);
            market.move(connection, intent.txId(), itemUid, Holder.player(player), Holder.pending(deliveryId), amountOf(connection, itemUid), now);
            returned++;
        }
        deliveries.transitionIntent(connection, intent.txId(), intent.state(), IntentState.FINALIZED, now);
        market.event(
                connection, now, "RECOVERY", player, intent.listingId(), intent.tradeId(), intent.txId(),
                returned + " stack(s) returned after an interrupted operation");
    }

    private boolean hasMovement(Connection connection, String itemUid) {
        try (var statement = connection.prepareStatement("SELECT 1 FROM item_movements WHERE item_uid = ? LIMIT 1")) {
            statement.setString(1, itemUid);
            try (var row = statement.executeQuery()) {
                return row.next();
            }
        } catch (java.sql.SQLException failure) {
            throw new site.vinoff.market.storage.StorageException("Could not check the ledger for " + itemUid, failure);
        }
    }

    // helpers ------------------------------------------------------------------------------------------------------

    private Listing requireListing(long id) {
        return listing(id).orElseThrow(() -> new MarketException(MarketError.LISTING_NOT_FOUND, "There is no listing #" + id));
    }

    private Trade requireTrade(long id) {
        return trade(id).orElseThrow(() -> new MarketException(MarketError.TRADE_NOT_FOUND, "There is no trade #" + id));
    }

    private void requireOwner(Listing listing, UUID actor) {
        if (!listing.ownedBy(actor)) {
            throw new MarketException(MarketError.NOT_OWNER, "This listing is not yours");
        }
    }

    private static String newTx() {
        return UUID.randomUUID().toString();
    }

    /** What startup recovery did, for the log and the admin command. */
    public record RecoveryReport(int escrowReturned, int handoversAwaitingLogin) {

        public boolean anything() {
            return escrowReturned > 0 || handoversAwaitingLogin > 0;
        }
    }

    /** Short description of a listing for chat and the API, without exposing item bytes. */
    public static String describe(Listing listing) {
        String offered = listing.offered().stream().map(item -> item.item().summary()).collect(Collectors.joining(", "));
        String wanted = listing.wanted().stream().map(item -> item.item().summary()).collect(Collectors.joining(", "));
        return switch (listing.type()) {
            case GIVEAWAY -> "gives away " + offered;
            case GIFT -> "gift: " + offered;
            case TRADE -> "offers " + offered + " for " + wanted;
            case WANTED -> "looks for " + wanted + " and offers " + offered;
        };
    }
}
