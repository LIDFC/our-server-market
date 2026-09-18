package site.vinoff.market.core;

/**
 * Life of an intent record. An intent is written before a player's inventory is touched and holds a digest of that
 * inventory, so after a crash the marketplace can tell whether the removal actually survived.
 */
public enum IntentState {
    /** written, the inventory has not been touched yet */
    INTENT,
    /** items were taken and the player's data was flushed */
    APPLIED,
    /** the operation finished */
    FINALIZED,
    /** the removal did not survive and the operation was undone */
    ABORTED,
    /** the marketplace could not decide on its own; an administrator has to look */
    MANUAL
}
