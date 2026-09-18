package site.vinoff.market.gui;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.vinoff.market.bukkit.Messages;
import site.vinoff.market.bukkit.PaperItemCodec;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.ListingState;
import site.vinoff.market.core.ListingType;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.core.model.Listing;

/**
 * Building a listing, or answering one with an offer.
 *
 * <p>The player's own inventory is shown as pictures and ticked with clicks. Nothing is moved while the window is
 * open: at the end the marketplace takes exactly the ticked stacks in one transaction, and if anything has changed in
 * the meantime it refuses instead of taking the wrong thing. That is why this window cannot duplicate anything even if
 * the server dies halfway through.
 */
public final class SelectionWindow extends MarketWindow {

    private static final int GRID = 36;

    private final Gui gui;
    private final ListingType type;
    private final String recipient;
    private final Long answeringListing;
    private final Selection selection = new Selection(MarketService.MAX_ITEMS_PER_LISTING);
    private boolean wantMode;

    private SelectionWindow(Gui gui, ListingType type, String recipient, Long answeringListing) {
        super(answeringListing == null ? "Что выкладываем" : "Что предлагаем", 6);
        this.gui = gui;
        this.type = type;
        this.recipient = recipient;
        this.answeringListing = answeringListing;
    }

    public static SelectionWindow forCreating(Gui gui, ListingType type, String recipient) {
        return new SelectionWindow(gui, type, recipient, null);
    }

    public static SelectionWindow forOffering(Gui gui, long listingId) {
        return new SelectionWindow(gui, ListingType.TRADE, null, listingId);
    }

    @Override
    public void refresh(Player player) {
        clear();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < GRID && slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) {
                selection.forget(slot);
                set(slot, Icons.filler());
                continue;
            }
            if (Icons.isGuiItem(stack)) {
                // a picture from another window has no business being here
                set(slot, Icons.button(Material.BARRIER, "Этот предмет нельзя выложить"));
                continue;
            }
            boolean offered = selection.isOffered(slot);
            boolean wanted = selection.isWanted(slot);
            String mark = offered ? "ОТДАЮ" : wanted ? "ХОЧУ" : "клик — выбрать";
            int chosenSlot = slot;
            set(
                    slot,
                    Icons.preview(stack, PaperItemCodec.summary(stack), mark, offered || wanted ? "клик — убрать" : null),
                    (clicker, click) -> toggle(clicker, chosenSlot));
        }

        boolean canWant = answeringListing == null && (type == ListingType.TRADE || type == ListingType.WANTED);
        if (canWant) {
            set(
                    45,
                    Icons.button(
                            wantMode ? Material.LIME_DYE : Material.GRAY_DYE,
                            wantMode ? "Отмечаю: ЧТО ХОЧУ" : "Отмечаю: ЧТО ОТДАЮ",
                            "Клик — переключить"),
                    (clicker, click) -> {
                        wantMode = !wantMode;
                        gui.windows().refresh(clicker);
                    });
        }
        set(
                48,
                Icons.button(Material.BARRIER, "Отмена", "Ничего не произойдёт"),
                (clicker, click) -> gui.openMain(clicker));
        set(
                50,
                Icons.button(
                        Material.EMERALD,
                        answeringListing == null ? "Выложить на рынок" : "Отправить предложение",
                        "Отдаю: " + selection.offeredCount() + " стак(ов)",
                        canWant ? "Хочу: " + selection.wantedCount() + " стак(ов)" : null),
                (clicker, click) -> confirm(clicker));
        fillEmpty();
    }

    private void toggle(Player player, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir() || Icons.isGuiItem(stack)) {
            gui.windows().refresh(player);
            return;
        }
        ItemBlob blob = PaperItemCodec.encode(stack);
        Selection.Result result = wantMode ? selection.toggleWanted(slot, blob) : selection.toggleOffered(slot, blob);
        switch (result) {
            case TOO_MANY -> player.sendMessage(Messages.bad("Больше " + MarketService.MAX_ITEMS_PER_LISTING + " стаков в один лот нельзя"));
            case ALREADY_OFFERED -> player.sendMessage(Messages.info("Этот предмет уже отмечен как отдаваемый"));
            case ALREADY_WANTED -> player.sendMessage(Messages.info("Этот предмет уже отмечен как желаемый"));
            default -> { }
        }
        gui.windows().refresh(player);
    }

    private void confirm(Player player) {
        List<ItemBlob> offered = selection.offered();
        List<ItemBlob> wanted = selection.wantedItems();
        if (answeringListing != null) {
            if (offered.isEmpty()) {
                player.sendMessage(Messages.bad("Отметьте, что предлагаете"));
                return;
            }
            boolean sent = gui.run(player, () -> {
                long trade = gui.market().offerTrade(player.getUniqueId(), player.getName(), answeringListing, offered);
                player.sendMessage(Messages.good("Предложение #" + trade + " отправлено"));
            });
            if (sent) {
                selection.clear();
                gui.openMain(player);
            }
            return;
        }

        if (offered.isEmpty() && type != ListingType.WANTED) {
            player.sendMessage(Messages.bad("Отметьте хотя бы один предмет"));
            return;
        }
        if (type == ListingType.WANTED && wanted.isEmpty()) {
            player.sendMessage(Messages.bad("Отметьте, что вы ищете"));
            return;
        }
        gui.run(player, () -> {
            long listingId = draftFor(player);
            if (!offered.isEmpty()) {
                gui.market().addOffer(player.getUniqueId(), listingId, offered);
            }
            if (!wanted.isEmpty()) {
                gui.market().addWanted(player.getUniqueId(), listingId, wanted);
            }
            gui.market().publish(player.getUniqueId(), listingId);
            player.sendMessage(Messages.good("Лот #" + listingId + " на рынке"));
            selection.clear();
            gui.openMain(player);
        });
    }

    /** Reuses the player's open draft if they have one, so a half built listing is never left behind. */
    private long draftFor(Player player) {
        return gui.market().listingsOf(player.getUniqueId(), false).stream()
                .filter(listing -> listing.state() == ListingState.DRAFT && listing.type() == type)
                .map(Listing::id)
                .findFirst()
                .orElseGet(() -> gui.market()
                        .createDraft(player.getUniqueId(), player.getName(), type, recipientUuid(), recipient, null));
    }

    private java.util.UUID recipientUuid() {
        if (recipient == null) {
            return null;
        }
        return gui.market()
                .findPlayer(recipient)
                .map(site.vinoff.market.core.model.Identity::uuid)
                .orElseGet(() -> site.vinoff.market.bukkit.BukkitInventoryPort.offlineUuid(recipient));
    }
}
