package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static site.vinoff.market.core.MarketFixture.item;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.model.StoredItem;
import site.vinoff.market.core.model.Trade;
import site.vinoff.market.core.model.TradeParty;

class TradeTest {

    private MarketFixture fixture;
    private MarketService market;
    private long listing;

    @BeforeEach
    void start() {
        fixture = new MarketFixture();
        market = fixture.service();
        ItemBlob diamonds = item("diamond", 16);
        fixture.inventory.give(fixture.alice, diamonds);
        listing = market.createDraft(fixture.alice, "Alice", ListingType.TRADE, null, null, null);
        market.addOffer(fixture.alice, listing, List.of(diamonds));
        market.addWanted(fixture.alice, listing, List.of(item("gold_ingot", 32)));
        market.publish(fixture.alice, listing);
    }

    @AfterEach
    void stop() {
        fixture.assertLedgerIsConsistent();
        fixture.close();
    }

    private long offerGold() {
        ItemBlob gold = item("gold_ingot", 32);
        fixture.inventory.give(fixture.bob, gold);
        return market.offerTrade(fixture.bob, "Bob", listing, List.of(gold));
    }

    @Test
    @DisplayName("both halves of a trade can be read back, so nobody answers an offer blind")
    void bothSidesAreVisible() {
        long trade = offerGold();

        List<String> buyerPutUp = market.tradeItems(trade, TradeParty.BUYER).stream()
                .map(StoredItem::summary)
                .toList();
        assertEquals(1, buyerPutUp.size());
        assertTrue(buyerPutUp.get(0).contains("gold_ingot"), "the owner sees what is offered: " + buyerPutUp);

        List<String> ownerPutUp = market.listing(listing).orElseThrow().offered().stream()
                .map(item -> item.item().summary())
                .toList();
        assertEquals(1, ownerPutUp.size());
        assertTrue(ownerPutUp.get(0).contains("diamond"), "the buyer sees what they would get: " + ownerPutUp);
    }

    @Test
    @DisplayName("a finished trade gives each side what the other put up")
    void completeTrade() {
        long trade = offerGold();
        assertEquals(0, fixture.inventory.countOf(fixture.bob), "the buyer's items are in escrow");
        assertEquals(2, fixture.escrowCount(), "both sides are held");

        market.acceptTrade(fixture.alice, trade);
        assertFalse(market.confirmTrade(fixture.bob, trade), "one confirmation is not enough");
        assertTrue(market.confirmTrade(fixture.alice, trade), "the second confirmation finishes it");

        assertEquals(TradeState.COMPLETED, market.trade(trade).orElseThrow().state());
        assertEquals(ListingState.COMPLETED, market.listing(listing).orElseThrow().state());
        assertEquals(0, fixture.escrowCount());

        market.claimDeliveries(fixture.alice);
        market.claimDeliveries(fixture.bob);
        assertEquals(32, fixture.inventory.countOf(fixture.alice), "Alice received the gold");
        assertEquals(16, fixture.inventory.countOf(fixture.bob), "Bob received the diamonds");
    }

    @Test
    @DisplayName("completing a trade again hands nothing over a second time")
    void completingTwiceChangesNothing() {
        long trade = offerGold();
        market.acceptTrade(fixture.alice, trade);
        market.confirmTrade(fixture.bob, trade);
        market.confirmTrade(fixture.alice, trade);

        market.completeTrade(trade);
        market.completeTrade(trade);
        market.confirmTrade(fixture.alice, trade);

        assertEquals(1, fixture.deliveryCount(fixture.alice), "one delivery each, not two");
        assertEquals(1, fixture.deliveryCount(fixture.bob));
        market.claimDeliveries(fixture.alice);
        market.claimDeliveries(fixture.bob);
        assertEquals(32, fixture.inventory.countOf(fixture.alice));
        assertEquals(16, fixture.inventory.countOf(fixture.bob));
    }

    @Test
    @DisplayName("confirming a trade is only for the two sides of it")
    void outsidersCannotConfirm() {
        long trade = offerGold();
        market.acceptTrade(fixture.alice, trade);

        MarketException failure = assertThrows(MarketException.class, () -> market.confirmTrade(fixture.carol, trade));
        assertEquals(MarketError.NOT_PARTICIPANT, failure.error());
        MarketException notOwner = assertThrows(MarketException.class, () -> market.acceptTrade(fixture.carol, trade));
        assertEquals(MarketError.NOT_OWNER, notOwner.error());
        assertEquals(2, fixture.escrowCount(), "nothing moved");
    }

