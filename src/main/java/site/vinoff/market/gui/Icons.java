package site.vinoff.market.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The pictures inside marketplace windows. Every one of them is marked in its persistent data, so an item that somehow
 * escapes a window can be recognised and destroyed rather than becoming a free diamond.
 */
public final class Icons {

    private static NamespacedKey marker;

    private Icons() {}

    public static void init(Plugin plugin) {
        marker = new NamespacedKey(plugin, "gui");
    }

    public static NamespacedKey marker() {
        return marker;
    }

    /** True when this stack is one of ours: a picture from a window, never a real item a player may own. */
    public static boolean isGuiItem(ItemStack stack) {
        if (marker == null || stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    public static ItemStack button(Material material, String title, String... lines) {
        return decorate(new ItemStack(material), title, lines);
    }

    /** Shows a real item as a picture: a copy, marked, so it can never be confused with the item itself. */
    public static ItemStack preview(ItemStack original, String title, String... lines) {
        ItemStack copy = original.clone();
        return decorate(copy, title, lines);
    }

    /**
     * A catalogue entry. The name is left to the client, so a Russian player reads «Алмазная кирка» and an English one
     * reads "Diamond Pickaxe" without the server knowing either word.
     */
    public static ItemStack catalogue(Material material, int amount, String... lines) {
        int capped = Math.max(1, Math.min(amount, material.getMaxStackSize()));
        ItemStack stack = new ItemStack(material, capped);
        Component name = Component.translatable(material.translationKey())
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
        return decorate(stack, name, lines);
    }

    /** A button whose lore is built out of components, so item names in it are translated by the client. */
    public static ItemStack rich(Material material, String title, List<Component> lines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(title, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            if (!lines.isEmpty()) {
                meta.lore(lines);
            }
            if (marker != null) {
                meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** One grey line of lore. */
    public static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    /** One grey line of lore naming an item, which the client renders in the player's own language. */
    public static Component line(String prefix, Material material) {
        return Component.text(prefix, NamedTextColor.GRAY)
                .append(Component.translatable(material.translationKey()))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack decorate(ItemStack stack, String title, String... lines) {
        Component name = title == null
                ? null
                : Component.text(title, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
        return decorate(stack, name, lines);
    }

    private static ItemStack decorate(ItemStack stack, Component title, String... lines) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (title != null) {
                meta.displayName(title);
            }
            List<Component> lore = new ArrayList<>();
            for (String line : lines) {
                if (line != null) {
                    lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                }
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            if (marker != null) {
                meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack filler() {
        return button(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : Arrays.asList(text.split(" "))) {
            if (line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
