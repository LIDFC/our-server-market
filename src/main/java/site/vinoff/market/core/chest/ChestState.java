package site.vinoff.market.core.chest;

/** Where a binding stands. A chest is never deleted from the table: its history is worth keeping. */
public enum ChestState {
    /** in use: the block is there, it is the player's marketplace stock */
    BOUND,
    /** the block is gone or is no longer the chest it was; nothing may be taken from it */
    BROKEN,
    /** the player let it go, or bound another one instead */
    RELEASED
}
