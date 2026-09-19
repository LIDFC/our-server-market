package site.vinoff.market.core;

/**
 * Every way a marketplace operation can refuse. The name is the code the HTTP API returns and the key the Bukkit layer
 * turns into a message for the player, so the game and the website always say the same thing.
 */
public enum MarketError {
    LISTING_NOT_FOUND(404),
    TRADE_NOT_FOUND(404),
    DELIVERY_NOT_FOUND(404),
    PLAYER_NOT_FOUND(404),

    NOT_OWNER(403),
    NOT_PARTICIPANT(403),
    NOT_RECIPIENT(403),

    LISTING_NOT_ACTIVE(409),
    LISTING_ALREADY_TAKEN(409),
    LISTING_NOT_DRAFT(409),
    TRADE_NOT_PENDING(409),
    TRADE_NOT_ACCEPTED(409),
    TRADE_ALREADY_FINISHED(409),
    ALREADY_CONFIRMED(409),
    OWN_LISTING(409),

    CHEST_NOT_BOUND(404),
    CHEST_ALREADY_BOUND(409),
    CHEST_MISSING(409),
    CHEST_CHANGED(409),
    CHEST_IN_USE(409),
    CHEST_BUSY(409),
    CHEST_LOCKED(409),
    CHEST_UNAVAILABLE(503),

    EMPTY_LISTING(400),
    TOO_MANY_ITEMS(400),
    INVALID_REQUEST(400),
    ITEM_DATA_CORRUPT(500),
    STORAGE_FAILURE(500);

    private final int httpStatus;

    MarketError(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
