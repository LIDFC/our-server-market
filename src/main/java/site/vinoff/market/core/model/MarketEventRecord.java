package site.vinoff.market.core.model;

import java.time.Instant;
import java.util.UUID;

/** A line of the marketplace log, also the feed the website polls. */
public record MarketEventRecord(
        long id, Instant at, String type, UUID actorUuid, Long listingId, Long tradeId, String txId, String detail) {}
