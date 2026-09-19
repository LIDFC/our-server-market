package site.vinoff.market.core.model;

import java.time.Instant;
import site.vinoff.market.core.chest.ChestPlan;

/**
 * The chest half of an intent: which chest, which journal number, what the chest looked like before and should look
 * like after, and the plan itself so it can be replayed if the world rolled back past it.
 */
public record ChestIntent(
        String txId,
        long chestId,
        long seq,
        String preDigest,
        String postDigest,
        String plan,
        Long listingId,
        Instant createdAt) {

    public ChestPlan decodedPlan() {
        return ChestPlan.decode(plan);
    }
}
