package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.UUID;
import site.vinoff.market.core.DeliveryReason;
import site.vinoff.market.core.DeliveryState;

/**
 * Items waiting for a player. Everything the marketplace hands back goes through this table first, so there is exactly
 * one way an item can reach an inventory and exactly one thing recovery has to look at.
 */
public record PendingDelivery(
        long id,
        UUID playerUuid,
        StoredItem item,
        DeliveryReason reason,
        DeliveryState state,
        Long sourceEscrowItemId,
        String claimTx,
        String claimBootId,
        int attempts,
        Instant createdAt,
        Instant claimedAt) {}
