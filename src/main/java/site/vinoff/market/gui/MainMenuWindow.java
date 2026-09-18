package site.vinoff.market.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import site.vinoff.market.core.ListingType;

/** The first screen: everything a player can do, in five buttons. */
public final class MainMenuWindow extends MarketWindow {

    private final Gui gui;

    public MainMenuWindow(Gui gui) {
        super("Рынок", 3);
        this.gui = gui;
    }

    @Override
    public void refresh(Player player) {
        clear();
        set(
                10,
                Icons.button(Material.CHEST, "Смотреть рынок", "Что сейчас отдают и меняют"),
                (clicker, click) -> gui.openBrowse(clicker, 1));
        set(
                12,
                Icons.button(Material.WRITABLE_BOOK, "Отдать даром", "Выберите вещи из своего инвентаря"),
                (clicker, click) -> gui.openCreate(clicker, ListingType.GIVEAWAY, null));
        set(
                13,
                Icons.button(Material.GOLD_INGOT, "Предложить обмен", "Что отдаёте и что хотите взамен"),
                (clicker, click) -> gui.openCreate(clicker, ListingType.TRADE, null));
        set(
                14,
                Icons.button(Material.PAPER, "Мои лоты", "Снять лот и забрать вещи"),
                (clicker, click) -> gui.openMine(clicker));
        set(
                16,
                Icons.button(Material.ENDER_CHEST, "Мои сделки", "Принять, отклонить, подтвердить"),
                (clicker, click) -> gui.openTrades(clicker));

        int waiting = gui.market().pendingCount(player.getUniqueId());
        set(
                22,
                Icons.button(
                        waiting > 0 ? Material.SHULKER_BOX : Material.BARRIER,
                        waiting > 0 ? "Вас ждут вещи: " + waiting : "Посылок нет",
                        waiting > 0 ? "Нажмите, чтобы забрать" : "Сюда попадает то, что не влезло в инвентарь"),
                (clicker, click) -> gui.openDeliveries(clicker));
        fillEmpty();
    }
}
