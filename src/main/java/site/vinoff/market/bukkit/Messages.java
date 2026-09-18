package site.vinoff.market.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import site.vinoff.market.core.MarketError;

/** What players read. Russian, because that is the language of the server. */
public final class Messages {

    public static final Component PREFIX = Component.text("[Рынок] ", NamedTextColor.GOLD);

    private Messages() {}

    public static Component info(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.WHITE));
    }

    public static Component good(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GREEN));
    }

    public static Component bad(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.RED));
    }

    public static Component hint(String text) {
        return Component.text("  " + text, NamedTextColor.GRAY);
    }

    /** Turns a refusal from the core into something a player can act on. */
    public static Component of(MarketError error, String fallback) {
        return bad(switch (error) {
            case LISTING_NOT_FOUND -> "Такого лота нет";
            case TRADE_NOT_FOUND -> "Такой сделки нет";
            case DELIVERY_NOT_FOUND -> "Такой посылки нет";
            case PLAYER_NOT_FOUND -> "Такой игрок не заходил на сервер";
            case NOT_OWNER -> "Это не ваш лот";
            case NOT_PARTICIPANT -> "Это не ваша сделка";
            case NOT_RECIPIENT -> "Этот подарок не вам";
            case LISTING_NOT_ACTIVE -> "По этому лоту уже идёт сделка";
            case LISTING_ALREADY_TAKEN -> "Вас опередили";
            case LISTING_NOT_DRAFT -> "Черновик уже опубликован";
            case TRADE_NOT_PENDING -> "Это предложение уже не ждёт ответа";
            case TRADE_NOT_ACCEPTED -> "Владелец ещё не принял предложение";
            case TRADE_ALREADY_FINISHED -> "Сделка уже завершена";
            case ALREADY_CONFIRMED -> "Вы уже подтвердили";
            case OWN_LISTING -> "Это ваш собственный лот";
            case EMPTY_LISTING -> "Сначала положите в лот хотя бы один предмет";
            case TOO_MANY_ITEMS -> "В один лот помещается не больше 27 стаков";
            case INVALID_REQUEST -> fallback == null ? "Так нельзя" : fallback;
            case ITEM_DATA_CORRUPT -> "Предмет не читается, позовите администратора";
            case STORAGE_FAILURE -> "Рынок сейчас недоступен, попробуйте позже";
        });
    }

    public static Component help() {
        return Component.text()
                .append(info("команды рынка:"))
                .append(Component.newline())
                .append(hint("/market browse [страница] — что сейчас продают и раздают"))
                .append(Component.newline())
                .append(hint("/market create giveaway — отдать вещь даром"))
                .append(Component.newline())
                .append(hint("/market create trade — обмен: сначала что отдаёте, потом /market want"))
                .append(Component.newline())
                .append(hint("/market create gift <ник> — подарок конкретному игроку"))
                .append(Component.newline())
                .append(hint("/market add — положить предмет из руки в черновик"))
                .append(Component.newline())
                .append(hint("/market want — записать, что вы хотите получить"))
                .append(Component.newline())
                .append(hint("/market publish — выложить черновик на рынок"))
                .append(Component.newline())
                .append(hint("/market take <лот> — забрать раздачу или подарок"))
                .append(Component.newline())
                .append(hint("/market offer <лот> — предложить предмет из руки в обмен"))
                .append(Component.newline())
                .append(hint("/market accept|decline <сделка> — ответить на предложение"))
                .append(Component.newline())
                .append(hint("/market confirm <сделка> — подтвердить обмен (нужно от обеих сторон)"))
                .append(Component.newline())
                .append(hint("/market mine | /market trades | /market cancel <лот>"))
                .append(Component.newline())
                .append(hint("/market deliveries — забрать то, что вас ждёт"))
                .build();
    }
}
