package site.vinoff.market.core;

import java.util.Set;

/** Life of a listing. Only the transitions listed here are allowed, and each one is a guarded UPDATE. */
public enum ListingState {
    /** being put together, items are still with the player */
    DRAFT,
    /** published, items are in escrow */
    ACTIVE,
    /** somebody is trading for it right now */
    PENDING_TRADE,
    COMPLETED,
    CANCELLED,
    EXPIRED;

    private static final Set<ListingState> TERMINAL = Set.of(COMPLETED, CANCELLED, EXPIRED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /** True while the listing holds items in escrow. */
    public boolean holdsEscrow() {
        return this == ACTIVE || this == PENDING_TRADE;
    }
}
