package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.UUID;
import site.vinoff.market.core.chest.ChestKind;
import site.vinoff.market.core.chest.ChestState;

/**
 * A chest a player has made their marketplace stock.
 *
 * <p>The world is stored by its UUID rather than its name: a renamed world must never silently rebind somebody's
 * stock to a different block. A double chest keeps both coordinates, because a chest that was double and is now
 * single has to be an error rather than an operation with shifted slot numbers.
 *
 * <p>{@code nextSeq} and {@code appliedSeq} are the marketplace's side of the journal. The same number is written
 * into the block itself; comparing the two after a restart is how the plugin learns whether the world kept the last
 * change or rolled back past it.
 */
public record BoundChest(
        long id,
        UUID ownerUuid,
        UUID worldUuid,
        int x,
        int y,
        int z,
        ChestKind kind,
        Integer pairX,
        Integer pairY,
        Integer pairZ,
        int size,
        ChestState state,
        long nextSeq,
        long appliedSeq,
        String verifiedBootId,
        Instant boundAt,
        Instant updatedAt,
        Instant releasedAt,
        String releasedReason) {

    public boolean isDouble() {
        return kind == ChestKind.DOUBLE;
    }

    public boolean usable() {
        return state == ChestState.BOUND;
    }

    /** True while this boot has not yet agreed with the block about where the journal stands. */
    public boolean settling(String bootId) {
        return !bootId.equals(verifiedBootId);
    }
}
