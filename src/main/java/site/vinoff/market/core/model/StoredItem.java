package site.vinoff.market.core.model;

import site.vinoff.market.core.ItemBlob;

/** A row of the items table: the prototype of one item, how many there are, and where it came from. */
public record StoredItem(String itemUid, ItemBlob blob, int dataVersion, String sha256) {

    public int amount() {
        return blob.count();
    }

    public String summary() {
        return blob.summary();
    }
}
