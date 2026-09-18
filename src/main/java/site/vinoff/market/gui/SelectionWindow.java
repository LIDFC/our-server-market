package site.vinoff.market.gui;

import java.util.ArrayList;
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
    /** whether this window has a second half at all: a giveaway wants nothing back */
    private final boolean twoSided;
    /** a second click on "publish" before the first one finished must not start a second listing */
    private boolean submitting;

    private SelectionWindow(Gui gui, ListingType type, String recipient, Long answeringListing) {
        super(answeringListing == null ? "Что выкладываем" : "Что предлагаем", 6);
        this.gui = gui;
        this.type = type;
        this.recipient = recipient;
        this.answeringListing = answeringListing;
        // an offer on somebody else's listing only has one side: you cannot ask for something back
        this.twoSided = answeringListing == null && (type == ListingType.TRADE || type == ListingType.WANTED);
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
            String mark = offered ? "► ОТДАЮ ЭТО" : wanted ? "► ХОЧУ ЭТО ВЗАМЕН" : null;
            String left = offered ? "ЛКМ — убрать из «отдаю»" : "ЛКМ — отдать это";
            String right = !twoSided ? null : wanted ? "ПКМ — убрать из «хочу»" : "ПКМ — хочу такое взамен";
            int chosenSlot = slot;
            set(
                    slot,
                    Icons.preview(stack, PaperItemCodec.summary(stack), mark, left, right),
                    (clicker, click) -> toggle(clicker, chosenSlot, click));
        }

        set(45, legend());
        if (answeringListing != null) {
            // answering somebody: show what their listing is, so the offer is not made blind
            set(46, theirListing());
        }
        set(
                48,
                Icons.button(Material.BARRIER, "Отмена", "Ничего не произойдёт, вещи останутся у вас"),
                (clicker, click) -> gui.openMain(clicker));
        set(50, confirmButton(), (clicker, click) -> confirm(clicker));
        fillEmpty();
    }

    /** The emerald says exactly what will happen, item by item: a count alone is not enough to press it. */
    private ItemStack confirmButton() {
        List<String> lines = new ArrayList<>();
        lines.add(selection.offeredCount() == 0 ? "Вы пока ничего не отметили" : "Вы отдаёте:");
        for (ItemBlob blob : selection.offered()) {
            lines.add("  • " + blob.summary());
        }
        if (twoSided) {
            lines.add(selection.wantedCount() == 0 ? "Взамен: что угодно (не указано)" : "Хотите взамен:");
            for (ItemBlob blob : selection.wantedItems()) {
                lines.add("  • " + blob.summary());
            }
        }
        lines.add(" ");
        lines.add(answeringListing == null ? "Клик — выложить на рынок" : "Клик — отправить предложение");
        lines.add("Отмеченное «отдаю» уйдёт рынку на хранение");
        lines.add("и вернётся, если сделка не состоится");
        return Icons.button(
                selection.empty() ? Material.GRAY_DYE : Material.EMERALD,
                answeringListing == null ? "Выложить на рынок" : "Отправить предложение",
                lines.toArray(new String[0]));
    }

    /** The listing being answered, spelled out: what you get and what its author asked for. */
    private ItemStack theirListing() {
        Listing listing = gui.market().listing(answeringListing).orElse(null);
        if (listing == null) {
            return Icons.button(Material.BARRIER, "Лот уже закрыт");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Вы получите:");
        listing.offered().forEach(item -> lines.add("  • " + item.item().summary()));
        if (listing.offered().isEmpty()) {
            lines.add("  • ничего (заявка)");
        }
        if (!listing.wanted().isEmpty()) {
            lines.add("Автор хочет за это:");
            listing.wanted().forEach(item -> lines.add("  • " + item.item().summary()));
        } else {
            lines.add("Автор не указал, что хочет");
        }
        return Icons.button(Material.PAPER, "Лот #" + listing.id(), lines.toArray(new String[0]));
    }

    private ItemStack legend() {
        if (answeringListing != null) {
            return Icons.button(
                    Material.BOOK,
                    "Как это работает",
                    "Кликните по своим вещам снизу —",
                    "это и будет ваше предложение.",
                    "Справа написано, что предлагают вам.");
        }
        if (!twoSided) {
            return Icons.button(Material.BOOK, "Как это работает", "Кликните по своим вещам снизу —", "они станут лотом");
        }
        return Icons.button(
                Material.BOOK,
                "Как это работает",
                "ЛКМ по вещи — я это отдаю",
                "ПКМ по вещи — я такое хочу взамен",
                "(для «хочу» вещь остаётся у вас,",
                " это просто пожелание)");
    }

    private void toggle(Player player, int slot, GuiPolicy.Click click) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir() || Icons.isGuiItem(stack)) {
            gui.windows().refresh(player);
            return;
        }
        ItemBlob blob = PaperItemCodec.encode(stack);
        // right click means "this is what I want back", and only where that makes sense
        boolean asWanted = twoSided && (click == GuiPolicy.Click.RIGHT || click == GuiPolicy.Click.SHIFT_RIGHT);
        Selection.Result result = asWanted ? selection.toggleWanted(slot, blob) : selection.toggleOffered(slot, blob);
        switch (result) {
            case TOO_MANY -> player.sendMessage(Messages.bad("Больше " + MarketService.MAX_ITEMS_PER_LISTING + " стаков в один лот нельзя"));
            case ALREADY_OFFERED -> player.sendMessage(Messages.info("Этот предмет уже отмечен как отдаваемый"));
            case ALREADY_WANTED -> player.sendMessage(Messages.info("Этот предмет уже отмечен как желаемый"));
            default -> { }
        }
        gui.windows().refresh(player);
    }

    private void confirm(Player player) {
        if (submitting) {
            return;
        }
        submitting = true;
        try {
            submit(player);
        } finally {
            submitting = false;
        }
    }

    private void submit(Player player) {
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
