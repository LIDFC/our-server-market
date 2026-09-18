package site.vinoff.market.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.bukkit.Messages;
import site.vinoff.market.bukkit.PaperItemCodec;
import site.vinoff.market.core.ListingType;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.core.model.Listing;
import site.vinoff.market.core.model.ListingItem;

/** What is on the market right now, 45 lots to a page. */
public final class BrowseWindow extends MarketWindow {

    private static final int PER_PAGE = 45;

    private final Gui gui;
    private final int page;

    public BrowseWindow(Gui gui, int page) {
        super("Рынок — страница " + page, 6);
        this.gui = gui;
        this.page = Math.max(1, page);
    }

    @Override
    public void refresh(Player player) {
        clear();
        List<Listing> listings = gui.market().activeListings(null, PER_PAGE, (page - 1) * PER_PAGE);
        int slot = 0;
        for (Listing listing : listings) {
            set(slot++, icon(listing), (clicker, click) -> open(clicker, listing));
        }
        if (listings.isEmpty()) {
            set(22, Icons.button(Material.BARRIER, "Здесь пока пусто", "Выложите что-нибудь первым"));
        }
        if (page > 1) {
            set(45, Icons.button(Material.ARROW, "Назад"), (clicker, click) -> gui.openBrowse(clicker, page - 1));
        }
        set(49, Icons.button(Material.COMPASS, "В главное меню"), (clicker, click) -> gui.openMain(clicker));
        if (listings.size() == PER_PAGE) {
            set(53, Icons.button(Material.ARROW, "Дальше"), (clicker, click) -> gui.openBrowse(clicker, page + 1));
        }
        fillEmpty();
    }

    private ItemStack icon(Listing listing) {
        ItemStack face = face(listing);
        String action = switch (listing.type()) {
            case GIVEAWAY -> "Клик — забрать";
            case GIFT -> "Подарок: заберёт только адресат";
            case TRADE, WANTED -> "Клик — предложить обмен";
        };
        String offered = "Отдают: " + summary(listing.offered());
        String wanted = listing.wanted().isEmpty() ? null : "Хотят: " + summary(listing.wanted());
        return Icons.preview(face, "#" + listing.id() + " " + typeName(listing.type()), offered, wanted, action);
    }

    private ItemStack face(Listing listing) {
        List<ListingItem> items = listing.offered().isEmpty() ? listing.wanted() : listing.offered();
        if (items.isEmpty()) {
            return new ItemStack(Material.PAPER);
        }
        try {
            return PaperItemCodec.decode(items.get(0).item().blob());
        } catch (RuntimeException unreadable) {
            // a broken item must not break the whole page
            return new ItemStack(Material.BARRIER);
        }
    }

    private String summary(List<ListingItem> items) {
        if (items.isEmpty()) {
            return "—";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(3, items.size()); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(items.get(index).item().summary());
        }
        if (items.size() > 3) {
            text.append(" и ещё ").append(items.size() - 3);
        }
        return text.toString();
    }

    private static String typeName(ListingType type) {
        return switch (type) {
            case GIVEAWAY -> "раздача";
            case TRADE -> "обмен";
            case WANTED -> "заявка";
            case GIFT -> "подарок";
        };
    }

    private void open(Player player, Listing listing) {
        if (listing.type() == ListingType.GIVEAWAY || listing.type() == ListingType.GIFT) {
            boolean taken = gui.run(player, () -> gui.market().claim(player.getUniqueId(), player.getName(), listing.id()));
            if (taken) {
                player.sendMessage(Messages.good("Забрали: " + MarketService.describe(listing)));
                gui.closeAndDeliver(player);
            }
            return;
        }
        gui.openOffer(player, listing.id());
    }
}
