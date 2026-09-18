package site.vinoff.market.core;

import java.util.Set;

/** Life of a trade between the owner of a listing and a buyer. */
public enum TradeState {
    /** the buyer offered items, they are already in escrow */
    PENDING,
    /** the owner accepted the offer, both sides now have to confirm */
    ACCEPTED,
    /** both sides confirmed, the exchange is about to be written */
    CONFIRMED,
    COMPLETED,
    REJECTED,
    CANCELLED,
    EXPIRED;

    private static final Set<TradeState> TERMINAL = Set.of(COMPLETED, REJECTED, CANCELLED, EXPIRED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public boolean holdsEscrow() {
        return this == PENDING || this == ACCEPTED || this == CONFIRMED;
    }
}
