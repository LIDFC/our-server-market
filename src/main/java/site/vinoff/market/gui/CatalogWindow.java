package site.vinoff.market.gui;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.bukkit.Messages;

/**
 * The catalogue: every item in the game, searchable, for building the "what I want in return" half of a listing.
 *
 * <p>It exists because the other half cannot answer the question. What a player gives is picked out of their own
 * inventory; what they want is by definition something they do not have, so it has to be picked from a list of
 * everything. Nothing in this window is a real item — the stacks are pictures, the same as in every other marketplace
 * window — and choosing here only writes a number into the draft.
 */
public final class CatalogWindow extends MarketWindow {

    private static final int PER_PAGE = 45;
    /** how many stacks of one thing a player may ask for */
    private static final int MAX_STACKS_PER_ITEM = 9;

    private final Gui gui;
    private final SelectionWindow origin;
    private final String query;
    private final int page;
    private final List<Material> found;

    public CatalogWindow(Gui gui, SelectionWindow origin, String query, int page) {
        super(title(query, page), 6);
        this.gui = gui;
        this.origin = origin;
        this.query = query == null ? "" : query;
        this.found = Catalogue.search(this.query);
        int pages = Math.max(1, (found.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.min(Math.max(1, page), pages);
    }

    /** Chest titles are short, so a long query is cut rather than pushing the page number off the screen. */
    private static String title(String query, int page) {
        String what = query == null || query.isBlank() ? "Что хочу взамен" : "Поиск: " + query;
        if (what.length() > 22) {
            what = what.substring(0, 22);
        }
        return what + " — стр. " + Math.max(1, page);
    }

    @Override
    public void refresh(Player player) {
        clear();
        Selection selection = origin.selection();
        int from = (page - 1) * PER_PAGE;
        for (int index = 0; index < PER_PAGE && from + index < found.size(); index++) {
            Material material = found.get(from + index);
            set(index, entry(material, selection), (clicker, click) -> change(clicker, material, click));
        }
        if (found.isEmpty()) {
            set(
                    22,
                    Icons.button(
                            Material.BARRIER,
                            "Ничего не нашлось",
                            "Попробуйте другое слово,",
                            "или английский id, например diamond"));
        }

        if (page > 1) {
            set(45, Icons.button(Material.ARROW, "Назад", "Страница " + (page - 1)), (clicker, click) -> open(clicker, page - 1));
        }
        set(
                46,
                Icons.button(
                        Material.COMPASS,
                        query.isBlank() ? "Поиск" : "Поиск: " + query,
                        "Клик — написать в чат, что искать",
                        "Можно по-русски («алмаз») или",
                        "английским id («diamond»)"),
                (clicker, click) -> search(clicker));
        if (!query.isBlank()) {
            set(47, Icons.button(Material.BARRIER, "Сбросить поиск", "Показать все предметы"), (clicker, click) -> open(clicker, 1, ""));
        }
        set(49, done(selection), (clicker, click) -> gui.windows().open(clicker, origin));
        int lastPage = Math.max(1, (found.size() + PER_PAGE - 1) / PER_PAGE);
        if (page < lastPage) {
            set(53, Icons.button(Material.ARROW, "Дальше", "Страница " + (page + 1)), (clicker, click) -> open(clicker, page + 1));
        }
        fillEmpty();
    }

    private ItemStack entry(Material material, Selection selection) {
        int chosen = selection.wantedAmount(Catalogue.id(material));
        String mark = chosen > 0 ? "► ХОЧУ: " + chosen + " шт." : null;
        return Icons.catalogue(
                material,
                Math.max(1, chosen),
                mark,
                mark == null ? null : " ",
                "ЛКМ — добавить 1",
                "Shift+ЛКМ — добавить 16",
                chosen > 0 ? "ПКМ — убрать 1" : null,
                chosen > 0 ? "Shift+ПКМ — убрать совсем" : null,
                " ",
                "id: " + Catalogue.id(material));
    }

    private ItemStack done(Selection selection) {
        Map<String, Integer> wanted = selection.wanted();
        String[] lines = new String[wanted.size() + 2];
        int index = 0;
        lines[index++] = wanted.isEmpty() ? "Вы пока ничего не выбрали" : "Вы хотите взамен:";
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            lines[index++] = "  • " + entry.getValue() + " шт. " + entry.getKey();
        }
        lines[index] = "Клик — вернуться к лоту";
        return Icons.button(wanted.isEmpty() ? Material.GRAY_DYE : Material.EMERALD, "Готово", lines);
    }

    private void change(Player player, Material material, GuiPolicy.Click click) {
        if (click == GuiPolicy.Click.DOUBLE_CLICK) {
            // a double click is a second event after an ordinary left click; counting it would add twice
            return;
        }
        Selection selection = origin.selection();
        String id = Catalogue.id(material);
        int cap = material.getMaxStackSize() * MAX_STACKS_PER_ITEM;
        Selection.Result result = switch (click) {
            case SHIFT_RIGHT -> selection.forgetWanted(id);
            case RIGHT -> selection.want(id, -1, cap);
            case SHIFT_LEFT -> selection.want(id, 16, cap);
            default -> selection.want(id, 1, cap);
        };
        if (result == Selection.Result.TOO_MANY) {
            player.sendMessage(Messages.bad("Больше " + site.vinoff.market.core.MarketService.MAX_ITEMS_PER_LISTING
                    + " разных предметов в «хочу» нельзя"));
        } else if (result == Selection.Result.UNCHANGED && click == GuiPolicy.Click.LEFT) {
            player.sendMessage(Messages.info("Больше " + cap + " шт. одного предмета просить нельзя"));
        }
        gui.windows().refresh(player);
    }

    private void search(Player player) {
        player.sendMessage(Messages.info("Напишите в чат, что ищете. «отмена» — ничего не менять"));
        gui.prompts().ask(player, answer -> {
            String wanted = answer.equalsIgnoreCase("отмена") || answer.equalsIgnoreCase("cancel") ? query : answer;
            open(player, 1, wanted);
        });
        // the chest has to go away before anything can be typed
        gui.closeWindow(player);
    }

    private void open(Player player, int page) {
        open(player, page, query);
    }

    private void open(Player player, int page, String query) {
        gui.windows().open(player, new CatalogWindow(gui, origin, query, page));
    }
}
