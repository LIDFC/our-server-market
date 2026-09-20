package site.vinoff.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.chest.ChestIndex;
import site.vinoff.market.core.chest.ChestKind;
import site.vinoff.market.core.chest.ChestState;
import site.vinoff.market.core.model.BoundChest;

/**
 * The index of bound blocks the protection listeners read.
 *
 * <p>Worth its own tests because it is asked on every tick of every hopper on the server, and because the answer it
 * gives decides whether a stranger may reach into somebody else's stock. Both of those are bad places for a bug that
 * only shows up with the second chest, or only in a chunk with negative coordinates.
 */
class ChestIndexTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes(StandardCharsets.UTF_8));
    private static final UUID NETHER = UUID.nameUUIDFromBytes("nether".getBytes(StandardCharsets.UTF_8));
    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes(StandardCharsets.UTF_8));

    private final ChestIndex index = new ChestIndex();

    private static BoundChest chest(long id, UUID world, int x, int y, int z, int[] pair) {
        return new BoundChest(
                id,
                ALICE,
                world,
                x,
                y,
                z,
                pair == null ? ChestKind.SINGLE : ChestKind.DOUBLE,
                pair == null ? null : pair[0],
                pair == null ? null : pair[1],
                pair == null ? null : pair[2],
                pair == null ? 27 : 54,
                ChestState.BOUND,
                1,
                0,
                "boot",
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null);
    }

    @Test
    @DisplayName("an empty index says so, which is the answer a hopper gets on almost every server")
    void emptyIsCheap() {
        assertTrue(index.isEmpty());
        assertTrue(index.at(WORLD, 0, 64, 0).isEmpty());
        assertEquals(List.of(), index.inChunk(WORLD, 0, 0));
    }

    @Test
    @DisplayName("a single chest is found by its block and by nothing else")
    void singleChest() {
        index.remember(chest(1, WORLD, 10, 64, -20, null), true);

        assertEquals(1, index.at(WORLD, 10, 64, -20).orElseThrow().chestId());
        assertTrue(index.at(WORLD, 11, 64, -20).isEmpty(), "the block next door is not bound");
        assertTrue(index.at(NETHER, 10, 64, -20).isEmpty(), "the same coordinates in another world are not bound");
        assertTrue(index.isSingleBlock(1));
    }

    @Test
    @DisplayName("a double chest is found by either half")
    void doubleChest() {
        index.remember(chest(2, WORLD, 5, 70, 5, new int[] {6, 70, 5}), true);

        assertEquals(2, index.at(WORLD, 5, 70, 5).orElseThrow().chestId());
        assertEquals(2, index.at(WORLD, 6, 70, 5).orElseThrow().chestId());
        assertFalse(index.isSingleBlock(2), "only a single chest can be paired into by a neighbour");
    }

    @Test
    @DisplayName("negative coordinates land in the right chunk")
    void negativeChunks() {
        index.remember(chest(3, WORLD, -1, 64, -17, null), true);

        assertEquals(List.of(3L), index.inChunk(WORLD, -1, -2));
        assertEquals(List.of(), index.inChunk(WORLD, 0, 0));
    }

    @Test
    @DisplayName("a double chest inside one chunk is listed once there, and clears out completely")
    void doubleChestInOneChunk() {
        index.remember(chest(4, WORLD, 2, 64, 2, new int[] {3, 64, 2}), true);
        assertEquals(List.of(4L), index.inChunk(WORLD, 0, 0), "one entry, not two");

        index.forget(4);
        assertEquals(List.of(), index.inChunk(WORLD, 0, 0));
        assertTrue(index.isEmpty(), "both blocks are gone, not just the first");
    }

    @Test
    @DisplayName("binding a different block under the same id leaves nothing behind at the old one")
    void rebinding() {
        index.remember(chest(5, WORLD, 0, 64, 0, null), true);
        index.remember(chest(5, WORLD, 100, 64, 100, null), true);

        assertTrue(index.at(WORLD, 0, 64, 0).isEmpty(), "the old block is free again");
        assertEquals(5, index.at(WORLD, 100, 64, 100).orElseThrow().chestId());
        assertEquals(1, index.size());
        assertEquals(List.of(), index.inChunk(WORLD, 0, 0));
    }

    @Test
    @DisplayName("both halves learn that the chest has been checked")
    void settling() {
        index.remember(chest(6, WORLD, 5, 70, 5, new int[] {6, 70, 5}), false);
        assertFalse(index.at(WORLD, 6, 70, 5).orElseThrow().settled());

        index.setSettled(6, true);
        assertTrue(index.at(WORLD, 5, 70, 5).orElseThrow().settled());
        assertTrue(index.at(WORLD, 6, 70, 5).orElseThrow().settled(), "the other half too");
    }

    @Test
    @DisplayName("forgetting a chest that was never there changes nothing")
    void forgetUnknown() {
        index.remember(chest(7, WORLD, 1, 1, 1, null), true);
        index.forget(999);

        assertEquals(1, index.size());
        assertEquals(7, index.at(WORLD, 1, 1, 1).orElseThrow().chestId());
    }
}
