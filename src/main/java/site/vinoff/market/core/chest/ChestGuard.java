package site.vinoff.market.core.chest;

/**
 * What may be done to a bound chest.
 *
 * <p>A plain table with no Bukkit types in it, for the same reason {@code GuiPolicy} is one: this decides whether a
 * stranger can reach into somebody's marketplace stock, and a rule written as data can be tested exhaustively, while
 * a rule spread across event handlers can only be tested by example.
 *
 * <p>The shape of the table is worth reading once. A bound chest is not a locked box — it is a box the marketplace
 * has promised the website about. So automation is refused for <em>everyone</em>, the owner included: a hopper does
 * not ask permission and would empty the stock between the moment the website showed it and the moment the player
 * pressed the button.
 */
public final class ChestGuard {

    /** What somebody is trying to do to the block. */
    public enum Action {
        OPEN,
        BREAK,
        /** a hopper, dropper, or any plugin using vanilla item transfer */
        TRANSFER,
        EXPLODE,
        PISTON,
        /** placing a chest that would pair with a bound single chest and make it a double */
        PLACE_ADJACENT
    }

    public enum Verdict {
        ALLOW,
        /** somebody else's stock */
        DENY_NOT_YOURS,
        /** your own, but a bound chest has to be released before it can be broken */
        DENY_UNBIND_FIRST,
        /** the chest has not been checked since the server restarted, or an operation is in flight */
        DENY_BUSY,
        /** nobody, ever: automation and explosions */
        DENY_ALWAYS
    }

    private ChestGuard() {}

    /**
     * @param bound whether this block is a bound chest at all
     * @param owner whether the actor is the player it is bound to
     * @param admin whether the actor may administer the marketplace
     * @param settling whether the chest is still being reconciled after a restart, or has an operation in flight
     */
    public static Verdict decide(boolean bound, boolean owner, boolean admin, boolean settling, Action action) {
        if (!bound) {
            return Verdict.ALLOW;
        }
        // automation and blasts are refused before anything else, including for the owner and for admins
        if (action == Action.TRANSFER || action == Action.EXPLODE || action == Action.PISTON
                || action == Action.PLACE_ADJACENT) {
            return Verdict.DENY_ALWAYS;
        }
        if (settling) {
            // between a chunk loading and the marketplace agreeing with it, nobody touches the box
            return Verdict.DENY_BUSY;
        }
        return switch (action) {
            case OPEN -> owner ? Verdict.ALLOW : Verdict.DENY_NOT_YOURS;
            // an admin must be able to clear away an abandoned chest; the owner releases it first instead
            case BREAK -> admin ? Verdict.ALLOW : owner ? Verdict.DENY_UNBIND_FIRST : Verdict.DENY_NOT_YOURS;
            default -> Verdict.DENY_ALWAYS;
        };
    }

    /** What to tell the player. Null when nothing needs saying, which is only ever the allowed case. */
    public static String message(Verdict verdict) {
        return switch (verdict) {
            case ALLOW -> null;
            case DENY_NOT_YOURS -> "Это склад другого игрока";
            case DENY_UNBIND_FIRST -> "Сначала отвяжите сундук: /market chest release";
            case DENY_BUSY -> "Сундук проверяется, подождите немного";
            case DENY_ALWAYS -> "Из привязанного сундука ничего нельзя вытянуть автоматикой";
        };
    }
}
