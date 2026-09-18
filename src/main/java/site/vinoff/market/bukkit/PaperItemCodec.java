package site.vinoff.market.bukkit;

import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;

/**
 * Turns a real ItemStack into the bytes the marketplace stores and back.
 *
 * <p>Uses Paper's NBT serialization, which writes the data version into the bytes and runs Mojang's converter when
 * reading them back. That is what keeps enchantments, custom names, lore, potion contents, attributes and damage
 * intact across a Minecraft update. The older BukkitObjectOutputStream is deprecated since 1.21 and is not used.
 */
public final class PaperItemCodec {

    private PaperItemCodec() {}

    /** Serializes one stack. The amount travels beside the bytes, so a stack can be counted without decoding it. */
    public static ItemBlob encode(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            throw new MarketException(MarketError.INVALID_REQUEST, "There is no item here");
        }
        ItemStack one = stack.clone();
        one.setAmount(1);
        byte[] data = ItemStack.serializeItemsAsBytes(List.of(one));
        return new ItemBlob(data, stack.getAmount(), summary(stack));
    }

    /** Reads a stack back, with the amount the marketplace recorded. */
    public static ItemStack decode(ItemBlob blob) {
        ItemStack[] items;
        try {
            items = ItemStack.deserializeItemsFromBytes(blob.data());
        } catch (RuntimeException broken) {
            throw new MarketException(MarketError.ITEM_DATA_CORRUPT, "This item cannot be read any more", broken);
        }
        if (items.length == 0 || items[0] == null || items[0].getType().isAir()) {
            throw new MarketException(MarketError.ITEM_DATA_CORRUPT, "This item cannot be read any more");
        }
        ItemStack stack = items[0];
        stack.setAmount(blob.count());
        return stack;
    }

    /** What a player sees in chat and on the website. Never used to decide anything. */
    public static String summary(ItemStack stack) {
        String name = stack.getType().getKey().getKey().replace('_', ' ');
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta != null && meta.hasDisplayName()) {
            String custom = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            if (!custom.isBlank()) {
                name = custom + " (" + name + ")";
            }
        }
        return stack.getAmount() + "x " + name;
    }

    /**
     * Data version of the running server, used to notice a rollback. Taken from the version string rather than from an
     * internal API, so an update cannot break the plugin at load time.
     */
    public static int serverDataVersion() {
        String version = Bukkit.getMinecraftVersion();
        String[] parts = version.split("\\.");
        int value = 0;
        for (int index = 0; index < 3; index++) {
            value = value * 100 + (index < parts.length ? parseOrZero(parts[index]) : 0);
        }
        return value;
    }

    private static int parseOrZero(String text) {
        try {
            return Integer.parseInt(text.replaceAll("\\D", ""));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
