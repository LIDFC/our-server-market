package site.vinoff.market.core.port;

import java.util.List;
import site.vinoff.market.core.ItemBlob;

/**
 * Turns "four diamonds" into an item the marketplace can store.
 *
 * <p>Needed only for the half of a listing that says what the player wants in return. Those items do not exist in the
 * world: nobody is holding them, they are a description of what would be accepted. In the game the catalogue window
 * builds them from a clicked icon; from the website they arrive as a name and a number, and this is where that name
 * is checked against the server's own list of items rather than believed.
 *
 * <p>A separate port from {@link ContainerPort} because it touches no block, and from {@link InventoryPort} because
 * it touches no player. It is the only one of the three that invents an item rather than moving one.
 */
public interface ItemFactoryPort {

    /** One line of a wish list, as it arrives from outside: a Minecraft item name and how many. */
    record Wanted(String material, int amount) {

        public Wanted {
            if (material == null || material.isBlank()) {
                throw new IllegalArgumentException("An item needs a name");
            }
            if (amount <= 0 || amount > 64) {
                throw new IllegalArgumentException("Between 1 and 64 of an item, not " + amount);
            }
        }
    }

    /**
     * Makes the items, or refuses the whole list.
     *
     * <p>All or nothing on purpose: a listing that silently dropped the one line the server did not recognise would
     * be a listing the player never wrote.
     */
    List<ItemBlob> materialise(List<Wanted> wanted);
}
