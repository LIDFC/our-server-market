package site.vinoff.market.core;

/**
 * Life of a pending delivery. CLAIMING is what makes handing items over recoverable: the row is marked before the
 * inventory is touched, so a crash in the middle leaves a row that recovery can look at instead of a silent loss.
 */
public enum DeliveryState {
    PENDING,
    CLAIMING,
    CLAIMED
}
