package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.UUID;
import site.vinoff.market.core.IntentState;

/**
 * A record written before a player's inventory is touched. It carries a digest of that inventory, so after a crash the
 * marketplace can tell whether the removal survived instead of guessing.
 */
public record Intent(
        String txId,
        String bootId,
        String op,
        UUID playerUuid,
        Long listingId,
        Long tradeId,
        IntentState state,
        String preDigest,
        int dataVersion,
        String detail,
        Instant createdAt,
        Instant resolvedAt) {}
