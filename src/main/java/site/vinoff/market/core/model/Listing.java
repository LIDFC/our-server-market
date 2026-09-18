package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import site.vinoff.market.core.ListingState;
import site.vinoff.market.core.ListingType;

/** A marketplace listing with the items on both sides of it. */
public record Listing(
        long id,
        UUID ownerUuid,
        ListingType type,
        ListingState state,
        UUID recipientUuid,
        String recipientNameLower,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        List<ListingItem> offered,
        List<ListingItem> wanted) {

    public Optional<UUID> recipient() {
        return Optional.ofNullable(recipientUuid);
    }

    public boolean ownedBy(UUID uuid) {
        return ownerUuid.equals(uuid);
    }
}
