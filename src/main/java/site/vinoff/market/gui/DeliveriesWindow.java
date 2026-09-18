package site.vinoff.market.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.bukkit.PaperItemCodec;
import site.vinoff.market.core.model.PendingDelivery;

/**
 * What is waiting for the player. The window only shows it: the items are handed over after it closes, because the
 * marketplace never changes an inventory a player is not looking at.
 */
public final class DeliveriesWindow extends MarketWindow {

    private final Gui gui;

    public DeliveriesWindow(Gui gui) {
        super("Мои посылки", 6);
        this.gui = gui;
    }

    @Override
    public void refresh(Player player) {
        clear();
        List<PendingDelivery> waiting = gui.market().pendingDeliveries(player.getUniqueId());
        int slot = 0;
        for (PendingDelivery delivery : waiting) {
            if (slot >= 45) {
                break;
            }
            set(slot++, icon(delivery));
        }
        if (waiting.isEmpty()) {
            set(22, Icons.button(Material.BARRIER, "Вас ничего не ждёт"));
        } else {
            set(
                    49,
                    Icons.button(Material.HOPPER, "Забрать всё", "Окно закроется, вещи придут в инвентарь"),
                    (clicker, click) -> gui.closeAndDeliver(clicker));
            set(45, Icons.button(Material.COMPASS, "В главное меню"), (clicker, click) -> gui.openMain(clicker));
            fillEmpty();
            return;
        }
        set(49, Icons.button(Material.COMPASS, "В главное меню"), (clicker, click) -> gui.openMain(clicker));
        fillEmpty();
    }

    private ItemStack icon(PendingDelivery delivery) {
        ItemStack face;
        try {
            face = PaperItemCodec.decode(delivery.item().blob());
        } catch (RuntimeException unreadable) {
            face = new ItemStack(Material.BARRIER);
        }
        return Icons.preview(face, delivery.item().summary(), reason(delivery), "Заберётся, когда окно закроется");
    }

    private static String reason(PendingDelivery delivery) {
        return switch (delivery.reason()) {
            case LISTING_CANCELLED -> "лот снят";
            case LISTING_EXPIRED -> "срок лота истёк";
            case TRADE_COMPLETED -> "обмен состоялся";
            case TRADE_CANCELLED -> "сделка отменена";
            case TRADE_REJECTED -> "предложение отклонено";
            case GIVEAWAY_CLAIMED -> "вы забрали раздачу";
            case GIFT_RECEIVED -> "подарок";
            case RECOVERED -> "вернулось после сбоя сервера";
            case ADMIN -> "выдал администратор";
        };
    }
}
