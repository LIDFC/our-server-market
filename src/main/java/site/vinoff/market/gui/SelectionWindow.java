package site.vinoff.market.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
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
 *
 * <p>The other half of a trade — what the player wants in return — is not in this window at all. It cannot be: you are
 * asking for something you do not own, so there is nothing here to tick. It is picked from {@link CatalogWindow}, and
 * the button that leads there lists the whole wish list so it is never a mystery.
 */
public final class SelectionWindow extends MarketWindow {

    private static final int GRID = 36;
    private static final int LEGEND_SLOT = 45;
    private static final int THEIRS_SLOT = 46;
    private static final int WISH_SLOT = 47;
    private static final int CANCEL_SLOT = 48;
    private static final int CONFIRM_SLOT = 50;

    private final Gui gui;
    private final ListingType type;
    private final String recipient;
    private final Long answeringListing;
    private final Selection selection = new Selection(MarketService.MAX_ITEMS_PER_LISTING);
    /** whether this listing has a "what I want back" half at all: a giveaway does not */
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

    /** The draft being built. The catalogue writes the wish list into it and hands the player back here. */
    Selection selection() {
        return selection;
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
            int chosenSlot = slot;
            set(
                    slot,
                    Icons.preview(
                            stack,
                            PaperItemCodec.summary(stack),
                            offered ? "► ОТДАЮ ЭТО" : null,
                            offered ? "Клик — передумать" : "Клик — отдать это"),
                    (clicker, click) -> toggle(clicker, chosenSlot, click));
        }

        set(LEGEND_SLOT, legend());
        if (answeringListing != null) {
            // answering somebody: show what their listing is, so the offer is not made blind
            set(THEIRS_SLOT, theirListing());
        }
        if (twoSided) {
            set(WISH_SLOT, wishButton(), (clicker, click) -> gui.openCatalogue(clicker, this, "", 1));
        }
        set(
                CANCEL_SLOT,
                Icons.button(Material.BARRIER, "Отмена", "Ничего не произойдёт, вещи останутся у вас"),
                (clicker, click) -> gui.openMain(clicker));
        set(CONFIRM_SLOT, confirmButton(), (clicker, click) -> confirm(clicker));
        fillEmpty();
    }

    /** The emerald says exactly what will happen, item by item: a count alone is not enough to press it. */
    private ItemStack confirmButton() {
        List<Component> lines = new ArrayList<>();
        lines.add(Icons.line(selection.offeredCount() == 0 ? "Вы пока ничего не отметили" : "Вы отдаёте:"));
        for (ItemBlob blob : selection.offered()) {
            lines.add(Icons.line("  • " + blob.summary()));
        }
        if (twoSided) {
            lines.add(Icons.line(selection.wantedCount() == 0 ? "Взамен: что угодно (не указано)" : "Хотите взамен:"));
            addWishLines(lines);
        }
        lines.add(Icons.line(" "));
        lines.add(Icons.line(answeringListing == null ? "Клик — выложить на рынок" : "Клик — отправить предложение"));
        lines.add(Icons.line("Отмеченное «отдаю» уйдёт рынку на хранение"));
        lines.add(Icons.line("и вернётся, если сделка не состоится"));
        return Icons.rich(
                selection.empty() ? Material.GRAY_DYE : Material.EMERALD,
                answeringListing == null ? "Выложить на рынок" : "Отправить предложение",
                lines);
    }

    /** The way into the catalogue, with the wish list built so far written on it. */
    private ItemStack wishButton() {
        List<Component> lines = new ArrayList<>();
        if (selection.wantedCount() == 0) {
            lines.add(Icons.line("Пока ничего не выбрано"));
            lines.add(Icons.line("Без этого лот значит «предложите что-нибудь»"));
        } else {
            lines.add(Icons.line("Вы хотите взамен:"));
            addWishLines(lines);
        }
        lines.add(Icons.line(" "));
        lines.add(Icons.line("Клик — открыть каталог всех предметов"));
        lines.add(Icons.line("Там есть поиск по названию"));
        return Icons.rich(
                selection.wantedCount() == 0 ? Material.HOPPER : Material.CHEST,
                "Что хочу взамен",
                lines);
    }

    /** The wish list as lore. Item names are left to the client, so each player reads them in their own language. */
    private void addWishLines(List<Component> lines) {
        for (Map.Entry<String, Integer> entry : selection.wanted().entrySet()) {
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null) {
                lines.add(Icons.line("  • " + entry.getValue() + " шт. " + entry.getKey()));
            } else {
                lines.add(Icons.line("  • " + entry.getValue() + " шт. ", material));
            }
        }
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
            lines.add("  • ничего (это заявка)");
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
                "Снизу — ваши вещи: клик по вещи",
                "значит «я это отдаю».",
                "Что вы хотите взамен — в каталоге",
                "справа, вещи для этого не нужны.");
    }

    private void toggle(Player player, int slot, GuiPolicy.Click click) {
        if (click == GuiPolicy.Click.DOUBLE_CLICK) {
            // a second event after an ordinary click; acting on it would undo what the player just did
            return;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir() || Icons.isGuiItem(stack)) {
            gui.windows().refresh(player);
            return;
        }
        Selection.Result result = selection.toggleOffered(slot, PaperItemCodec.encode(stack));
        if (result == Selection.Result.TOO_MANY) {
            player.sendMessage(Messages.bad("Больше " + MarketService.MAX_ITEMS_PER_LISTING + " стаков в один лот нельзя"));
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

        List<ItemBlob> wanted = wishList();
        if (wanted.size() > MarketService.MAX_ITEMS_PER_LISTING) {
            player.sendMessage(Messages.bad("В «хочу» получилось больше " + MarketService.MAX_ITEMS_PER_LISTING
                    + " стаков — уменьшите количество"));
            return;
        }
        if (offered.isEmpty() && type != ListingType.WANTED) {
            player.sendMessage(Messages.bad("Отметьте хотя бы один предмет"));
            return;
        }
        if (type == ListingType.WANTED && wanted.isEmpty()) {
            player.sendMessage(Messages.bad("Выберите в каталоге, что вы ищете"));
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

    /**
     * Turns the wish list into item blobs. An amount larger than a stack becomes several stacks, because that is how a
     * wish for 128 diamonds has to be written down; nothing is taken from anybody, these are descriptions.
     */
    private List<ItemBlob> wishList() {
        List<ItemBlob> blobs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : selection.wanted().entrySet()) {
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null) {
                continue;
            }
            int max = Math.max(1, material.getMaxStackSize());
            int left = entry.getValue();
            while (left > 0) {
                int take = Math.min(left, max);
                blobs.add(PaperItemCodec.encode(new ItemStack(material, take)));
                left -= take;
            }
        }
        return blobs;
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
