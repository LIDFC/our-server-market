package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.chest.ChestKind;
import site.vinoff.market.core.chest.ChestPlan;
import site.vinoff.market.core.model.Listing;

/**
 * Answering somebody else's listing out of your own chest.
 *
 * <p>The half worth testing is not the happy path but what happens when the chest refuses after the listing has
 * already been won. An offer claims the listing first, because losing that race should cost nothing; if the items
 * then fail to arrive, the listing has to go back on the market rather than sit in {@code PENDING_TRADE} forever
 * with nobody able to touch it.
 */
class ChestOfferTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes(StandardCharsets.UTF_8));

    private MarketFixture fixture;
    private MarketService market;
    private long bobsChest;
    private long listingId;

    @BeforeEach
    void start() {
        fixture = new MarketFixture();
        market = fixture.service();

        // Alice puts up a trade out of her own chest, the way the website does it
        long aliceChest = market.bindChest(fixture.alice, "Alice", WORLD, 10, 64, -20, ChestKind.SINGLE, null);
        fixture.containers.put(aliceChest, 0, FakeContainer.item("diamond", 64));
        String digest = market.readChest(fixture.alice).snapshot().digest();
        String hash = market.readChest(fixture.alice).snapshot().at(0).orElseThrow().sha256();
        listingId = market.createListingFromChest(
                fixture.alice, "Alice", ListingType.TRADE, null, null, null, digest,
                new ChestPlan(List.of(new ChestPlan.Take(0, hash, 64))),
                List.of(MarketFixture.item("netherite_ingot", 4)));

        // Bob has a chest of his own to pay with
        bobsChest = market.bindChest(fixture.bob, "Bob", WORLD, 30, 64, 30, ChestKind.SINGLE, null);
        fixture.containers.put(bobsChest, 2, FakeContainer.item("netherite_ingot", 8));
    }

    @AfterEach
    void stop() {
        fixture.assertLedgerIsConsistent();
        fixture.close();
    }

    private String bobsDigest() {
        return market.readChest(fixture.bob).snapshot().digest();
    }

    private ChestPlan bobsPlan(int amount) {
        String hash = market.readChest(fixture.bob).snapshot().at(2).orElseThrow().sha256();
        return new ChestPlan(List.of(new ChestPlan.Take(2, hash, amount)));
    }

    @Test
    @DisplayName("an offer paid out of a chest reaches escrow, and the ledger says it came from the world")
    void offerFromChest() {
        long tradeId = market.offerTradeFromChest(fixture.bob, "Bob", listingId, bobsDigest(), bobsPlan(4));

        assertEquals(ListingState.PENDING_TRADE, market.listing(listingId).orElseThrow().state());
        assertEquals(TradeState.PENDING, market.trade(tradeId).orElseThrow().state());
        assertEquals(2, fixture.escrowCount(), "both halves are held by the marketplace");
        assertEquals(4, fixture.containers.contentsOf(bobsChest).get(2).count(), "half the stack stayed behind");
        assertEquals(
                1,
                fixture.count("SELECT COUNT(*) FROM item_movements WHERE from_holder = 'CHEST:" + bobsChest
                        + "' AND to_holder = 'TRADE:" + tradeId + "'"),
                "the offer came out of the world, not out of a player file");
    }

    @Test
    @DisplayName("the trade completes and each side gets the other side's items")
    void theWholeExchange() {
        long tradeId = market.offerTradeFromChest(fixture.bob, "Bob", listingId, bobsDigest(), bobsPlan(4));
        market.acceptTrade(fixture.alice, tradeId);
        market.confirmTrade(fixture.alice, tradeId);
        assertTrue(market.confirmTrade(fixture.bob, tradeId), "the second confirmation finishes it");

        assertEquals(TradeState.COMPLETED, market.trade(tradeId).orElseThrow().state());
        assertEquals(0, fixture.escrowCount(), "nothing is held any more");
        assertEquals(1, fixture.deliveryCount(fixture.alice), "the ingots are waiting for Alice");
        assertEquals(1, fixture.deliveryCount(fixture.bob), "the diamonds are waiting for Bob");
    }

    @Test
    @DisplayName("a chest that moved under the request leaves the listing on the market, not stuck")
    void refusedOfferGivesTheListingBack() {
        String stale = bobsDigest();
        fixture.containers.put(bobsChest, 9, FakeContainer.item("bread", 2));

        MarketException refused = assertThrows(
                MarketException.class,
                () -> market.offerTradeFromChest(fixture.bob, "Bob", listingId, stale, bobsPlan(4)));

        assertEquals(MarketError.CHEST_CHANGED, refused.error());
        Listing listing = market.listing(listingId).orElseThrow();
        assertEquals(ListingState.ACTIVE, listing.state(), "somebody else can still trade for it");
        assertEquals(1, fixture.escrowCount(), "only Alice's half is held");
        assertEquals(10, fixture.containers.contentsOf(bobsChest).get(2).count(), "nothing left the chest");
    }

    @Test
    @DisplayName("a buyer with no chest is told so before the listing is touched")
    void noChestOfYourOwn() {
        MarketException refused = assertThrows(
                MarketException.class,
                () -> market.offerTradeFromChest(fixture.carol, "Carol", listingId, "whatever", bobsPlan(1)));

        assertEquals(MarketError.CHEST_NOT_BOUND, refused.error());
        assertEquals(ListingState.ACTIVE, market.listing(listingId).orElseThrow().state());
    }

    @Test
    @DisplayName("your own listing, and a free one, are refused for what they are")
    void wrongListings() {
        assertEquals(
                MarketError.OWN_LISTING,
                assertThrows(
                                MarketException.class,
                                () -> market.offerTradeFromChest(fixture.alice, "Alice", listingId, "x", bobsPlan(1)))
                        .error());

        long aliceChest = market.chestOf(fixture.alice).orElseThrow().id();
        fixture.containers.put(aliceChest, 5, FakeContainer.item("apple", 3));
        String digest = market.readChest(fixture.alice).snapshot().digest();
        String hash = market.readChest(fixture.alice).snapshot().at(5).orElseThrow().sha256();
        long giveaway = market.createListingFromChest(
                fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null, digest,
                new ChestPlan(List.of(new ChestPlan.Take(5, hash, 3))), List.of());

        assertEquals(
                MarketError.INVALID_REQUEST,
                assertThrows(
                                MarketException.class,
                                () -> market.offerTradeFromChest(fixture.bob, "Bob", giveaway, bobsDigest(), bobsPlan(1)))
                        .error());
        assertEquals(ListingState.ACTIVE, market.listing(giveaway).orElseThrow().state());
    }

    @Test
    @DisplayName("a free listing is taken without a chest at all, and waits in the queue")
    void takingAGiveaway() {
        long aliceChest = market.chestOf(fixture.alice).orElseThrow().id();
        fixture.containers.put(aliceChest, 5, FakeContainer.item("apple", 3));
        String digest = market.readChest(fixture.alice).snapshot().digest();
        String hash = market.readChest(fixture.alice).snapshot().at(5).orElseThrow().sha256();
        long giveaway = market.createListingFromChest(
                fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null, digest,
                new ChestPlan(List.of(new ChestPlan.Take(5, hash, 3))), List.of());

        market.claim(fixture.carol, "Carol", giveaway);

        assertEquals(ListingState.COMPLETED, market.listing(giveaway).orElseThrow().state());
        assertEquals(1, fixture.deliveryCount(fixture.carol), "collected in the game, like everything else");
    }

    @Test
    @DisplayName("two buyers, one listing: the second is refused and keeps everything")
    void twoBuyers() {
        long carolChest = market.bindChest(fixture.carol, "Carol", WORLD, 50, 64, 50, ChestKind.SINGLE, null);
        fixture.containers.put(carolChest, 0, FakeContainer.item("netherite_ingot", 4));
        String carolDigest = market.readChest(fixture.carol).snapshot().digest();
        String carolHash = market.readChest(fixture.carol).snapshot().at(0).orElseThrow().sha256();

        market.offerTradeFromChest(fixture.bob, "Bob", listingId, bobsDigest(), bobsPlan(4));

        MarketException refused = assertThrows(
                MarketException.class,
                () -> market.offerTradeFromChest(
                        fixture.carol, "Carol", listingId, carolDigest,
                        new ChestPlan(List.of(new ChestPlan.Take(0, carolHash, 4)))));
        assertEquals(MarketError.LISTING_ALREADY_TAKEN, refused.error());
        assertEquals(4, fixture.containers.contentsOf(carolChest).get(0).count(), "Carol still has her ingots");
    }
}
