package site.vinoff.market.bukkit;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Asks a player to type something in chat and hands the answer back on the main thread.
 *
 * <p>A chest window has no text field, so searching the catalogue has to go through chat. Only one question can be
 * outstanding per player, the message that answers it is swallowed rather than broadcast, and the answer is delivered
 * on the main thread because whatever happens next will touch inventories.
 */
public final class ChatPrompt implements Listener {

    /** Long enough to type a word, short enough that a forgotten prompt does not eat a normal message later. */
    private static final long TIMEOUT_MILLIS = 60_000;
    private static final int MAX_LENGTH = 64;

    private final Plugin plugin;
    private final Map<UUID, Question> waiting = new ConcurrentHashMap<>();

    public ChatPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    private record Question(Consumer<String> answer, long askedAt) {}

    /** The next thing this player types goes to {@code answer} instead of to chat. */
    public void ask(Player player, Consumer<String> answer) {
        waiting.put(player.getUniqueId(), new Question(answer, System.currentTimeMillis()));
    }

    public void forget(Player player) {
        waiting.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Question question = waiting.remove(event.getPlayer().getUniqueId());
        if (question == null) {
            return;
        }
        if (System.currentTimeMillis() - question.askedAt() > TIMEOUT_MILLIS) {
            // the player moved on long ago; let the message be an ordinary message
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        String answer = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                question.answer().accept(answer);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }

    public void clear() {
        waiting.clear();
    }
}
