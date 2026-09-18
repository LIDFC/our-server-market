package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static site.vinoff.market.core.MarketFixture.item;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.model.Listing;

class ListingAndEscrowTest {

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

    @Test
    @DisplayName("publishing a listing takes the items out of the inventory and into escrow")
    void itemsMoveIntoEscrow() {
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);

        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(diamonds));
        market.publish(fixture.alice, listing);

        assertEquals(0, fixture.inventory.countOf(fixture.alice), "the items left the inventory");
        assertEquals(1, fixture.escrowCount());
        Listing stored = market.listing(listing).orElseThrow();
        assertEquals(ListingState.ACTIVE, stored.state());
        assertEquals(1, stored.offered().size());
        assertEquals("16x diamond", stored.offered().get(0).item().summary());
    }

    @Test
    @DisplayName("an empty listing cannot be published")
    void emptyListingIsRefused() {
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        MarketException failure = assertThrows(MarketException.class, () -> market.publish(fixture.alice, listing));
        assertEquals(MarketError.EMPTY_LISTING, failure.error());
    }

    @Test
    @DisplayName("items a player does not have cannot be listed")
    void cannotListWhatYouDoNotHave() {
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        MarketException failure =
                assertThrows(MarketException.class, () -> market.addOffer(fixture.alice, listing, List.of(item("netherite_ingot", 1))));
        assertEquals(MarketError.INVALID_REQUEST, failure.error());
        assertEquals(0, fixture.escrowCount());
    }

    @Test
    @DisplayName("cancelling gives the items back through the delivery queue")
    void cancelReturnsItems() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);

        market.cancel(fixture.alice, listing, false);

        assertEquals(0, fixture.escrowCount(), "escrow is empty");
        assertEquals(1, fixture.deliveryCount(fixture.alice), "the items are waiting for their owner");
        assertEquals(0, fixture.inventory.countOf(fixture.alice), "nothing appears before the player claims it");

        assertEquals(1, market.claimDeliveries(fixture.alice));
        assertEquals(64, fixture.inventory.countOf(fixture.alice));
        assertEquals(0, fixture.deliveryCount(fixture.alice));
    }

    @Test
    @DisplayName("cancelling twice does not hand the items over twice")
    void cancelIsIdempotent() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);

        market.cancel(fixture.alice, listing, false);
        market.cancel(fixture.alice, listing, false);
        market.cancel(fixture.alice, listing, false);

        assertEquals(1, fixture.deliveryCount(fixture.alice), "one delivery, however often cancel is called");
        market.claimDeliveries(fixture.alice);
        assertEquals(64, fixture.inventory.countOf(fixture.alice));
    }

    @Test
    @DisplayName("somebody else's listing cannot be cancelled")
    void onlyTheOwnerMayCancel() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);

        MarketException failure = assertThrows(MarketException.class, () -> market.cancel(fixture.bob, listing, false));
        assertEquals(MarketError.NOT_OWNER, failure.error());
        assertEquals(1, fixture.escrowCount(), "the items stayed where they were");
        assertEquals(0, fixture.deliveryCount(fixture.bob));
    }

    @Test
    @DisplayName("a listing that does not exist is a clear refusal, not a crash")
    void unknownListing() {
        MarketException failure = assertThrows(MarketException.class, () -> market.cancel(fixture.alice, 4242, false));
        assertEquals(MarketError.LISTING_NOT_FOUND, failure.error());
    }

    @Test
    @DisplayName("a giveaway goes to the first player who takes it, and only to them")
    void giveawayHasOneWinner() {
        ItemBlob iron = item("iron_ingot", 32);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);

        market.claim(fixture.bob, "Bob", listing);
        MarketException tooLate = assertThrows(MarketException.class, () -> market.claim(fixture.carol, "Carol", listing));
        assertEquals(MarketError.LISTING_ALREADY_TAKEN, tooLate.error());

        assertEquals(1, fixture.deliveryCount(fixture.bob));
        assertEquals(0, fixture.deliveryCount(fixture.carol));
        assertEquals(0, fixture.escrowCount());
    }

    @Test
    @DisplayName("a gift only opens for the player it is addressed to")
    void giftChecksTheRecipient() {
        ItemBlob cake = item("cake", 1);
        fixture.inventory.give(fixture.alice, cake);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIFT, fixture.bob, "Bob", "happy birthday");
        market.addOffer(fixture.alice, listing, List.of(cake));
        market.publish(fixture.alice, listing);

        MarketException wrongPlayer = assertThrows(MarketException.class, () -> market.claim(fixture.carol, "Carol", listing));
        assertEquals(MarketError.NOT_RECIPIENT, wrongPlayer.error());

        market.claim(fixture.bob, "Bob", listing);
        assertEquals(1, fixture.deliveryCount(fixture.bob));
    }

    @Test
    @DisplayName("a full inventory keeps the items waiting instead of losing them")
    void fullInventoryKeepsItemsWaiting() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);
        market.cancel(fixture.alice, listing, false);

        fixture.inventory.setSlots(fixture.alice, 0);
        assertEquals(0, market.claimDeliveries(fixture.alice), "nothing fits");
        assertEquals(1, fixture.deliveryCount(fixture.alice), "and nothing was lost");

        fixture.inventory.setSlots(fixture.alice, FakeInventory.DEFAULT_SLOTS);
        assertEquals(1, market.claimDeliveries(fixture.alice));
        assertEquals(64, fixture.inventory.countOf(fixture.alice));
    }

    @Test
    @DisplayName("items are only handed over when the player is ready for them")
    void waitsUntilThePlayerIsReady() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);
        market.cancel(fixture.alice, listing, false);

        fixture.inventory.setReady(fixture.alice, false);
        assertEquals(0, market.claimDeliveries(fixture.alice));
        assertTrue(fixture.deliveryCount(fixture.alice) > 0);

        fixture.inventory.setReady(fixture.alice, true);
        assertEquals(1, market.claimDeliveries(fixture.alice));
    }

    @Test
    @DisplayName("what a player is looking for is written down without taking anything")
    void wantedItemsAreNotEscrowed() {
        ItemBlob elytra = item("elytra", 1);
        ItemBlob diamonds = item("diamond", 20);
        fixture.inventory.give(fixture.alice, diamonds);

        long listing = market.createDraft(fixture.alice, "Alice", ListingType.WANTED, null, null, null);
        market.addWanted(fixture.alice, listing, List.of(elytra));
        market.addOffer(fixture.alice, listing, List.of(diamonds));
        market.publish(fixture.alice, listing);

        Listing stored = market.listing(listing).orElseThrow();
        assertEquals(1, stored.wanted().size());
        assertEquals("1x elytra", stored.wanted().get(0).item().summary());
        assertEquals(1, fixture.escrowCount(), "only the reward is held");
        assertFalse(fixture.inventory.contentsOf(fixture.alice).contains(elytra));
    }
}
