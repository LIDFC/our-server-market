package site.vinoff.market.core;

/** What a listing is for. The type decides which items are escrowed and who may take it. */
public enum ListingType {
    /** items given away for free, anyone may claim */
    GIVEAWAY,
    /** items offered in exchange for the wanted items */
    TRADE,
    /** a request: the wanted items are what the author looks for, the offered items are the reward */
    WANTED,
    /** items addressed to one player */
    GIFT
}
