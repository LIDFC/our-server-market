package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One Minecraft identity. On an offline server the UUID follows the exact nickname, so the name is kept beside it and
 * several identities can belong to one account after an administrator merges them.
 */
public record Identity(
        UUID uuid, String accountId, String nameExact, String nameLower, Instant firstSeen, Instant lastSeen, String frozenReason) {

    public boolean frozen() {
        return frozenReason != null;
    }
}
