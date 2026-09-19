package site.vinoff.market.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.ListingType;

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
    @DisplayName("the limit holds for what is given and for what is asked for")
    void limit() {
        Selection selection = new Selection(2);
        assertEquals(Selection.Result.ADDED, selection.toggleOffered(1, item("a", 1)));
        assertEquals(Selection.Result.ADDED, selection.toggleOffered(2, item("b", 1)));
        assertEquals(Selection.Result.TOO_MANY, selection.toggleOffered(3, item("c", 1)));
        assertEquals(2, selection.offeredCount());

        assertEquals(Selection.Result.ADDED, selection.want("diamond", 1, 64));
        assertEquals(Selection.Result.ADDED, selection.want("emerald", 1, 64));
        assertEquals(Selection.Result.TOO_MANY, selection.want("gold_ingot", 1, 64));
        assertEquals(2, selection.wantedCount());
        assertEquals(Selection.Result.CHANGED, selection.want("diamond", 5, 64), "a third item is refused, not a sixth diamond");
    }

    @Test
    @DisplayName("a slot that changed under the player is forgotten, not offered by mistake")
    void forgetting() {
        Selection selection = new Selection(5);
        selection.toggleOffered(7, item("diamond", 16));
        selection.forget(7);
        assertTrue(selection.empty());
        assertEquals(0, selection.offered().size());
    }

    @Test
    @DisplayName("what is ticked is what is handed to the marketplace, in order")
    void collecting() {
        Selection selection = new Selection(5);
        selection.toggleOffered(1, item("diamond", 16));
        selection.toggleOffered(5, item("iron", 64));

        assertEquals(List.of("16x diamond", "64x iron"), selection.offered().stream().map(ItemBlob::summary).toList());

        selection.clear();
        assertTrue(selection.empty());
    }

    @Test
    @DisplayName("an amount goes up, comes down, and disappears when it reaches nothing")
    void amounts() {
        Selection selection = new Selection(5);
        assertEquals(Selection.Result.ADDED, selection.want("diamond", 1, 64));
        assertEquals(1, selection.wantedAmount("diamond"));
        assertEquals(Selection.Result.CHANGED, selection.want("diamond", 16, 64));
        assertEquals(17, selection.wantedAmount("diamond"));
        assertEquals(Selection.Result.CHANGED, selection.want("diamond", -1, 64));
        assertEquals(16, selection.wantedAmount("diamond"));
        assertEquals(Selection.Result.REMOVED, selection.want("diamond", -100, 64));
        assertEquals(0, selection.wantedAmount("diamond"));
        assertTrue(selection.empty());
    }

    @Test
    @DisplayName("a held down mouse button cannot ask for a million diamonds")
    void cap() {
        Selection selection = new Selection(5);
        for (int click = 0; click < 100; click++) {
            selection.want("diamond", 16, 64);
        }
        assertEquals(64, selection.wantedAmount("diamond"));
        assertEquals(Selection.Result.UNCHANGED, selection.want("diamond", 16, 64), "already at the cap");
    }

    @Test
    @DisplayName("the wish list keeps the order it was built in")
    void wishListOrder() {
        Selection selection = new Selection(5);
        selection.want("emerald", 5, 64);
        selection.want("diamond", 2, 64);
        assertEquals(List.of("emerald", "diamond"), List.copyOf(selection.wanted().keySet()));

        assertEquals(Selection.Result.REMOVED, selection.forgetWanted("emerald"));
        assertEquals(Selection.Result.UNCHANGED, selection.forgetWanted("emerald"));
        assertEquals(List.of("diamond"), List.copyOf(selection.wanted().keySet()));
    }

    @Test
    @DisplayName("a wanted listing asks what you are looking for, not what you want in return")
    void wishHeading() {
        assertEquals("Что ищу", SelectionWindow.headingFor(ListingType.WANTED));
        for (ListingType other : new ListingType[] {ListingType.TRADE, ListingType.GIVEAWAY, ListingType.GIFT}) {
            assertEquals("Что хочу взамен", SelectionWindow.headingFor(other), other + " asks for something in return");
        }
    }

    @Test
    @DisplayName("what a player gives and what they ask for are independent")
    void bothHalvesAreSeparate() {
        Selection selection = new Selection(5);
        selection.toggleOffered(4, item("diamond", 16));
        selection.want("diamond", 32, 64);
        assertEquals(1, selection.offeredCount());
        assertEquals(1, selection.wantedCount());
        assertEquals(32, selection.wantedAmount("diamond"), "asking for diamonds does not stop you giving diamonds");
    }
}
