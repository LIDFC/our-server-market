package site.vinoff.market.core.model;

/** An item attached to a listing: either part of what is offered, or part of what the author wants. */
public record ListingItem(long listingId, ItemRole role, int position, StoredItem item) {}
