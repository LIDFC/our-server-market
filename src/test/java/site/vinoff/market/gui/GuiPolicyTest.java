package site.vinoff.market.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The click rules, checked for every kind of click there is. This is the table that decides whether a marketplace
 * window can be used to move an item, so it is tested exhaustively rather than by example.
 */
class GuiPolicyTest {

    private static final int TOP = 54;

    @ParameterizedTest
    @EnumSource(GuiPolicy.Click.class)
    @DisplayName("no click of any kind is ever left uncancelled in a marketplace window")
    void everyClickIsCancelled(GuiPolicy.Click click) {
        for (int slot : new int[] {-999, -1, 0, 8, 53, 54, 60, 89}) {
            GuiPolicy.Decision decision = GuiPolicy.decide(true, slot, TOP, click);
            assertNotEquals(GuiPolicy.Decision.IGNORE, decision, click + " at slot " + slot + " must not be ignored");
        }
    }

    @ParameterizedTest
    @EnumSource(GuiPolicy.Click.class)
    @DisplayName("clicks in somebody else's window are none of our business")
    void otherWindowsAreLeftAlone(GuiPolicy.Click click) {
        assertEquals(GuiPolicy.Decision.IGNORE, GuiPolicy.decide(false, 3, TOP, click));
        assertEquals(GuiPolicy.Decision.IGNORE, GuiPolicy.decide(false, 60, TOP, click));
    }

    @Test
    @DisplayName("the tricks that move items without the cursor entering the window")
    void shiftNumberKeyAndSwapAreHandled() {
        // shift click from the player's own inventory: the clicked inventory is the player's, the item lands in ours
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 60, TOP, GuiPolicy.Click.SHIFT_LEFT));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 60, TOP, GuiPolicy.Click.SHIFT_RIGHT));
        // hotbar swap and off hand swap change a player slot while the cursor is over ours
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 70, TOP, GuiPolicy.Click.NUMBER_KEY));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 70, TOP, GuiPolicy.Click.SWAP_OFFHAND));
        // double click collects matching items from every slot of the view
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 61, TOP, GuiPolicy.Click.DOUBLE_CLICK));
        // creative mode clients can fabricate stacks
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 62, TOP, GuiPolicy.Click.CREATIVE));
        // and a click type from a future version is treated as the dangerous one
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 63, TOP, GuiPolicy.Click.UNKNOWN));
    }

    @Test
    @DisplayName("those same tricks still press the button when they land on it")
    void trickyClicksOnOurSlotsStillRunTheButton() {
        for (GuiPolicy.Click click : GuiPolicy.Click.values()) {
            assertEquals(GuiPolicy.Decision.RUN_BUTTON, GuiPolicy.decide(true, 10, TOP, click), click.name());
        }
    }

    @Test
    @DisplayName("a click outside any inventory only drops the cursor, which is always empty here")
    void clicksOutside() {
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, -999, TOP, GuiPolicy.Click.LEFT));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, -1, TOP, GuiPolicy.Click.WINDOW_BORDER_LEFT));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, -1, TOP, GuiPolicy.Click.DROP));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, -1, TOP, GuiPolicy.Click.CONTROL_DROP));
    }

    @Test
    @DisplayName("a drag is cancelled as soon as it touches the window, and in the window it never starts")
    void dragsAreCancelled() {
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decideDrag(true, new int[] {5}, TOP));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decideDrag(true, new int[] {60, 61, 5}, TOP));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decideDrag(true, new int[] {60, 61}, TOP));
        assertEquals(GuiPolicy.Decision.IGNORE, GuiPolicy.decideDrag(false, new int[] {5}, TOP));
    }

    @Test
    @DisplayName("a small window does not accidentally count the player's inventory as its own")
    void windowSizeIsRespected() {
        int small = 27;
        assertEquals(GuiPolicy.Decision.RUN_BUTTON, GuiPolicy.decide(true, 26, small, GuiPolicy.Click.LEFT));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 27, small, GuiPolicy.Click.LEFT));
        assertEquals(GuiPolicy.Decision.CANCEL, GuiPolicy.decide(true, 40, small, GuiPolicy.Click.SHIFT_LEFT));
    }
}
