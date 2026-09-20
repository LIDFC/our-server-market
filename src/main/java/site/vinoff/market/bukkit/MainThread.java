package site.vinoff.market.bukkit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;

/**
 * Runs a piece of work on the server thread and waits for its answer.
 *
 * <p>The marketplace API answers browsers on its own threads, which is safe as long as everything it touches is the
 * database. A chest is not: blocks and inventories may only be read or changed on the server thread. So a request
 * that reaches into a chest has to stop, hand the work over, and wait.
 *
 * <p>Waiting forever is not an option — a stalled server would hold every HTTP thread — but neither is giving up
 * blindly. A task that has <em>not started</em> can be dropped safely; a task that is <em>already moving items in a
 * chest</em> cannot be dropped at any price, because the alternative is a half emptied chest with an error message in
 * somebody's browser. The two are told apart by a compare-and-set on the stage: the caller can only abandon work that
 * is still {@code PENDING}, and the task itself refuses to start once it has been abandoned.
 *
 * <p>One rule holds this together and cannot be checked by the compiler: <b>the work handed in here never touches the
 * database.</b> The database is guarded by a single connection, so a server thread waiting for it while the thread
 * holding it waits for the server thread is a deadlock with no way out. {@link BukkitContainerPort} is built without
 * a database field at all, which is how that rule is kept structurally rather than by memory.
 */
public final class MainThread {

    /** How long a caller waits before deciding the server is too busy to bother. */
    private static final long SOFT_TIMEOUT_MS = 2_000;

    /** How long a caller waits for work that has already started. Long, because the alternative is losing items. */
    private static final long HARD_TIMEOUT_MS = 30_000;

    private enum Stage {
        PENDING,
        RUNNING,
        DONE,
        /** the caller gave up before the task started; the task must never run now */
        ABANDONED
    }

    private final Plugin plugin;
    private final Logger log;

    public MainThread(Plugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    public <T> T call(String what, Supplier<T> work) {
        if (plugin.getServer().isPrimaryThread()) {
            return work.get();
        }
        AtomicReference<Stage> stage = new AtomicReference<>(Stage.PENDING);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!stage.compareAndSet(Stage.PENDING, Stage.RUNNING)) {
                    return;
                }
                try {
                    value.set(work.get());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    stage.set(Stage.DONE);
                    finished.countDown();
                }
            });
        } catch (RuntimeException stopping) {
            // the scheduler refuses new work once the plugin is being disabled
            throw new MarketException(MarketError.CHEST_UNAVAILABLE, "The server is shutting down", stopping);
        }

        waitFor(finished, stage, what);
        Throwable thrown = failure.get();
        if (thrown != null) {
            rethrow(thrown, what);
        }
        return value.get();
    }

    public void run(String what, Runnable work) {
        call(what, () -> {
            work.run();
            return null;
        });
    }

    private void waitFor(CountDownLatch finished, AtomicReference<Stage> stage, String what) {
        try {
            if (finished.await(SOFT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            if (stage.compareAndSet(Stage.PENDING, Stage.ABANDONED)) {
                throw new MarketException(
                        MarketError.CHEST_UNAVAILABLE, "The server did not get to " + what + " in time");
            }
            // it is already running, which means it may be halfway through a chest: waiting is the only safe answer
            log.warning("Waiting on the server thread to finish: " + what);
            if (!finished.await(HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.severe("The server thread has not finished " + what + " after "
                        + (HARD_TIMEOUT_MS / 1000) + " seconds; the chest may be left mid-operation");
                throw new MarketException(MarketError.CHEST_UNAVAILABLE, "The server thread is stuck on " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MarketException(MarketError.CHEST_UNAVAILABLE, "Interrupted while waiting for " + what);
        }
    }

    private static void rethrow(Throwable thrown, String what) {
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        throw new MarketException(MarketError.CHEST_UNAVAILABLE, "Could not " + what, thrown);
    }
}
