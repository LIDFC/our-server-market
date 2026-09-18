package site.vinoff.market.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.bukkit.Messages;
import site.vinoff.market.core.TradeState;
import site.vinoff.market.core.model.Trade;
import site.vinoff.market.core.model.TradeParty;

/**
 * Trades the player is part of. Each one shows exactly what the next step is, and every step is a separate click, so a
 * trade can never be completed by a player who was just clicking quickly.
 */
public final class TradesWindow extends MarketWindow {

    private final Gui gui;

    public TradesWindow(Gui gui) {
        super("Мои сделки", 6);
        this.gui = gui;
    }

    @Override
    public void refresh(Player player) {
        clear();
        List<Trade> trades = gui.market().tradesOf(player.getUniqueId(), false);
        int slot = 0;
        for (Trade trade : trades) {
            if (slot >= 45) {
                break;
            }
            boolean owner = trade.ownerUuid().equals(player.getUniqueId());
            set(slot, icon(trade, owner, player), (clicker, click) -> act(clicker, trade, owner, click));
            slot++;
        }
        if (trades.isEmpty()) {
            set(22, Icons.button(Material.BARRIER, "Сделок нет", "Предложите обмен на чужой лот"));
        }
        set(49, Icons.button(Material.COMPASS, "В главное меню"), (clicker, click) -> gui.openMain(clicker));
        fillEmpty();
    }

    private ItemStack icon(Trade trade, boolean owner, Player player) {
        TradeParty me = owner ? TradeParty.OWNER : TradeParty.BUYER;
        boolean iConfirmed = trade.confirmations().contains(me);
        String next = switch (trade.state()) {
            case PENDING -> owner ? "Левый клик — принять, правый — отклонить" : "Ждём ответа владельца";
            case ACCEPTED -> iConfirmed ? "Вы подтвердили, ждём вторую сторону" : "Клик — подтвердить обмен";
            case CONFIRMED -> "Завершается…";
            default -> trade.state().name();
        };
        Material material = switch (trade.state()) {
            case PENDING -> owner ? Material.WRITABLE_BOOK : Material.CLOCK;
            case ACCEPTED -> iConfirmed ? Material.CLOCK : Material.EMERALD;
            default -> Material.PAPER;
        };
        return Icons.button(
                material,
                "Сделка #" + trade.id() + " по лоту #" + trade.listingId(),
                owner ? "Вам предложили обмен" : "Вы предложили обмен",
                "Состояние: " + state(trade.state()),
                next);
    }

    private void act(Player player, Trade trade, boolean owner, GuiPolicy.Click click) {
        boolean rightClick = click == GuiPolicy.Click.RIGHT || click == GuiPolicy.Click.SHIFT_RIGHT;
        if (trade.state() == TradeState.PENDING) {
            if (!owner) {
                player.sendMessage(Messages.info("Ждём, пока владелец лота ответит"));
                return;
            }
            if (rightClick) {
                if (gui.run(player, () -> gui.market().declineTrade(player.getUniqueId(), trade.id(), false))) {
                    player.sendMessage(Messages.info("Предложение отклонено, предметы вернутся владельцу"));
                    gui.openTrades(player);
                }
                return;
            }
            if (gui.run(player, () -> gui.market().acceptTrade(player.getUniqueId(), trade.id()))) {
                player.sendMessage(Messages.good("Принято. Теперь обе стороны подтверждают обмен"));
                gui.openTrades(player);
            }
            return;
        }
        if (trade.state() == TradeState.ACCEPTED || trade.state() == TradeState.CONFIRMED) {
            if (rightClick) {
                if (gui.run(player, () -> gui.market().declineTrade(player.getUniqueId(), trade.id(), false))) {
                    player.sendMessage(Messages.info("Сделка отменена, предметы вернутся"));
                    gui.openTrades(player);
                }
                return;
            }
            boolean[] finished = {false};
            boolean ok = gui.run(player, () -> finished[0] = gui.market().confirmTrade(player.getUniqueId(), trade.id()));
            if (!ok) {
                return;
            }
            if (finished[0]) {
                player.sendMessage(Messages.good("Обмен состоялся"));
                gui.closeAndDeliver(player);
            } else {
                player.sendMessage(Messages.info("Ваше подтверждение принято, ждём вторую сторону"));
                gui.openTrades(player);
            }
        }
    }

    private static String state(TradeState state) {
        return switch (state) {
            case PENDING -> "ждёт ответа";
            case ACCEPTED -> "ждёт подтверждений";
            case CONFIRMED -> "подтверждена";
            case COMPLETED -> "завершена";
            case REJECTED -> "отклонена";
            case CANCELLED -> "отменена";
            case EXPIRED -> "истекла";
        };
    }
}
