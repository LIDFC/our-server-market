package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static site.vinoff.market.core.MarketFixture.item;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens when the server stops in the middle of something. Every test asserts the same thing in the end: the
 * items exist exactly once, either with the player, in escrow or in the delivery queue.
 */
class RecoveryTest {

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

    /** Items with the player, held in escrow or waiting in the queue. Must never change on its own. */
    private int totalItems() {
        return fixture.inventory.countOf(fixture.alice)
                + fixture.count("SELECT COALESCE(SUM(i.amount), 0) FROM escrow_items e JOIN items i ON i.item_uid = e.item_uid"
                        + " WHERE e.state = 'HELD'")
                + fixture.count("SELECT COALESCE(SUM(i.amount), 0) FROM pending_deliveries d JOIN items i ON i.item_uid = d.item_uid"
                        + " WHERE d.state <> 'CLAIMED'");
    }

    @Test
    @DisplayName("a crash before the items left the inventory leaves the player with their items")
    void crashBeforeRemoval() {
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);

        fixture.inventory.crashOn("remove");
        assertThrows(IllegalStateException.class, () -> market.addOffer(fixture.alice, listing, List.of(diamonds)));

        fixture.restart();
        fixture.service().recoverAtStartup();
        fixture.service().recoverPlayer(fixture.alice);

        assertEquals(16, fixture.inventory.countOf(fixture.alice), "the items never left");
        assertEquals(0, fixture.escrowCount());
        assertEquals(0, fixture.deliveryCount(fixture.alice));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM intents WHERE state = 'ABORTED'"));
        assertEquals(16, totalItems());
    }

    @Test
    @DisplayName("a crash after the items left the inventory gives them back through the queue")
    void crashAfterRemoval() {
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);

        // the removal goes through and then the server dies before the escrow is written
        fixture.inventory.crashOn("save");
        assertThrows(IllegalStateException.class, () -> market.addOffer(fixture.alice, listing, List.of(diamonds)));
        assertEquals(0, fixture.inventory.countOf(fixture.alice), "the items are gone from the inventory");
        assertEquals(0, fixture.escrowCount(), "and were never recorded as escrow");

        fixture.restart();
        fixture.service().recoverAtStartup();
        fixture.service().recoverPlayer(fixture.alice);

        assertEquals(1, fixture.deliveryCount(fixture.alice), "recovery put them in the queue");
        assertEquals(16, totalItems(), "nothing was lost and nothing was duplicated");
        assertEquals(1, fixture.service().claimDeliveries(fixture.alice));
        assertEquals(16, fixture.inventory.countOf(fixture.alice));
    }

    @Test
    @DisplayName("recovery runs twice without handing anything over twice")
    void recoveryIsIdempotent() {
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        fixture.inventory.crashOn("save");
        assertThrows(IllegalStateException.class, () -> market.addOffer(fixture.alice, listing, List.of(diamonds)));

        fixture.restart();
        fixture.service().recoverPlayer(fixture.alice);
        fixture.service().recoverPlayer(fixture.alice);
        fixture.service().recoverAtStartup();

        assertEquals(1, fixture.deliveryCount(fixture.alice));
        assertEquals(16, totalItems());
    }

    @Test
    @DisplayName("a crash while handing items over puts the delivery back in the queue")
    void crashWhileHandingOver() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);
        market.cancel(fixture.alice, listing, false);

        fixture.inventory.crashOn("add");
        market.claimDeliveries(fixture.alice);

        assertEquals(0, fixture.inventory.countOf(fixture.alice), "the items did not reach the player");
        assertEquals(1, fixture.deliveryCount(fixture.alice), "they are still waiting");
        assertEquals(64, totalItems());

        assertEquals(1, market.claimDeliveries(fixture.alice), "a second try works");
        assertEquals(64, fixture.inventory.countOf(fixture.alice));
    }

    @Test
    @DisplayName("a crash after the items arrived does not deliver them a second time")
    void crashAfterItemsArrived() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);
        market.cancel(fixture.alice, listing, false);

        // the items land in the inventory and the server dies before the delivery is written off
        fixture.inventory.crashOn("save");
        assertThrows(IllegalStateException.class, () -> market.claimDeliveries(fixture.alice));
        assertEquals(64, fixture.inventory.countOf(fixture.alice), "the player has them");

        fixture.restart();
        fixture.service().recoverAtStartup();
        fixture.service().recoverPlayer(fixture.alice);

        assertEquals(0, fixture.deliveryCount(fixture.alice), "the queue knows they were handed over");
        assertEquals(64, totalItems(), "and they were not handed over twice");
        assertEquals(0, fixture.service().claimDeliveries(fixture.alice));
        assertEquals(64, fixture.inventory.countOf(fixture.alice));
    }

    @Test
    @DisplayName("escrow left behind by a finished listing is returned at startup")
    void orphanedEscrowIsReturned() {
        ItemBlob iron = item("iron_ingot", 64);
        fixture.inventory.give(fixture.alice, iron);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(iron));
        market.publish(fixture.alice, listing);

        // as if a crash had left the listing finished with its escrow still held
        fixture.database().inTransaction(connection -> {
            try (var statement = connection.prepareStatement("UPDATE listings SET state = 'COMPLETED' WHERE id = ?")) {
                statement.setLong(1, listing);
                statement.executeUpdate();
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return null;
        });

        fixture.restart();
        MarketService.RecoveryReport report = fixture.service().recoverAtStartup();

        assertEquals(1, report.escrowReturned());
        assertTrue(report.anything());
        assertEquals(0, fixture.escrowCount());
        assertEquals(1, fixture.deliveryCount(fixture.alice));
        assertEquals(64, totalItems());
    }

    @Test
    @DisplayName("a server rolled back to an older version is left for an administrator instead of guessed at")
    void differentDataVersionIsNotGuessed() {
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null);
        fixture.inventory.crashOn("save");
        assertThrows(IllegalStateException.class, () -> market.addOffer(fixture.alice, listing, List.of(diamonds)));

        fixture.restart();
        fixture.inventory.setDataVersion(4000);
        fixture.service().recoverPlayer(fixture.alice);

        assertEquals(1, fixture.count("SELECT COUNT(*) FROM intents WHERE state = 'MANUAL'"));
        assertEquals(0, fixture.deliveryCount(fixture.alice), "nothing was handed out on a guess");
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM events WHERE type = 'RECOVERY_MANUAL'"));
    }

    @Test
    @DisplayName("an interrupted trade offer does not leave the listing stuck")
    void interruptedOfferReleasesTheListing() {
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);
        long listing = market.createDraft(fixture.alice, "Alice", ListingType.TRADE, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(diamonds));
        market.addWanted(fixture.alice, listing, List.of(item("gold_ingot", 32)));
        market.publish(fixture.alice, listing);

        ItemBlob gold = item("gold_ingot", 32);
        fixture.inventory.give(fixture.bob, gold);
        fixture.inventory.crashOn("remove");
        assertThrows(IllegalStateException.class, () -> market.offerTrade(fixture.bob, "Bob", listing, List.of(gold)));

        assertEquals(ListingState.ACTIVE, market.listing(listing).orElseThrow().state(), "the listing is open again");
        assertEquals(32, fixture.inventory.countOf(fixture.bob), "the buyer kept their items");

        // and somebody else can trade for it right away
        fixture.inventory.give(fixture.carol, item("gold_ingot", 32));
        long trade = market.offerTrade(fixture.carol, "Carol", listing, List.of(item("gold_ingot", 32)));
        assertEquals(TradeState.PENDING, market.trade(trade).orElseThrow().state());
    }
}
