package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static site.vinoff.market.core.MarketFixture.item;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the things the security review of the finished plugin turned up: forgotten listings, the sweep
 * that closes them racing a buyer, and handing one identity's belongings to another after a rename.
 */
class AuditTest {

    private MarketFixture fixture;
    private MarketService market;

    @BeforeEach
    void start() {
        fixture = new MarketFixture();
        market = fixture.service();
    }

    @AfterEach
    void stop() {
        fixture.assertLedgerIsConsistent();
        fixture.close();
    }

    private long giveawayOf(ItemBlob blob) {
        fixture.inventory.give(fixture.alice, blob);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(blob));
        market.publish(fixture.alice, listing);
        return listing;
    }

    @Test
    @DisplayName("a forgotten listing is closed and its items go back to their owner")
    void expiry() {
        long listing = giveawayOf(item("iron_ingot", 64));

        assertEquals(0, market.expireListingsOlderThan(Duration.ofDays(7)), "a fresh listing is left alone");
        assertEquals(1, market.expireListingsOlderThan(Duration.ZERO));

        assertEquals(ListingState.EXPIRED, market.listing(listing).orElseThrow().state());
        assertEquals(0, fixture.escrowCount());
        assertEquals(1, fixture.deliveryCount(fixture.alice));
    }

    @Test
    @DisplayName("running the sweep twice returns the items once")
    void expiryIsIdempotent() {
        giveawayOf(item("iron_ingot", 64));
        market.expireListingsOlderThan(Duration.ZERO);
        market.expireListingsOlderThan(Duration.ZERO);
        market.expireListingsOlderThan(Duration.ZERO);
        assertEquals(1, fixture.deliveryCount(fixture.alice));
    }

    @Test
    @DisplayName("a listing taken a moment before the sweep is not returned to its owner as well")
    void expiryDoesNotRaceAClaim() {
        long listing = giveawayOf(item("diamond", 16));
        market.claim(fixture.bob, "Bob", listing);

        assertEquals(0, market.expireListingsOlderThan(Duration.ZERO), "an already finished listing is not touched");
        assertEquals(1, fixture.deliveryCount(fixture.bob), "the buyer keeps what they took");
        assertEquals(0, fixture.deliveryCount(fixture.alice), "and the owner gets nothing back");
    }

    @Test
    @DisplayName("a listing with a trade running on it is not swept away under the buyer")
    void expiryLeavesTradesAlone() {
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.TRADE, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(diamonds));
        market.publish(fixture.alice, listing);
        ItemBlob gold = item("gold_ingot", 32);
        fixture.inventory.give(fixture.bob, gold);
        market.offerTrade(fixture.bob, "Bob", listing, List.of(gold));

        assertEquals(0, market.expireListingsOlderThan(Duration.ZERO));
        assertEquals(ListingState.PENDING_TRADE, market.listing(listing).orElseThrow().state());
        assertEquals(2, fixture.escrowCount());
    }

    @Test
    @DisplayName("after a rename an administrator can hand everything to the new identity")
    void reassign() {
        long listing = giveawayOf(item("iron_ingot", 64));
        market.cancel(fixture.alice, listing, false);
        long second = giveawayOf(item("diamond", 16));
        UUID renamed = UUID.nameUUIDFromBytes("OfflinePlayer:AliceNew".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        market.seePlayer(renamed, "AliceNew");

        MarketService.ReassignReport report = market.reassign(fixture.alice, renamed, fixture.bob);

        assertEquals(1, report.listings(), "the open listing moved");
        assertEquals(1, report.escrowRows(), "its escrow moved with it");
        assertEquals(1, report.deliveries(), "and so did the waiting delivery");
        assertEquals(0, fixture.deliveryCount(fixture.alice));
        assertEquals(1, fixture.deliveryCount(renamed));
        assertTrue(market.listingsOf(renamed, false).stream().anyMatch(item -> item.id() == second));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM events WHERE type = 'ADMIN_REASSIGN'"), "it is written down");
    }

    @Test
    @DisplayName("belongings are never handed to somebody the server has never seen")
    void reassignChecksTheTarget() {
        giveawayOf(item("iron_ingot", 64));
        UUID stranger = UUID.randomUUID();
        MarketException failure = assertThrows(MarketException.class, () -> market.reassign(fixture.alice, stranger, fixture.bob));
        assertEquals(MarketError.PLAYER_NOT_FOUND, failure.error());
        assertEquals(1, fixture.escrowCount(), "nothing moved");

        MarketException toSelf = assertThrows(MarketException.class, () -> market.reassign(fixture.alice, fixture.alice, fixture.bob));
        assertEquals(MarketError.INVALID_REQUEST, toSelf.error());
    }

    @Test
    @DisplayName("what an administrator sees about a player")
    void inspect() {
        giveawayOf(item("iron_ingot", 64));
        String report = market.inspect(fixture.alice);
        assertTrue(report.contains("Alice"), report);
        assertTrue(report.contains("1 open listing"), report);
        assertTrue(report.contains("1 stack(s) in escrow"), report);
    }
}
