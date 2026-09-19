package site.vinoff.market.core.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;

/**
 * The fingerprint and the plan, as pure functions. Everything the marketplace knows about a chest it cannot see is
 * decided here, so this is where it is checked.
 */
class ChestSnapshotTest {

    private static final String DIAMOND = "a".repeat(64);
    private static final String GOLD = "b".repeat(64);

    private static ChestSlot slot(int slot, String sha, int amount) {
        return new ChestSlot(slot, sha, amount, amount + "x item");
    }

    private static ChestSnapshot chest(ChestSlot... slots) {
        return new ChestSnapshot(27, List.of(slots));
    }

    @Test
    @DisplayName("the same contents always give the same fingerprint, whatever order they arrive in")
    void stableOrder() {
        ChestSnapshot one = chest(slot(0, DIAMOND, 64), slot(5, GOLD, 32));
        ChestSnapshot other = new ChestSnapshot(27, List.of(slot(5, GOLD, 32), slot(0, DIAMOND, 64)));
        assertEquals(one.digest(), other.digest());
    }

    @Test
    @DisplayName("moving a stack to another slot changes the fingerprint")
    void emptySlotsCount() {
        assertNotEquals(chest(slot(3, DIAMOND, 64)).digest(), chest(slot(4, DIAMOND, 64)).digest());
    }

    @Test
    @DisplayName("taking one item out of a stack changes the fingerprint")
    void amountCounts() {
        assertNotEquals(chest(slot(0, DIAMOND, 64)).digest(), chest(slot(0, DIAMOND, 63)).digest());
    }

    @Test
    @DisplayName("a chest of a different size is a different chest")
    void sizeCounts() {
        ChestSnapshot small = new ChestSnapshot(27, List.of(slot(0, DIAMOND, 64)));
        ChestSnapshot large = new ChestSnapshot(54, List.of(slot(0, DIAMOND, 64)));
        assertNotEquals(small.digest(), large.digest());
    }

    @Test
    @DisplayName("an empty chest still has a fingerprint, and it is not the empty string")
    void emptyChest() {
        assertEquals(64, ChestSnapshot.empty(27).digest().length());
        assertNotEquals(ChestSnapshot.empty(27).digest(), ChestSnapshot.empty(54).digest());
    }

    @Test
    @DisplayName("a whole stack leaves the slot empty")
    void takeWholeStack() {
        ChestSnapshot after = chest(slot(0, DIAMOND, 64), slot(1, GOLD, 8))
                .apply(new ChestPlan(List.of(new ChestPlan.Take(0, DIAMOND, 64))));
        assertTrue(after.at(0).isEmpty());
        assertEquals(8, after.at(1).orElseThrow().amount());
        assertEquals(8, after.totalItems());
    }

    @Test
    @DisplayName("part of a stack leaves the rest, with the same item in it")
    void takePartOfStack() {
        ChestSnapshot after = chest(slot(0, DIAMOND, 64)).apply(new ChestPlan(List.of(new ChestPlan.Take(0, DIAMOND, 16))));
        ChestSlot left = after.at(0).orElseThrow();
        assertEquals(48, left.amount());
        assertEquals(DIAMOND, left.sha256(), "the hash is of the item, not of the pile");
    }

    @Test
    @DisplayName("a plan for an empty slot, a changed item or too many is refused")
    void refusals() {
        ChestSnapshot box = chest(slot(0, DIAMOND, 16));
        for (ChestPlan.Take bad : List.of(
                new ChestPlan.Take(7, DIAMOND, 1),
                new ChestPlan.Take(0, GOLD, 1),
                new ChestPlan.Take(0, DIAMOND, 17))) {
            MarketException refused = assertThrows(MarketException.class, () -> box.verify(new ChestPlan(List.of(bad))));
            assertEquals(MarketError.CHEST_CHANGED, refused.error());
        }
    }

    @Test
    @DisplayName("a slot outside the chest is refused rather than read")
    void outsideTheChest() {
        MarketException refused = assertThrows(
                MarketException.class,
                () -> chest(slot(0, DIAMOND, 1)).verify(new ChestPlan(List.of(new ChestPlan.Take(30, DIAMOND, 1)))));
        assertEquals(MarketError.CHEST_CHANGED, refused.error());
    }

    @Test
    @DisplayName("one plan may not touch the same slot twice")
    void noDuplicateSlots() {
        MarketException refused = assertThrows(
                MarketException.class,
                () -> new ChestPlan(List.of(new ChestPlan.Take(0, DIAMOND, 1), new ChestPlan.Take(0, DIAMOND, 1))));
        assertEquals(MarketError.INVALID_REQUEST, refused.error());
    }

    @Test
    @DisplayName("an empty plan is not a plan")
    void emptyPlan() {
        assertEquals(MarketError.EMPTY_LISTING, assertThrows(MarketException.class, () -> new ChestPlan(List.of())).error());
    }

    @Test
    @DisplayName("a plan survives being written down and read back")
    void roundTrip() {
        ChestPlan plan = new ChestPlan(List.of(new ChestPlan.Take(0, DIAMOND, 16), new ChestPlan.Take(9, GOLD, 1)));
        assertEquals(plan, ChestPlan.decode(plan.encode()));
        assertEquals("0:" + DIAMOND + ":16;9:" + GOLD + ":1", plan.encode(), "readable during an incident");
    }

    @Test
    @DisplayName("a malformed stored plan is a storage failure, not a guess")
    void malformedPlan() {
        for (String broken : List.of("", "nonsense", "0:short:1", "0:" + DIAMOND, "x:" + DIAMOND + ":1")) {
            assertEquals(
                    MarketError.STORAGE_FAILURE,
                    assertThrows(MarketException.class, () -> ChestPlan.decode(broken)).error(),
                    broken);
        }
    }

    @Test
    @DisplayName("applying a plan gives exactly the fingerprint the chest will have")
    void appliedFingerprintMatchesReality() {
        ChestSnapshot before = chest(slot(0, DIAMOND, 64), slot(4, GOLD, 10));
        ChestPlan plan = new ChestPlan(List.of(new ChestPlan.Take(0, DIAMOND, 64), new ChestPlan.Take(4, GOLD, 4)));

        // what the marketplace predicts, against what the chest would actually look like afterwards
        assertEquals(chest(slot(4, GOLD, 6)).digest(), before.apply(plan).digest());
    }
}
