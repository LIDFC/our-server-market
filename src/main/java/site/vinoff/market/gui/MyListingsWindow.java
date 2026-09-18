package site.vinoff.market.gui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.bukkit.Messages;
import site.vinoff.market.bukkit.PaperItemCodec;
import site.vinoff.market.core.ListingState;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.core.model.Listing;

/** The player's own listings, with one safety catch: cancelling asks for a second click. */
public final class MyListingsWindow extends MarketWindow {

    private final Gui gui;
    private final Set<Long> asked = new HashSet<>();

    public MyListingsWindow(Gui gui) {
        super("Мои лоты", 6);
        this.gui = gui;
    }

    @Override
    public void refresh(Player player) {
        clear();
        List<Listing> listings = gui.market().listingsOf(player.getUniqueId(), false);
        int slot = 0;
        for (Listing listing : listings) {
            if (slot >= 45) {
                break;
            }
            set(slot++, icon(listing), (clicker, click) -> cancel(clicker, listing));
        }
        if (listings.isEmpty()) {
            set(22, Icons.button(Material.BARRIER, "У вас нет лотов", "Создайте лот в главном меню"));
        }
        set(49, Icons.button(Material.COMPASS, "В главное меню"), (clicker, click) -> gui.openMain(clicker));
        fillEmpty();
    }

    private ItemStack icon(Listing listing) {
        ItemStack face;
        try {
            face = listing.offered().isEmpty()
                    ? new ItemStack(Material.PAPER)
                    : PaperItemCodec.decode(listing.offered().get(0).item().blob());
        } catch (RuntimeException unreadable) {
            face = new ItemStack(Material.BARRIER);
        }
        String action = listing.state() == ListingState.PENDING_TRADE
                ? "Идёт сделка — снять нельзя"
                : asked.contains(listing.id()) ? "Ещё раз — снять и забрать вещи" : "Клик — снять лот";
        return Icons.preview(
                face,
                "#" + listing.id() + " " + state(listing.state()),
                MarketService.describe(listing),
                action);
    }

    private void cancel(Player player, Listing listing) {
        if (listing.state() == ListingState.PENDING_TRADE) {
            player.sendMessage(Messages.info("По этому лоту идёт сделка: ответьте на предложение в «Мои сделки»"));
            return;
        }
        if (asked.add(listing.id())) {
            // first click only asks: a misclick must not undo a listing somebody is looking at
            gui.windows().refresh(player);
            return;
        }
        boolean cancelled = gui.run(player, () -> gui.market().cancel(player.getUniqueId(), listing.id(), false));
        if (cancelled) {
            player.sendMessage(Messages.good("Лот #" + listing.id() + " снят"));
            gui.closeAndDeliver(player);
        }
    }

    private static String state(ListingState state) {
        return switch (state) {
            case DRAFT -> "черновик";
            case ACTIVE -> "на рынке";
            case PENDING_TRADE -> "идёт сделка";
            case COMPLETED -> "завершён";
            case CANCELLED -> "снят";
            case EXPIRED -> "истёк";
        };
    }
}
