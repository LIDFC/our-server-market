package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import site.vinoff.market.core.TradeState;

/** A trade between the owner of a listing and one buyer. */
public record Trade(
        long id,
        long listingId,
        UUID ownerUuid,
        UUID buyerUuid,
        TradeState state,
        int expectedEscrowCount,
        Set<TradeParty> confirmations,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {

    public boolean involves(UUID uuid) {
        return ownerUuid.equals(uuid) || buyerUuid.equals(uuid);
    }

    public TradeParty partyOf(UUID uuid) {
        if (ownerUuid.equals(uuid)) {
            return TradeParty.OWNER;
        }
        if (buyerUuid.equals(uuid)) {
            return TradeParty.BUYER;
        }
        return null;
    }

    public boolean bothConfirmed() {
        return confirmations.contains(TradeParty.OWNER) && confirmations.contains(TradeParty.BUYER);
    }
}
