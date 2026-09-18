package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.UUID;
import site.vinoff.market.core.EscrowState;

/** One stack the marketplace is holding, and for whom. */
public record EscrowItem(
        long id,
        StoredItem item,
        UUID ownerUuid,
        Long listingId,
        Long tradeId,
        TradeParty side,
        EscrowState state,
        UUID releasedTo,
        String releasedTx,
        Instant releasedAt,
        Instant createdAt) {}
