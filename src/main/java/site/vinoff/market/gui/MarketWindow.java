package site.vinoff.market.gui;

import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * A marketplace window. It is recognised by being this holder, never by its title, because a title can be copied by
 * anything and a holder cannot.
 *
 * <p>A window holds pictures and buttons, never items. Clicks are cancelled by the listener before a button runs, so a
 * button can only ask the marketplace to do something; it can never hand an item over by itself.
 */
public abstract class MarketWindow implements InventoryHolder {

    /** What a click on a slot asks for. */
    @FunctionalInterface
    public interface Button {
        void run(Player player, GuiPolicy.Click click);
    }

    private final Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();

    protected MarketWindow(String title, int rows) {
        this.inventory = Bukkit.createInventory(this, rows * 9, Component.text(title, NamedTextColor.DARK_GRAY));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public int size() {
        return inventory.getSize();
    }

    protected void clear() {
        inventory.clear();
        buttons.clear();
    }

    protected void set(int slot, ItemStack icon) {
        inventory.setItem(slot, icon);
    }

    protected void set(int slot, ItemStack icon, Button action) {
        inventory.setItem(slot, icon);
        buttons.put(slot, action);
    }

    protected void fillEmpty() {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, Icons.filler());
            }
        }
    }

    /** Runs whatever sits on this slot. Called after the event was cancelled. */
    public void click(Player player, int slot, GuiPolicy.Click click) {
        Button button = buttons.get(slot);
        if (button != null) {
            button.run(player, click);
        }
    }

    /** Builds the contents. Called when the window opens and whenever something it shows has changed. */
    public abstract void refresh(Player player);
}
