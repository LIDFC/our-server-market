package site.vinoff.market.gui;

/**
 * What a click inside a marketplace window is allowed to do.
 *
 * <p>This is deliberately a plain table with no Bukkit types in it: the rule that decides whether an item may move is
 * the one place a marketplace plugin cannot afford to get wrong, so it is written as data and tested exhaustively
 * instead of living inside an event handler.
 *
 * <p>The rule itself is short: <b>every click is cancelled</b>. Marketplace windows hold only display items, and a
 * player's own items never enter one, so there is nothing a click could legitimately move. What a click can do is ask
 * the plugin to run an action, which happens after the event, on the next tick.
 */
public final class GuiPolicy {

    /** Every click type Bukkit can report. Kept as our own list so a new one cannot silently fall through. */
    public enum Click {
        LEFT,
        SHIFT_LEFT,
        RIGHT,
        SHIFT_RIGHT,
        WINDOW_BORDER_LEFT,
        WINDOW_BORDER_RIGHT,
        MIDDLE,
        NUMBER_KEY,
        DOUBLE_CLICK,
        DROP,
        CONTROL_DROP,
        CREATIVE,
        SWAP_OFFHAND,
        UNKNOWN
    }

    /** What the listener does with the event. */
    public enum Decision {
        /** cancel the event and run the button under the cursor, if there is one */
        RUN_BUTTON,
        /** cancel the event and do nothing else */
        CANCEL,
        /** not our window, leave it alone */
        IGNORE
    }

    private GuiPolicy() {}

    /**
     * @param ourWindow whether the top inventory belongs to the marketplace
     * @param rawSlot the slot Bukkit reported, negative when the click was outside any inventory
     * @param topSize how many slots the marketplace window has
     * @param click what kind of click it was
     */
    public static Decision decide(boolean ourWindow, int rawSlot, int topSize, Click click) {
        if (!ourWindow) {
            return Decision.IGNORE;
        }
        // Clicks that move items between the two inventories without the cursor ever entering ours, and clicks that
        // collect items from every slot of the view at once. They are cancelled wherever they land.
        if (click == Click.SHIFT_LEFT
                || click == Click.SHIFT_RIGHT
                || click == Click.NUMBER_KEY
                || click == Click.SWAP_OFFHAND
                || click == Click.DOUBLE_CLICK
                || click == Click.CREATIVE
                || click == Click.UNKNOWN) {
            return rawSlot >= 0 && rawSlot < topSize ? Decision.RUN_BUTTON : Decision.CANCEL;
        }
        if (rawSlot < 0) {
            // outside the window: dropping the cursor item, which inside our window is always empty anyway
            return Decision.CANCEL;
        }
        if (rawSlot < topSize) {
            return Decision.RUN_BUTTON;
        }
        // the player's own inventory below an open marketplace window: cancelled, so nothing can be dragged upwards
        return Decision.CANCEL;
    }

    /** A drag is cancelled as soon as it touches even one slot of a marketplace window. */
    public static Decision decideDrag(boolean ourWindow, int[] rawSlots, int topSize) {
        if (!ourWindow) {
            return Decision.IGNORE;
        }
        for (int slot : rawSlots) {
            if (slot >= 0 && slot < topSize) {
                return Decision.CANCEL;
            }
        }
        // a drag that stays inside the player's own inventory is harmless, but the window is display only anyway
        return Decision.CANCEL;
    }
}
