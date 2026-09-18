package site.vinoff.market.core;

/** A refusal with a code. Thrown by the service layer, never carries item data or anything secret. */
public class MarketException extends RuntimeException {

    private final MarketError error;

    public MarketException(MarketError error, String message) {
        super(message);
        this.error = error;
    }

    public MarketException(MarketError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public MarketError error() {
        return error;
    }
}
