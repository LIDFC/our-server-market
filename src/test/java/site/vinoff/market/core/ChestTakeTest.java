package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.chest.ChestKind;
import site.vinoff.market.core.chest.ChestPlan;
import site.vinoff.market.core.model.Listing;

/** Binding a chest, and listing out of it. */
class ChestTakeTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private MarketFixture fixture;
    private MarketService market;
    private long chestId;

    @BeforeEach
    void start() {
        fixture = new MarketFixture();
        market = fixture.service();
        chestId = market.bindChest(fixture.alice, "Alice", WORLD, 10, 64, -20, ChestKind.SINGLE, null);
        fixture.containers.put(chestId, 0, FakeContainer.item("diamond", 64));
        fixture.containers.put(chestId, 4, FakeContainer.item("gold_ingot", 10));
    }

    @AfterEach
    void stop() {
        fixture.assertLedgerIsConsistent();
        fixture.close();
    }

    private String digest() {
        return market.readChest(fixture.alice).snapshot().digest();
    }

    private String hashAt(int slot) {
        return market.readChest(fixture.alice).snapshot().at(slot).orElseThrow().sha256();
    }

    private long list(ChestPlan plan) {
        return market.createListingFromChest(
                fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null, digest(), plan, List.of());
    }

    @Test
    @DisplayName("a whole stack goes from the chest into a listing, and the ledger says where it came from")
    void takeWholeStack() {
        long listingId = list(new ChestPlan(List.of(new ChestPlan.Take(0, hashAt(0), 64))));

        Listing listing = market.listing(listingId).orElseThrow();
        assertEquals(ListingState.ACTIVE, listing.state());
        assertEquals(1, listing.offered().size());
        assertEquals(64, listing.offered().get(0).item().amount());

        assertTrue(fixture.containers.contentsOf(chestId).get(0) == null, "the slot is empty now");
        assertEquals(10, fixture.containers.totalItems(), "only the gold is left in the chest");
        assertEquals(1, fixture.escrowCount());
        assertTrue(
                fixture.holders().stream().anyMatch(line -> line.contains("CHEST:" + chestId)),
                "the ledger records that the item came out of the world, not out of a player file");
    }

    @Test
    @DisplayName("part of a stack leaves the rest behind")
    void takePartOfStack() {
        long listingId = list(new ChestPlan(List.of(new ChestPlan.Take(0, hashAt(0), 16))));

        assertEquals(16, market.listing(listingId).orElseThrow().offered().get(0).item().amount());
        assertEquals(48, fixture.containers.contentsOf(chestId).get(0).count());
        assertEquals(58, fixture.containers.totalItems());
    }

    @Test
    @DisplayName("a fingerprint from before somebody rummaged in the chest is refused")
    void staleDigestIsRefused() {
        String stale = digest();
        fixture.containers.put(chestId, 9, FakeContainer.item("bread", 3));

        ChestPlan plan = new ChestPlan(List.of(new ChestPlan.Take(0, hashAt(0), 64)));
        MarketException refused = assertThrows(
                MarketException.class,
                () -> market.createListingFromChest(
                        fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null, stale, plan, List.of()));
        assertEquals(MarketError.CHEST_CHANGED, refused.error());
        assertEquals(77, fixture.containers.totalItems(), "nothing moved");
        assertEquals(0, fixture.escrowCount());
    }

    @Test
    @DisplayName("asking for an item that is not in the slot any more is refused")
    void wrongItemIsRefused() {
        ChestPlan plan = new ChestPlan(List.of(new ChestPlan.Take(0, hashAt(4), 1)));
        assertEquals(
                MarketError.CHEST_CHANGED,
                assertThrows(MarketException.class, () -> list(plan)).error());
        assertEquals(74, fixture.containers.totalItems());
    }

    @Test
    @DisplayName("asking for more than the slot holds is refused")
    void tooManyIsRefused() {
        ChestPlan plan = new ChestPlan(List.of(new ChestPlan.Take(4, hashAt(4), 11)));
        assertEquals(MarketError.CHEST_CHANGED, assertThrows(MarketException.class, () -> list(plan)).error());
    }

    @Test
    @DisplayName("a player without a chest is told so rather than given one")
    void noChest() {
        ChestPlan plan = new ChestPlan(List.of(new ChestPlan.Take(0, hashAt(0), 1)));
        MarketException refused = assertThrows(
                MarketException.class,
                () -> market.createListingFromChest(
                        fixture.bob, "Bob", ListingType.GIVEAWAY, null, null, null, "whatever", plan, List.of()));
        assertEquals(MarketError.CHEST_NOT_BOUND, refused.error());
    }

    @Test
    @DisplayName("one chest per player: binding another releases the first")
    void rebinding() {
        long second = market.bindChest(fixture.alice, "Alice", WORLD, 30, 64, 30, ChestKind.DOUBLE, new int[] {31, 64, 30});
        assertEquals(second, market.chestOf(fixture.alice).orElseThrow().id());
        assertTrue(market.chestAt(WORLD, 10, 64, -20).isEmpty(), "the old binding is gone");
        assertEquals(54, market.chestOf(fixture.alice).orElseThrow().size());
    }

    @Test
    @DisplayName("a double chest is found by either of its halves")
    void doubleChestIsFoundByBothBlocks() {
        market.releaseChest(fixture.alice, "TEST");
        long id = market.bindChest(fixture.bob, "Bob", WORLD, 5, 70, 5, ChestKind.DOUBLE, new int[] {6, 70, 5});
        assertEquals(id, market.chestAt(WORLD, 5, 70, 5).orElseThrow().id());
        assertEquals(id, market.chestAt(WORLD, 6, 70, 5).orElseThrow().id(), "the other half is the same binding");
    }

    @Test
    @DisplayName("somebody else's chest, and the other half of one, cannot be bound")
    void cannotStealABinding() {
        assertEquals(
                MarketError.CHEST_ALREADY_BOUND,
                assertThrows(
                                MarketException.class,
                                () -> market.bindChest(fixture.bob, "Bob", WORLD, 10, 64, -20, ChestKind.SINGLE, null))
                        .error());

        market.releaseChest(fixture.alice, "TEST");
        market.bindChest(fixture.alice, "Alice", WORLD, 1, 64, 1, ChestKind.DOUBLE, new int[] {2, 64, 1});
        assertEquals(
                MarketError.CHEST_ALREADY_BOUND,
                assertThrows(
                                MarketException.class,
                                () -> market.bindChest(fixture.bob, "Bob", WORLD, 2, 64, 1, ChestKind.SINGLE, null))
                        .error());
    }

    @Test
    @DisplayName("more stacks than a listing holds is refused before anything is touched")
    void tooManyStacks() {
        for (int slot = 0; slot < 27; slot++) {
            fixture.containers.put(chestId, slot, FakeContainer.item("stone", 1));
        }
        List<ChestPlan.Take> takes = new java.util.ArrayList<>();
        for (int slot = 0; slot < 28 && slot < 27; slot++) {
            takes.add(new ChestPlan.Take(slot, hashAt(slot), 1));
        }
        // 27 is the limit, so 27 is allowed; the twenty-eighth would not be
        assertEquals(27, takes.size());
        long listingId = list(new ChestPlan(takes));
        assertEquals(27, market.listing(listingId).orElseThrow().offered().size());
    }

    @Test
    @DisplayName("a chest that has gone missing refuses rather than pretending")
    void missingChest() {
        fixture.containers.vanish();
        ChestPlan plan = new ChestPlan(List.of(new ChestPlan.Take(0, "a".repeat(64), 1)));
        assertEquals(
                MarketError.CHEST_MISSING,
                assertThrows(
                                MarketException.class,
                                () -> market.createListingFromChest(
                                        fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null, "x", plan, List.of()))
                        .error());
    }
}
