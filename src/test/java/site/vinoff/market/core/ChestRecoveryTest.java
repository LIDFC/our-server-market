package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import site.vinoff.market.core.chest.ChestState;

/**
 * What happens when the server dies in the middle of taking something out of a chest.
 *
 * <p>One test per row of the table in the design. The rows matter because a chest cannot be flushed to disk on
 * demand: the database commits immediately, the world commits whenever the chunk is next written, and everything
 * between those two moments is a chance to either duplicate an item or lose one.
 *
 * <p>{@code FakeContainer.rollbackTo} is what makes this testable at all — it puts the contents and the stamped
 * number back together, which is exactly what a chunk that was never saved does.
 */
class ChestRecoveryTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes(StandardCharsets.UTF_8));

    private MarketFixture fixture;
    private MarketService market;
    private long chestId;

    @BeforeEach
    void start() {
        fixture = new MarketFixture();
        market = fixture.service();
        chestId = market.bindChest(fixture.alice, "Alice", WORLD, 10, 64, -20, ChestKind.SINGLE, null);
        fixture.containers.put(chestId, 0, FakeContainer.item("diamond", 64));
    }

    @AfterEach
    void stop() {
        fixture.assertLedgerIsConsistent();
        fixture.close();
    }

    private ChestPlan wholeStack() {
        String hash = market.readChest(fixture.alice).snapshot().at(0).orElseThrow().sha256();
        return new ChestPlan(List.of(new ChestPlan.Take(0, hash, 64)));
    }

    private long listEverything() {
        String digest = market.readChest(fixture.alice).snapshot().digest();
        return market.createListingFromChest(
                fixture.alice, "Alice", ListingType.GIVEAWAY, null, null, null, digest, wholeStack(), List.of());
    }

    /** One item, in exactly one place: the chest, escrow, or the delivery queue. Never two, never none. */
    private void assertExactlyOneCopy() {
        int inChest = fixture.containers.totalItems();
        int inEscrow = fixture.escrowCount();
        int waiting = fixture.deliveryCount(fixture.alice);
        assertEquals(1, (inChest > 0 ? 1 : 0) + inEscrow + waiting, "chest=" + inChest + " escrow=" + inEscrow + " queue=" + waiting);
    }

    @Test
    @DisplayName("nothing was started: the chest keeps its items")
    void crashBeforeAnything() {
        fixture.restart();
        market = fixture.service();
        market.reconcileChest(chestId);

        assertEquals(64, fixture.containers.totalItems());
        assertExactlyOneCopy();
    }

    @Test
    @DisplayName("the world never saved the removal: the operation is abandoned and the items stay put")
    void crashAfterRemovalWithUnsavedChunk() {
        FakeContainer.World before = fixture.containers.snapshot(chestId);
        fixture.containers.crashAfterTake();

        assertTrue(runAndSwallow(this::listEverything), "the take was interrupted on purpose");
        // the chunk was never written, so contents and stamp both go back
        fixture.containers.rollbackTo(chestId, before);
        fixture.restart();
        market = fixture.service();
        market.reconcileChest(chestId);

        assertEquals(64, fixture.containers.totalItems(), "the items never left the chest");
        assertEquals(0, fixture.escrowCount());
        assertEquals(0, fixture.deliveryCount(fixture.alice));
        assertExactlyOneCopy();
    }

    @Test
    @DisplayName("the world saved the removal but the listing was never written: the items come back through the queue")
    void crashAfterRemovalWithSavedChunk() {
        fixture.containers.crashAfterTake();
        assertTrue(runAndSwallow(this::listEverything), "the take was interrupted on purpose");

        // the chunk was written: the chest really is empty
        assertEquals(0, fixture.containers.totalItems());
        fixture.restart();
        market = fixture.service();
        market.reconcileChest(chestId);

        assertEquals(0, fixture.containers.totalItems());
        assertEquals(0, fixture.escrowCount(), "no listing was ever created");
        assertEquals(1, fixture.deliveryCount(fixture.alice), "the items are waiting for their owner");
        assertExactlyOneCopy();
    }

    @Test
    @DisplayName("everything committed and the world agrees: reconciling changes nothing")
    void cleanRun() {
        listEverything();
        assertEquals(1, fixture.escrowCount());

        fixture.restart();
        market = fixture.service();
        market.reconcileChest(chestId);

        assertEquals(0, fixture.containers.totalItems());
        assertEquals(1, fixture.escrowCount());
        assertExactlyOneCopy();
    }

    @Test
    @DisplayName("the listing exists but the world rolled back: the take is applied again, and there is one copy")
    void worldRolledBackAfterCommit() {
        FakeContainer.World before = fixture.containers.snapshot(chestId);
        listEverything();
        assertEquals(1, fixture.escrowCount());

        // the database committed, the chunk holding the chest never did
        fixture.containers.rollbackTo(chestId, before);
        assertEquals(64, fixture.containers.totalItems(), "the world has the diamonds back, and so does the marketplace");

        fixture.restart();
        market = fixture.service();
        market.reconcileChest(chestId);

        assertEquals(0, fixture.containers.totalItems(), "the take was applied again");
        assertEquals(1, fixture.escrowCount());
        assertExactlyOneCopy();
    }

    @Test
    @DisplayName("reconciling twice, and three times, changes nothing after the first")
    void reconcileIsIdempotent() {
        FakeContainer.World before = fixture.containers.snapshot(chestId);
        listEverything();
        fixture.containers.rollbackTo(chestId, before);
        fixture.restart();
        market = fixture.service();

        market.reconcileChest(chestId);
        long seqAfterFirst = fixture.containers.seqOf(chestId);
        market.reconcileChest(chestId);
        market.reconcileChest(chestId);

        assertEquals(seqAfterFirst, fixture.containers.seqOf(chestId));
        assertEquals(0, fixture.containers.totalItems());
        assertEquals(1, fixture.escrowCount());
        assertExactlyOneCopy();
    }

    @Test
    @DisplayName("a chest that matches neither fingerprint is left for an administrator, and nothing moves")
    void unrecognisableChestIsLeftAlone() {
        fixture.containers.crashAfterTake();
        assertTrue(runAndSwallow(this::listEverything));

        // somebody put something else in while the server was down
        fixture.containers.put(chestId, 7, FakeContainer.item("bread", 5));
        fixture.restart();
        market = fixture.service();
        market.reconcileChest(chestId);

        assertEquals(0, fixture.escrowCount(), "nothing was created");
        assertEquals(0, fixture.deliveryCount(fixture.alice), "and nothing was handed out");
        assertTrue(fixture.count("SELECT COUNT(*) FROM intents WHERE state = 'MANUAL'") > 0, "an administrator decides");
    }

    @Test
    @DisplayName("a chest that is gone is marked lost, and the marketplace keeps what it already held")
    void chestVanishes() {
        listEverything();
        fixture.containers.vanish();
        fixture.restart();
        market = fixture.service();
        market.reconcileChest(chestId);

        assertEquals(ChestState.BROKEN, market.chestById(chestId).orElseThrow().state());
        assertEquals(1, fixture.escrowCount(), "the listing is untouched: it never lived in the chest");
        assertTrue(fixture.count("SELECT COUNT(*) FROM events WHERE type = 'CHEST_LOST'") > 0);
    }

    @Test
    @DisplayName("a chest intent is never settled by looking at the player's inventory")
    void loginRecoveryLeavesChestIntentsAlone() {
        fixture.containers.crashAfterTake();
        assertTrue(runAndSwallow(this::listEverything));
        fixture.restart();
        market = fixture.service();

        // exactly what happens when the owner logs in after the crash
        market.recoverPlayer(fixture.alice);

        assertEquals(0, fixture.deliveryCount(fixture.alice), "the items are still in the chest, not in the queue");
        assertEquals(0, fixture.escrowCount());
        assertNotEquals(0, fixture.count("SELECT COUNT(*) FROM intents WHERE source = 'CHEST' AND state = 'INTENT'"));
    }

    /** Runs something that is expected to stop dead. An Error, not an exception: a crash does not unwind politely. */
    private boolean runAndSwallow(Runnable work) {
        try {
            work.run();
            return false;
        } catch (FakeContainer.SimulatedCrash expected) {
            return true;
        }
    }
}
