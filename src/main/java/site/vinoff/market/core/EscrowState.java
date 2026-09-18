package site.vinoff.market.core;

/** Where an escrowed item is in its life. Every change is written together with a row in the item movement log. */
public enum EscrowState {
    /** the marketplace holds the item, nobody can touch it */
    HELD,
    /** the item left escrow: it is either handed over or waiting in a pending delivery */
    RELEASED
}
