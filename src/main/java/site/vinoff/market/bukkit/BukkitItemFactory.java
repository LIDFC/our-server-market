package site.vinoff.market.bukkit;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.port.ItemFactoryPort;

/**
 * Makes the items on a wish list, from names the website sent.
 *
 * <p>The name is matched against the server's own list of materials, exactly as the catalogue window does in the
 * game, and anything that is not an item a player can hold is refused. That check is the whole job: without it a
 * request could ask for {@code AIR}, or for a block that exists only as a block, and the listing would show a wish
 * nobody can ever satisfy.
 *
 * <p>The work happens on the server thread. Building an item reads the server's registries, and reading those from
 * an HTTP thread is the kind of thing that works until the day it does not.
 */
public final class BukkitItemFactory implements ItemFactoryPort {

    private final MainThread main;

    public BukkitItemFactory(MainThread main) {
        this.main = main;
    }

    @Override
    public List<ItemBlob> materialise(List<Wanted> wanted) {
        if (wanted.isEmpty()) {
            return List.of();
        }
        return main.call("build a wish list of " + wanted.size() + " item(s)", () -> {
            List<ItemBlob> items = new ArrayList<>(wanted.size());
            for (Wanted line : wanted) {
                Material material = Material.matchMaterial(line.material());
                if (material == null || !material.isItem() || material.isAir()) {
                    throw new MarketException(
                            MarketError.INVALID_REQUEST, "Такого предмета нет: " + line.material());
                }
                ItemStack stack = new ItemStack(material, line.amount());
                items.add(PaperItemCodec.encode(stack));
            }
            return items;
        });
    }
}
