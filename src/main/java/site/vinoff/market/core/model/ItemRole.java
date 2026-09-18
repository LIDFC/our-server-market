package site.vinoff.market.core.model;

/** Which half of a listing an item belongs to. */
public enum ItemRole {
    /** goes into escrow when the listing is published */
    OFFERED,
    /** only a description of what the author is looking for, never escrowed from the author */
    WANTED
}
