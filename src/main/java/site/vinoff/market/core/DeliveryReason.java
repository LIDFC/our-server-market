package site.vinoff.market.core;

/** Why a player has items waiting for them. Shown in chat and on the website, and useful when reading the log. */
public enum DeliveryReason {
    LISTING_CANCELLED,
    LISTING_EXPIRED,
    TRADE_COMPLETED,
    TRADE_CANCELLED,
    TRADE_REJECTED,
    GIVEAWAY_CLAIMED,
    GIFT_RECEIVED,
    /** the server came back after a crash and the item had nowhere else to go */
    RECOVERED,
    /** an administrator moved the item */
    ADMIN
}
