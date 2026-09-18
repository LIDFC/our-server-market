package site.vinoff.market.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.ListingState;
import site.vinoff.market.core.ListingType;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.core.model.Identity;
import site.vinoff.market.core.model.Listing;
import site.vinoff.market.core.model.PendingDelivery;
import site.vinoff.market.core.model.Trade;
import site.vinoff.market.gui.Gui;

/**
 * The /market command. Everything here runs on the server thread and hands straight over to the core, which is the only
 * thing allowed to decide whether an item may move.
 *
 * <p>There is no session state in memory: a draft is simply the player's own listing that is still a draft, so a
 * disconnect or a restart in the middle of building one changes nothing.
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("browse", "create", "add", "want", "publish", "take", "offer", "accept", "decline", "confirm", "cancel", "mine",
                    "trades", "deliveries", "help", "admin");

    private final MarketService market;
    private final Gui gui;

    public MarketCommand(MarketService market, Gui gui) {
        this.market = market;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Рынок работает только в игре.");
            return true;
        }
        if (!player.hasPermission("ourserver.market.use")) {
            player.sendMessage(Messages.bad("У вас нет доступа к рынку"));
            return true;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        try {
            handle(player, sub, args);
        } catch (MarketException refused) {
            player.sendMessage(Messages.of(refused.error(), refused.getMessage()));
        } catch (RuntimeException failure) {
            player.sendMessage(Messages.bad("Рынок не смог выполнить команду, попробуйте позже"));
            throw failure;
        }
        return true;
    }

    private void handle(Player player, String sub, String[] args) {
        UUID uuid = player.getUniqueId();
        switch (sub) {
            case "menu" -> gui.openMain(player);
            case "browse" -> {
                if (args.length > 1) {
                    browse(player, parsePage(args[1]));
                } else {
                    gui.openBrowse(player, 1);
                }
            }
            case "create" -> create(player, args);
            case "add" -> addToDraft(player, false);
            case "want" -> addToDraft(player, true);
            case "publish" -> publish(player);
            case "take" -> take(player, requireId(args, "лота"));
            case "offer" -> offer(player, requireId(args, "лота"));
            case "accept" -> {
                requirePermission(player, "ourserver.market.trade");
                market.acceptTrade(uuid, requireId(args, "сделки"));
                player.sendMessage(Messages.good("Предложение принято. Теперь обе стороны подтверждают: /market confirm"));
            }
            case "decline" -> {
                market.declineTrade(uuid, requireId(args, "сделки"), false);
                player.sendMessage(Messages.info("Предложение отклонено, предметы возвращены"));
            }
            case "confirm" -> confirm(player, requireId(args, "сделки"));
            case "cancel" -> {
                market.cancel(uuid, requireId(args, "лота"), false);
                player.sendMessage(Messages.info("Лот снят, предметы ждут вас: /market deliveries"));
            }
            case "mine" -> mine(player);
            case "trades" -> trades(player);
            case "deliveries", "pending" -> deliveries(player);
            case "admin" -> admin(player, args);
            default -> player.sendMessage(Messages.help());
        }
    }

    // player commands ----------------------------------------------------------------------------------------------

    private void browse(Player player, int page) {
        int perPage = 8;
        List<Listing> listings = market.activeListings(null, perPage, (page - 1) * perPage);
        if (listings.isEmpty()) {
            player.sendMessage(Messages.info(page == 1 ? "На рынке пока пусто" : "На этой странице ничего нет"));
            return;
        }
        player.sendMessage(Messages.info("страница " + page + ":"));
        for (Listing listing : listings) {
            player.sendMessage(Component.text("  #" + listing.id() + " ", NamedTextColor.GOLD)
                    .append(Component.text(MarketService.describe(listing), NamedTextColor.WHITE))
                    .append(Component.text(listing.type() == ListingType.TRADE || listing.type() == ListingType.WANTED
                                    ? "  →  /market offer " + listing.id()
                                    : "  →  /market take " + listing.id(), NamedTextColor.GRAY)));
        }
    }

    private void create(Player player, String[] args) {
        requirePermission(player, "ourserver.market.create");
        if (args.length < 2) {
            player.sendMessage(Messages.bad("Что создаём: giveaway, trade, wanted или gift <ник>"));
            return;
        }
        ListingType type;
        try {
            type = ListingType.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            player.sendMessage(Messages.bad("Бывают только giveaway, trade, wanted и gift"));
            return;
        }
        UUID recipient = null;
        String recipientName = null;
        if (type == ListingType.GIFT) {
            if (args.length < 3) {
                player.sendMessage(Messages.bad("Кому подарок? /market create gift <ник>"));
                return;
            }
            recipientName = args[2];
            Optional<Identity> known = market.findPlayer(recipientName);
            recipient = known.map(Identity::uuid).orElseGet(() -> BukkitInventoryPort.offlineUuid(args[2]));
            if (known.isEmpty()) {
                player.sendMessage(Messages.info("Такой игрок ещё не заходил; подарок будет ждать его"));
            }
        }
        long id = market.createDraft(player.getUniqueId(), player.getName(), type, recipient, recipientName, null);
        player.sendMessage(Messages.good("Черновик #" + id + " создан"));
        player.sendMessage(Messages.hint("/market add — положить предмет из руки"));
        if (type == ListingType.TRADE || type == ListingType.WANTED) {
            player.sendMessage(Messages.hint("/market want — сказать, что вы хотите взамен"));
        }
        player.sendMessage(Messages.hint("/market publish — выложить на рынок"));
    }

    private void addToDraft(Player player, boolean wanted) {
        requirePermission(player, "ourserver.market.create");
        Listing draft = currentDraft(player);
        ItemBlob blob = itemInHand(player);
        if (wanted) {
            market.addWanted(player.getUniqueId(), draft.id(), List.of(blob));
            player.sendMessage(Messages.good("Записал: вы хотите " + blob.summary()));
            return;
        }
        market.addOffer(player.getUniqueId(), draft.id(), List.of(blob));
        player.sendMessage(Messages.good("Положил в лот #" + draft.id() + ": " + blob.summary()));
        player.sendMessage(Messages.hint("предметы уже у рынка; /market cancel " + draft.id() + " вернёт их"));
    }

    private void publish(Player player) {
        Listing draft = currentDraft(player);
        market.publish(player.getUniqueId(), draft.id());
        player.sendMessage(Messages.good("Лот #" + draft.id() + " на рынке"));
    }

    private void take(Player player, long listingId) {
        requirePermission(player, "ourserver.market.trade");
        market.claim(player.getUniqueId(), player.getName(), listingId);
        player.sendMessage(Messages.good("Забрали лот #" + listingId));
        deliverNow(player);
    }

    private void offer(Player player, long listingId) {
        requirePermission(player, "ourserver.market.trade");
        ItemBlob blob = itemInHand(player);
        long trade = market.offerTrade(player.getUniqueId(), player.getName(), listingId, List.of(blob));
        player.sendMessage(Messages.good("Предложение #" + trade + " отправлено: " + blob.summary()));
        player.sendMessage(Messages.hint("предметы у рынка; /market decline " + trade + " вернёт их"));
    }

    private void confirm(Player player, long tradeId) {
        requirePermission(player, "ourserver.market.trade");
        boolean finished = market.confirmTrade(player.getUniqueId(), tradeId);
        if (finished) {
            player.sendMessage(Messages.good("Обмен состоялся"));
            deliverNow(player);
        } else {
            player.sendMessage(Messages.info("Ваше подтверждение принято, ждём вторую сторону"));
        }
    }

    private void mine(Player player) {
        List<Listing> listings = market.listingsOf(player.getUniqueId(), false);
        if (listings.isEmpty()) {
            player.sendMessage(Messages.info("У вас нет лотов"));
            return;
        }
        player.sendMessage(Messages.info("ваши лоты:"));
        for (Listing listing : listings) {
            player.sendMessage(Component.text("  #" + listing.id() + " ", NamedTextColor.GOLD)
                    .append(Component.text(state(listing.state()) + " — " + MarketService.describe(listing), NamedTextColor.WHITE)));
        }
    }

    private void trades(Player player) {
        List<Trade> trades = market.tradesOf(player.getUniqueId(), false);
        if (trades.isEmpty()) {
            player.sendMessage(Messages.info("Сейчас у вас нет сделок"));
            return;
        }
        player.sendMessage(Messages.info("ваши сделки:"));
        for (Trade trade : trades) {
            boolean mineToAnswer = trade.ownerUuid().equals(player.getUniqueId()) && trade.state().name().equals("PENDING");
            String hint = mineToAnswer
                    ? " → /market accept " + trade.id() + " или /market decline " + trade.id()
                    : trade.state().name().equals("ACCEPTED") ? " → /market confirm " + trade.id() : "";
            player.sendMessage(Component.text("  #" + trade.id() + " ", NamedTextColor.GOLD)
                    .append(Component.text("лот #" + trade.listingId() + ", " + trade.state(), NamedTextColor.WHITE))
                    .append(Component.text(hint, NamedTextColor.GRAY)));
        }
    }

    private void deliveries(Player player) {
        List<PendingDelivery> waiting = market.pendingDeliveries(player.getUniqueId());
        if (waiting.isEmpty()) {
            player.sendMessage(Messages.info("Вас ничего не ждёт"));
            return;
        }
        int handed = market.claimDeliveries(player.getUniqueId());
        int left = market.pendingCount(player.getUniqueId());
        if (handed > 0) {
            player.sendMessage(Messages.good("Выдано предметов: " + handed));
        }
        if (left > 0) {
            player.sendMessage(Messages.info("Ещё ждут: " + left + ". Освободите место в инвентаре и повторите"));
        }
    }

    private void admin(Player player, String[] args) {
        requirePermission(player, "ourserver.market.admin");
        if (args.length < 3) {
            player.sendMessage(Messages.bad("/market admin info <лот> | cancel <лот> | inspect <ник> | reassign <старый ник> <новый ник>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "info" -> {
                Listing listing = market.listing(parseId(args[2], "лота"))
                        .orElseThrow(() -> new MarketException(MarketError.LISTING_NOT_FOUND, "нет такого лота"));
                player.sendMessage(Messages.info("#" + listing.id() + " " + listing.type() + " " + listing.state()
                        + " владелец " + listing.ownerUuid() + " — " + MarketService.describe(listing)));
            }
            case "cancel" -> {
                long id = parseId(args[2], "лота");
                market.cancel(player.getUniqueId(), id, true);
                player.sendMessage(Messages.good("Лот #" + id + " снят, предметы ушли владельцу"));
            }
            case "inspect" -> player.sendMessage(Messages.info(market.inspect(identityOf(args[2]))));
            case "reassign" -> {
                if (args.length < 4) {
                    player.sendMessage(Messages.bad("/market admin reassign <старый ник> <новый ник>"));
                    return;
                }
                UUID from = identityOf(args[2]);
                UUID to = identityOf(args[3]);
                MarketService.ReassignReport report = market.reassign(from, to, player.getUniqueId());
                player.sendMessage(Messages.good("Перенесено: лотов " + report.listings() + ", эскроу "
                        + report.escrowRows() + ", посылок " + report.deliveries()));
                player.sendMessage(Messages.hint("запись об этом есть в журнале рынка"));
            }
            default -> player.sendMessage(Messages.bad("/market admin info|cancel|inspect|reassign"));
        }
    }

    /** A nickname the marketplace has seen, or the offline UUID it would have. */
    private UUID identityOf(String name) {
        return market.findPlayer(name).map(Identity::uuid).orElseGet(() -> BukkitInventoryPort.offlineUuid(name));
    }

    // helpers ------------------------------------------------------------------------------------------------------

    private void deliverNow(Player player) {
        int handed = market.claimDeliveries(player.getUniqueId());
        int left = market.pendingCount(player.getUniqueId());
        if (handed > 0) {
            player.sendMessage(Messages.good("Предметы у вас"));
        }
        if (left > 0) {
            player.sendMessage(Messages.info("Что-то не поместилось: освободите место и наберите /market deliveries"));
        }
    }

    private Listing currentDraft(Player player) {
        return market.listingsOf(player.getUniqueId(), false).stream()
                .filter(listing -> listing.state() == ListingState.DRAFT)
                .findFirst()
                .orElseThrow(() -> new MarketException(
                        MarketError.LISTING_NOT_DRAFT, "Сначала создайте черновик: /market create giveaway"));
    }

    private ItemBlob itemInHand(Player player) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack.getType().isAir()) {
            throw new MarketException(MarketError.INVALID_REQUEST, "Возьмите предмет в руку");
        }
        return PaperItemCodec.encode(stack);
    }

    private void requirePermission(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            throw new MarketException(MarketError.NOT_OWNER, "У вас нет прав на это действие");
        }
    }

    private static long requireId(String[] args, String what) {
        if (args.length < 2) {
            throw new MarketException(MarketError.INVALID_REQUEST, "Укажите номер " + what);
        }
        return parseId(args[1], what);
    }

    private static long parseId(String text, String what) {
        try {
            return Long.parseLong(text.replace("#", ""));
        } catch (NumberFormatException notANumber) {
            throw new MarketException(MarketError.INVALID_REQUEST, "Номер " + what + " — это число");
        }
    }

    private static int parsePage(String text) {
        try {
            return Math.max(1, Integer.parseInt(text));
        } catch (NumberFormatException notANumber) {
            return 1;
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

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String option : SUBCOMMANDS) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    matches.add(option);
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return List.of("giveaway", "trade", "wanted", "gift");
        }
        return List.of();
    }
}
