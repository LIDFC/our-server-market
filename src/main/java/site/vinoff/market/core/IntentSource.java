package site.vinoff.market.core;

/**
 * What an intent's fingerprint is a fingerprint of.
 *
 * <p>The two kinds live in the same table but are resolved against completely different things: a player intent is
 * settled by comparing against that player's inventory when they next log in, a chest intent by reading the chest.
 * Mixing them up would hand a player items that are still in their chest, so every intent has to say which it is.
 */
public enum IntentSource {
    PLAYER,
    CHEST
}
