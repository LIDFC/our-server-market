package site.vinoff.market.core.chest;

import java.util.Map;
import site.vinoff.market.core.ItemBlob;

/**
 * Everything one read of a bound chest produced.
 *
 * <p>Two views of the same thing, on purpose. {@code snapshot} is what the website may see — hashes, amounts and
 * names, never item bytes. {@code items} is what the marketplace needs to actually move something: the bytes of the
 * stack in each occupied slot, which must be recorded <em>before</em> the chest is emptied, because afterwards there
 * is nowhere left to read them from if the operation has to be handed back.
 *
 * <p>{@code seq} is the journal number stamped on the block itself. Compared against the marketplace's own copy it
 * says whether the world kept the last change or rolled back past it.
 */
public record ChestContents(ChestSnapshot snapshot, Map<Integer, ItemBlob> items, long seq) {

    public ChestContents {
        items = Map.copyOf(items);
    }

    public ItemBlob itemAt(int slot) {
        return items.get(slot);
    }
}
