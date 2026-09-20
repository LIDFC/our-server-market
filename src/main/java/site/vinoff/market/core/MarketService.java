package site.vinoff.market.core;

import java.sql.Connection;
import java.time.Duration;
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
import site.vinoff.market.core.chest.ChestContents;
import site.vinoff.market.core.chest.ChestKind;
import site.vinoff.market.core.chest.ChestPlan;
import site.vinoff.market.core.chest.ChestSnapshot;
import site.vinoff.market.core.chest.ChestState;
import site.vinoff.market.core.model.BoundChest;
import site.vinoff.market.core.model.ChestIntent;
import site.vinoff.market.core.port.ContainerPort;
import site.vinoff.market.core.port.InventoryPort;
import site.vinoff.market.core.port.MarketClock;
import site.vinoff.market.storage.ChestRepository;
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
    private final ChestRepository chests;
    private final InventoryPort inventory;
    private final ContainerPort containers;
    private final MarketClock clock;
    private final Logger log;

    public MarketService(
            Database database,
            MarketRepository market,
            DeliveryRepository deliveries,
            ChestRepository chests,
            InventoryPort inventory,
            ContainerPort containers,
            MarketClock clock,
            Logger log) {
        this.database = database;
        this.market = market;
        this.deliveries = deliveries;
        this.chests = chests;
        this.inventory = inventory;
        this.containers = containers;
        this.clock = clock;
        this.log = log;
    }

    // identities ---------------------------------------------------------------------------------------------------

    public Identity seePlayer(UUID uuid, String name) {
        Instant now = clock.now();
        return database.inTransaction(connection -> market.touchIdentity(connection, uuid, name, now));
    }

    /** The name the marketplace last saw a player under, which is the only name it will write down for them. */
    public Optional<Identity> identity(UUID uuid) {
        return database.read(connection -> market.identity(connection, uuid));
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

    // bound chests -------------------------------------------------------------------------------------------------

    /**
     * Makes a chest a player's marketplace stock.
     *
     * <p>Binding stays something done in the game, looking at the block, and is deliberately not offered over HTTP:
     * a browser has no way to point at a block, and "which chest did you mean" is not a question worth answering
     * from one.
     */
    public long bindChest(UUID owner, String ownerName, UUID world, int x, int y, int z, ChestKind kind, int[] pair) {
        Instant now = clock.now();
        return database.inTransaction(connection -> {
            market.touchIdentity(connection, owner, ownerName, now);
            if (chests.byBlock(connection, world, x, y, z).isPresent()) {
                throw new MarketException(MarketError.CHEST_ALREADY_BOUND, "This chest is already somebody's stock");
            }
            if (pair != null && chests.byBlock(connection, world, pair[0], pair[1], pair[2]).isPresent()) {
                // the other half is already bound: binding this one would give two players one box
                throw new MarketException(MarketError.CHEST_ALREADY_BOUND, "The other half of this chest is bound");
            }
            chests.byOwner(connection, owner).ifPresent(previous -> {
                if (chests.hasOpenIntent(connection, previous.id())) {
                    throw new MarketException(MarketError.CHEST_LOCKED, "Your current chest has an operation in flight");
                }
                chests.release(connection, previous.id(), ChestState.RELEASED, "REBOUND", now);
                market.event(connection, now, "CHEST_RELEASED", owner, null, null, null, "rebound");
            });
            long id = chests.bind(connection, owner, world, x, y, z, kind, pair, now);
            // agreed with from the moment it is bound: nothing has happened to it yet
            chests.markVerified(connection, id, database.bootId(), now);
            market.event(connection, now, "CHEST_BOUND", owner, null, null, null, kind.name() + " " + x + "," + y + "," + z);
            return id;
        });
    }

    /** Lets a chest go. Refused while an operation is open on it: that operation still has to be settled. */
    public void releaseChest(UUID owner, String reason) {
        Instant now = clock.now();
        database.inTransaction(connection -> {
            BoundChest chest = chests.byOwner(connection, owner)
                    .orElseThrow(() -> new MarketException(MarketError.CHEST_NOT_BOUND, "You have no chest bound"));
            if (chests.hasOpenIntent(connection, chest.id())) {
                throw new MarketException(MarketError.CHEST_LOCKED, "This chest has an operation in flight");
            }
            chests.release(connection, chest.id(), ChestState.RELEASED, reason, now);
            market.event(connection, now, "CHEST_RELEASED", owner, null, null, null, reason);
            return null;
        });
    }

    public Optional<BoundChest> chestById(long chestId) {
        return database.read(connection -> chests.byId(connection, chestId));
    }

    public Optional<BoundChest> chestOf(UUID owner) {
        return database.read(connection -> chests.byOwner(connection, owner));
    }

    public Optional<BoundChest> chestAt(UUID world, int x, int y, int z) {
        return database.read(connection -> chests.byBlock(connection, world, x, y, z));
    }

    /** Every chest the marketplace holds a binding for. Read once at startup to fill the index of blocks. */
    public List<BoundChest> boundChests() {
        return database.read(connection -> chests.bound(connection));
    }

    /** Whether an operation on this chest is still open. Asked at startup, before anybody can reach the block. */
    public boolean chestHasOpenIntent(long chestId) {
        return database.read(connection -> chests.hasOpenIntent(connection, chestId));
    }

    /** This run of the server. A chest carries the boot it was last agreed with, and disagreement means frozen. */
    public String bootId() {
        return database.bootId();
    }

    /** What is in a player's chest right now. Settles it first, so nobody is shown a rolled back world. */
    public ChestContents readChest(UUID owner) {
        return containers.read(requireUsableChest(owner));
    }

    /**
     * Creates a listing out of items in the owner's bound chest.
     *
     * <p>The third protocol of the marketplace, beside taking from a player and giving to one. It exists because a
     * chest, unlike a player file, cannot be flushed to disk on demand. So instead of forcing the world to be durable
     * before the database commits, every operation is numbered, the number is stamped on the block, and a later read
     * compares the two and repairs whichever side is behind.
     *
     * <p>{@code expectedDigest} is what the caller last saw the chest as, and it is required rather than optional:
     * without it a replay carrying a fresh idempotency key would quietly take a second helping out of a stack that
     * had only been partly emptied, because the slot would still hold the same item.
     */
    public long createListingFromChest(
            UUID owner,
            String ownerName,
            ListingType type,
            UUID recipient,
            String recipientName,
            String note,
            String expectedDigest,
            ChestPlan plan,
            List<ItemBlob> wanted) {
        if (plan.totalStacks() > MAX_ITEMS_PER_LISTING) {
            throw new MarketException(MarketError.TOO_MANY_ITEMS, "A listing holds at most " + MAX_ITEMS_PER_LISTING + " stacks");
        }
        BoundChest chest = requireUsableChest(owner);

        ChestContents before = containers.read(chest);
        if (!before.snapshot().digest().equals(expectedDigest)) {
            throw new MarketException(MarketError.CHEST_CHANGED, "The chest is not what you last saw");
        }
        before.snapshot().verify(plan);
        String postDigest = before.snapshot().apply(plan).digest();

        List<ItemBlob> taking = new ArrayList<>();
        for (ChestPlan.Take take : plan.takes()) {
            ItemBlob whole = before.itemAt(take.slot());
            if (whole == null) {
                throw new MarketException(MarketError.CHEST_CHANGED, "Slot " + take.slot() + " is empty now");
            }
            // the bytes describe one item, so a partial take is the same item carrying a smaller number
            taking.add(new ItemBlob(whole.data(), take.amount(), whole.summary()));
        }

        String txId = newTx();
        Instant started = clock.now();
        /*
         * The items are written down before anything is removed, and that ordering is the whole point: if the server
         * dies after the chest has been emptied but before the listing exists, this table is the only place those
         * items still exist, and recovery can hand them back from it.
         */
        Reserved reserved = database.inTransaction(connection -> {
            List<String> uids = new ArrayList<>();
            for (ItemBlob blob : taking) {
                uids.add(market.insertItem(connection, blob, containers.dataVersion(), started));
            }
            long seq = chests.reserveSeq(connection, chest.id());
            deliveries.insertIntent(
                    connection, txId, database.bootId(), IntentSource.CHEST, "CHEST_TAKE", owner, null, null,
                    before.snapshot().digest(), containers.dataVersion(), String.join(",", uids), started);
            chests.insertIntent(connection, txId, chest.id(), seq, before.snapshot().digest(), postDigest, plan.encode(), started);
            return new Reserved(seq, uids);
        });

        try {
            containers.take(chest, plan, reserved.seq());
        } catch (RuntimeException refused) {
            Instant failed = clock.now();
            database.inTransaction(connection -> {
                deliveries.transitionIntent(connection, txId, IntentState.INTENT, IntentState.ABORTED, failed);
                market.event(connection, failed, "CHEST_TAKE_ABORTED", owner, null, null, txId, refused.getMessage());
                return null;
            });
            throw refused;
        }

        Instant now = clock.now();
        return database.inTransaction(connection -> {
            if (!deliveries.transitionIntent(connection, txId, IntentState.INTENT, IntentState.APPLIED, now)) {
                throw new MarketException(MarketError.STORAGE_FAILURE, "The intent record disappeared");
            }
            market.touchIdentity(connection, owner, ownerName, now);
            long listingId = market.insertListing(
                    connection,
                    owner,
                    type,
                    ListingState.ACTIVE,
                    recipient,
                    recipientName == null ? null : recipientName.toLowerCase(java.util.Locale.ROOT),
                    note,
                    now,
                    null);
            int position = 0;
            for (String itemUid : reserved.uids()) {
                market.addListingItem(connection, listingId, ItemRole.OFFERED, position++, itemUid);
                market.insertEscrow(connection, itemUid, owner, listingId, null, TradeParty.OWNER, now);
                market.move(
                        connection, txId, itemUid, Holder.chest(chest.id()), Holder.listing(listingId),
                        amountOf(connection, itemUid), now);
            }
            int wantedPosition = 0;
            for (ItemBlob blob : wanted) {
                String itemUid = market.insertItem(connection, blob, containers.dataVersion(), now);
                market.addListingItem(connection, listingId, ItemRole.WANTED, wantedPosition++, itemUid);
            }
            chests.markApplied(connection, chest.id(), reserved.seq(), now);
            chests.linkListing(connection, txId, listingId);
            deliveries.transitionIntent(connection, txId, IntentState.APPLIED, IntentState.FINALIZED, now);
            market.event(connection, now, "LISTING_CREATED", owner, listingId, null, txId, type.name() + " from chest");
            return listingId;
        });
    }

    private record Reserved(long seq, List<String> uids) {}

    /**
     * Brings one chest and the marketplace back into agreement after a restart.
     *
     * <p>This is where the numbering pays for itself. Two facts are read: what the chest holds now, and the journal
     * number stamped on the block. Against what the database believes, they say which side is stale, and only three
     * outcomes are ever allowed — abort the operation, finish it, or repeat it. Anything that matches neither the
     * fingerprint before nor the one after is left alone as {@code MANUAL}, because an extra return duplicates an item
     * exactly as surely as a missing one loses it.
     *
     * <p>Safe to run again at any time: every branch ends with the chest and the block agreeing, and a chest that
     * already agrees does nothing.
     */
    public void reconcileChest(long chestId) {
        BoundChest chest = database.read(connection -> chests.byId(connection, chestId)).orElse(null);
        if (chest == null || !chest.usable()) {
            return;
        }

        ChestContents look;
        try {
            look = containers.read(chest);
        } catch (MarketException problem) {
            if (problem.error() == MarketError.CHEST_MISSING) {
                loseChest(chest, "MISSING");
            }
            // unreachable for now: a later sweep will try again, and until then nothing may be taken from it
            return;
        }

        for (ChestIntent intent : database.read(connection -> chests.unresolved(connection, chestId))) {
            settleOpenIntent(chest, intent, look);
            look = containers.read(chest);
        }

        BoundChest after = database.read(connection -> chests.byId(connection, chestId)).orElse(null);
        if (after == null || !after.usable()) {
            return;
        }
        if (!catchUpWorld(after, look)) {
            return;
        }
        Instant now = clock.now();
        database.inTransaction(connection -> {
            chests.markVerified(connection, chestId, database.bootId(), now);
            return null;
        });
    }

    /** An operation that was open when the server stopped. Exactly one of three things happened to it. */
    private void settleOpenIntent(BoundChest chest, ChestIntent intent, ChestContents look) {
        Instant now = clock.now();
        String digest = look.snapshot().digest();
        Intent open = database.read(connection -> deliveries.intent(connection, intent.txId())).orElse(null);
        if (open == null) {
            return;
        }
        if (open.dataVersion() != containers.dataVersion()) {
            manual(chest, intent, "the server data version changed, the chest is not touched");
            return;
        }
        if (open.state() != IntentState.INTENT) {
            // the take transaction is a single commit, so nothing else should ever be seen here
            manual(chest, intent, "an unexpected intent state: " + open.state());
            return;
        }

        if (digest.equals(intent.preDigest())) {
            database.inTransaction(connection -> {
                deliveries.transitionIntent(connection, intent.txId(), IntentState.INTENT, IntentState.ABORTED, now);
                market.event(
                        connection, now, "RECOVERY", chest.ownerUuid(), null, null, intent.txId(),
                        "the chest never lost the items, nothing was created");
                return null;
            });
            return;
        }
        if (digest.equals(intent.postDigest())) {
            // the items left the chest but the listing was never written: they exist only in the items table
            database.inTransaction(connection -> {
                int returned = 0;
                for (String itemUid : open.detail().split(",")) {
                    if (itemUid.isBlank() || hasMovement(connection, itemUid)) {
                        continue;
                    }
                    long deliveryId = deliveries.insertDelivery(
                            connection, chest.ownerUuid(), itemUid, DeliveryReason.RECOVERED, null, now);
                    market.move(
                            connection, intent.txId(), itemUid, Holder.chest(chest.id()), Holder.pending(deliveryId),
                            amountOf(connection, itemUid), now);
                    returned++;
                }
                chests.markApplied(connection, chest.id(), intent.seq(), now);
                deliveries.transitionIntent(connection, intent.txId(), IntentState.INTENT, IntentState.FINALIZED, now);
                market.event(
                        connection, now, "RECOVERY", chest.ownerUuid(), null, null, intent.txId(),
                        returned + " stack(s) from the chest returned after an interrupted operation");
                return null;
            });
            return;
        }
        manual(chest, intent, "the chest matches neither the fingerprint before nor the one after");
    }

    /**
     * Repeats an operation the world lost. Returns false when the chest was left for an administrator, in which case
     * it stays unverified and therefore untouchable.
     */
    private boolean catchUpWorld(BoundChest chest, ChestContents look) {
        if (look.seq() == chest.appliedSeq()) {
            return true;
        }
        if (look.seq() > chest.appliedSeq()) {
            // the block is ahead of the database: the database was restored from a backup
            manual(chest, null, "the chest is ahead of the marketplace, seq " + look.seq() + " against " + chest.appliedSeq());
            return false;
        }

        ChestIntent intent = database.read(connection -> chests.bySeq(connection, chest.id(), chest.appliedSeq())).orElse(null);
        if (intent == null) {
            manual(chest, null, "no record of operation " + chest.appliedSeq() + ", which the chest has not seen");
            return false;
        }
        String digest = look.snapshot().digest();
        if (digest.equals(intent.postDigest())) {
            // the contents are right, only the stamp is behind
            containers.stamp(chest, chest.appliedSeq());
            return true;
        }
        if (!digest.equals(intent.preDigest())) {
            manual(chest, intent, "the chest matches neither fingerprint of operation " + chest.appliedSeq());
            return false;
        }
        /*
         * The listing exists and holds these items, but the world rolled back to before they left the chest. Left
         * alone this is a duplication: the same items in escrow and in the box. Repeating the plan is the only
         * outcome that keeps each item in one place, and it is safe because the plan says exactly which slot and
         * which item, and the chest still matches the fingerprint from before.
         */
        containers.take(chest, intent.decodedPlan(), chest.appliedSeq());
        Instant now = clock.now();
        database.inTransaction(connection -> {
            market.event(
                    connection, now, "RECOVERY", chest.ownerUuid(), intent.listingId(), null, intent.txId(),
                    "the world had rolled back; operation " + chest.appliedSeq() + " was applied again");
            return null;
        });
        return true;
    }

    private void manual(BoundChest chest, ChestIntent intent, String why) {
        Instant now = clock.now();
        log.severe("Chest " + chest.id() + " needs an administrator: " + why);
        database.inTransaction(connection -> {
            if (intent != null) {
                deliveries.transitionIntent(connection, intent.txId(), IntentState.INTENT, IntentState.MANUAL, now);
            }
            market.event(
                    connection, now, "RECOVERY_MANUAL", chest.ownerUuid(), intent == null ? null : intent.listingId(),
                    null, intent == null ? null : intent.txId(), why);
            return null;
        });
    }

    /** The block is not the chest it was. Nothing of the marketplace was in it, but the owner should hear about it. */
    private void loseChest(BoundChest chest, String reason) {
        Instant now = clock.now();
        log.warning("The bound chest of " + chest.ownerUuid() + " is gone (" + reason + ")");
        database.inTransaction(connection -> {
            chests.release(connection, chest.id(), ChestState.BROKEN, reason, now);
            market.event(connection, now, "CHEST_LOST", chest.ownerUuid(), null, null, null, reason);
            return null;
        });
    }

    private BoundChest requireUsableChest(UUID owner) {
        BoundChest found = database.read(connection -> chests.byOwner(connection, owner))
                .orElseThrow(() -> new MarketException(MarketError.CHEST_NOT_BOUND, "You have no chest bound"));
        if (found.settling(database.bootId())) {
            long settlingId = found.id();
            reconcileChest(settlingId);
            found = database.read(connection -> chests.byId(connection, settlingId))
                    .orElseThrow(() -> new MarketException(MarketError.CHEST_MISSING, "The chest is gone"));
            if (found.usable() && found.settling(database.bootId())) {
                // the check did not end in agreement: either the world could not be reached, or the chest matches
                // neither fingerprint and an administrator has to look at it. Until then nothing leaves the box.
                throw new MarketException(MarketError.CHEST_LOCKED, "This chest has not been checked yet");
            }
        }
        if (!found.usable()) {
            throw new MarketException(MarketError.CHEST_MISSING, "That chest is no longer there");
        }
        long id = found.id();
        if (database.read(connection -> chests.hasOpenIntent(connection, id))) {
            throw new MarketException(MarketError.CHEST_BUSY, "An operation on this chest is still open");
        }
        return found;
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

    /** What one side of a trade put up. Used to show a player what they are being offered before they answer. */
    public List<StoredItem> tradeItems(long tradeId, TradeParty side) {
        return database.read(connection -> market.escrowOf(connection, null, tradeId, EscrowState.HELD).stream()
                .filter(item -> item.side() == side)
                .map(EscrowItem::item)
                .toList());
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
                    connection, txId, database.bootId(), IntentSource.PLAYER, op, player, listingId, tradeId, digest,
                    inventory.dataVersion(), String.join(",", uids), started);
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
                    connection, txId, database.bootId(), IntentSource.PLAYER, "CLAIM", player, null, null, digest,
                    inventory.dataVersion(), String.valueOf(delivery.id()), started);
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

    // expiry -------------------------------------------------------------------------------------------------------

    /**
     * Closes listings nobody touched for a long time and gives their items back. Guarded like everything else, so a
     * sweep that runs twice, or at the same moment as somebody's offer, returns each stack exactly once.
     *
     * @return how many listings were closed
     */
    public int expireListingsOlderThan(Duration age) {
        Instant now = clock.now();
        Instant cutoff = now.minus(age);
        String txId = newTx();
        return database.inTransaction(connection -> {
            int expired = 0;
            for (Listing listing : market.listings(connection, Set.of(ListingState.ACTIVE), null, null, 200, 0)) {
                if (listing.createdAt().isAfter(cutoff)) {
                    continue;
                }
                // the guard is what makes a second sweep, or a sweep racing an offer, harmless
                if (!market.transitionListing(connection, listing.id(), ListingState.ACTIVE, ListingState.EXPIRED, now)) {
                    continue;
                }
                returnEscrow(
                        connection,
                        market.escrowOf(connection, listing.id(), null, EscrowState.HELD),
                        DeliveryReason.LISTING_EXPIRED,
                        txId,
                        now);
                market.event(connection, now, "LISTING_EXPIRED", null, listing.id(), null, txId, null);
                expired++;
            }
            return expired;
        });
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
