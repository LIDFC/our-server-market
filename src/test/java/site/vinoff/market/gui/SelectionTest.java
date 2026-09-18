package site.vinoff.market.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.ItemBlob;

class SelectionTest {

    private static ItemBlob item(String name, int count) {
        return new ItemBlob(("item:" + name).getBytes(StandardCharsets.UTF_8), count, count + "x " + name);
    }

    @Test
    @DisplayName("ticking and unticking a stack")
    void toggling() {
        Selection selection = new Selection(3);
        assertEquals(Selection.Result.ADDED, selection.toggleOffered(4, item("diamond", 16)));
        assertTrue(selection.isOffered(4));
        assertEquals(Selection.Result.REMOVED, selection.toggleOffered(4, item("diamond", 16)));
        assertFalse(selection.isOffered(4));
        assertTrue(selection.empty());
    }

    @Test
    @DisplayName("a stack cannot be both offered and wanted")
    void oneRolePerSlot() {
        Selection selection = new Selection(3);
        selection.toggleOffered(4, item("diamond", 16));
        assertEquals(Selection.Result.ALREADY_OFFERED, selection.toggleWanted(4, item("diamond", 16)));
        assertEquals(1, selection.offeredCount());
        assertEquals(0, selection.wantedCount());
    }

    @Test
    @DisplayName("the limit holds for both sides")
    void limit() {
        Selection selection = new Selection(2);
        assertEquals(Selection.Result.ADDED, selection.toggleOffered(1, item("a", 1)));
        assertEquals(Selection.Result.ADDED, selection.toggleOffered(2, item("b", 1)));
        assertEquals(Selection.Result.TOO_MANY, selection.toggleOffered(3, item("c", 1)));
        assertEquals(2, selection.offeredCount());

        assertEquals(Selection.Result.ADDED, selection.toggleWanted(10, item("d", 1)));
        assertEquals(Selection.Result.ADDED, selection.toggleWanted(11, item("e", 1)));
        assertEquals(Selection.Result.TOO_MANY, selection.toggleWanted(12, item("f", 1)));
    }

    @Test
    @DisplayName("a slot that changed under the player is forgotten, not offered by mistake")
    void forgetting() {
        Selection selection = new Selection(5);
        selection.toggleOffered(7, item("diamond", 16));
        selection.toggleWanted(8, item("gold", 32));
        selection.forget(7);
        selection.forget(8);
        assertTrue(selection.empty());
        assertEquals(0, selection.offered().size());
        assertEquals(0, selection.wantedItems().size());
    }

    @Test
    @DisplayName("what is ticked is what is handed to the marketplace, in order")
    void collecting() {
        Selection selection = new Selection(5);
        selection.toggleOffered(1, item("diamond", 16));
        selection.toggleOffered(5, item("iron", 64));
        selection.toggleWanted(9, item("gold", 32));

        assertEquals(2, selection.offered().size());
        assertEquals("16x diamond", selection.offered().get(0).summary());
        assertEquals("64x iron", selection.offered().get(1).summary());
        assertEquals(1, selection.wantedItems().size());
        assertEquals("32x gold", selection.wantedItems().get(0).summary());

        selection.clear();
        assertTrue(selection.empty());
    }
}