    @Test
    @DisplayName("a trade cannot be confirmed before the owner accepted it")
    void confirmationNeedsAcceptanceFirst() {
        long trade = offerGold();
        MarketException failure = assertThrows(MarketException.class, () -> market.confirmTrade(fixture.bob, trade));
        assertEquals(MarketError.TRADE_NOT_ACCEPTED, failure.error());
    }

    @Test
    @DisplayName("declining gives the buyer their items back and puts the listing up again")
    void decliningReturnsTheOffer() {
        long trade = offerGold();
        market.declineTrade(fixture.alice, trade, false);

        assertEquals(TradeState.REJECTED, market.trade(trade).orElseThrow().state());
        assertEquals(ListingState.ACTIVE, market.listing(listing).orElseThrow().state(), "the listing is open again");
        assertEquals(1, fixture.deliveryCount(fixture.bob), "the buyer gets their gold back");
        assertEquals(1, fixture.escrowCount(), "the owner's items stay in escrow");

        market.claimDeliveries(fixture.bob);
        assertEquals(32, fixture.inventory.countOf(fixture.bob));
    }

    @Test
    @DisplayName("declining twice does not return the items twice")
    void decliningIsIdempotent() {
        long trade = offerGold();
        market.declineTrade(fixture.alice, trade, false);
        market.declineTrade(fixture.bob, trade, false);
        market.declineTrade(fixture.alice, trade, false);

        assertEquals(1, fixture.deliveryCount(fixture.bob));
    }

    @Test
    @DisplayName("you cannot trade with yourself")
    void ownListing() {
        fixture.inventory.give(fixture.alice, item("gold_ingot", 32));
        MarketException failure =
                assertThrows(MarketException.class, () -> market.offerTrade(fixture.alice, "Alice", listing, List.of(item("gold_ingot", 32))));
        assertEquals(MarketError.OWN_LISTING, failure.error());
    }

    @Test
    @DisplayName("two players offering at the same moment: one gets the listing, the other keeps their items")
    void concurrentOffers() throws Exception {
        fixture.inventory.give(fixture.bob, item("gold_ingot", 32));
        fixture.inventory.give(fixture.carol, item("gold_ingot", 32));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();

        Runnable bob = offerRunnable(start, winners, losers, fixture.bob, "Bob");
        Runnable carol = offerRunnable(start, winners, losers, fixture.carol, "Carol");
        Thread first = new Thread(bob);
        Thread second = new Thread(carol);
        first.start();
        second.start();
        start.countDown();
        first.join(10_000);
        second.join(10_000);

        assertEquals(1, winners.get(), "exactly one offer went through");
        assertEquals(1, losers.get(), "the other was told the listing is taken");
        assertEquals(2, fixture.escrowCount(), "only the winner's items joined the owner's");
        assertEquals(32, fixture.inventory.countOf(fixture.bob) + fixture.inventory.countOf(fixture.carol), "the loser kept their gold");
    }

    private Runnable offerRunnable(CountDownLatch start, AtomicInteger winners, AtomicInteger losers, java.util.UUID player, String name) {
        return () -> {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                market.offerTrade(player, name, listing, List.of(item("gold_ingot", 32)));
                winners.incrementAndGet();
            } catch (MarketException refused) {
                if (refused.error() == MarketError.LISTING_ALREADY_TAKEN) {
                    losers.incrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    @DisplayName("the trade remembers how many stacks it expects, and refuses to complete without them")
    void completionChecksTheEscrow() {
        long trade = offerGold();
        market.acceptTrade(fixture.alice, trade);
        // somebody releases one side behind the marketplace's back
        fixture.database().inTransaction(connection -> {
            try (var statement = connection.prepareStatement("UPDATE escrow_items SET state = 'RELEASED' WHERE side = 'BUYER'")) {
                statement.executeUpdate();
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return null;
        });

        market.confirmTrade(fixture.bob, trade);
        MarketException failure = assertThrows(MarketException.class, () -> market.confirmTrade(fixture.alice, trade));
        assertEquals(MarketError.TRADE_NOT_ACCEPTED, failure.error());
        Trade stored = market.trade(trade).orElseThrow();
        assertEquals(TradeState.CONFIRMED, stored.state(), "it stops instead of handing out half a trade");
        assertEquals(0, fixture.deliveryCount(fixture.alice));
    }
}
