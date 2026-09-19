package site.vinoff.market.bukkit;

import java.util.List;
import site.vinoff.market.core.ItemBlob;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.chest.ChestContents;
import site.vinoff.market.core.chest.ChestPlan;
import site.vinoff.market.core.model.BoundChest;
import site.vinoff.market.core.port.ContainerPort;

/**
 * A chest port that does nothing, because the real one is not written yet.
 *
 * <p>The core of the bound chest feature — the fingerprint, the journal number, the take protocol and its recovery —
 * is finished and under test. The half that actually touches blocks is not: it needs a hop to the main thread, chunk
 * loading and a verified answer about whether persistent data on a chest really is lost together with an unsaved
 * chunk. Until that exists this refuses, and nothing in the game or the API can reach a chest anyway, since no
 * command and no endpoint offers one.
 *
 * <p>It is a class rather than a null check inside the service on purpose: a null would have to be remembered at
 * every call site, and forgetting it once would be a crash in the middle of moving items.
 */
public final class UnboundContainerPort implements ContainerPort {

    @Override
    public ChestContents read(BoundChest chest) {
        throw refuse();
    }

    @Override
    public List<ItemBlob> take(BoundChest chest, ChestPlan plan, long seq) {
        throw refuse();
    }

    @Override
    public void stamp(BoundChest chest, long seq) {
        throw refuse();
    }

    @Override
    public int dataVersion() {
        return PaperItemCodec.serverDataVersion();
    }

    private static MarketException refuse() {
        return new MarketException(MarketError.CHEST_UNAVAILABLE, "Chests are not wired to the world yet");
    }
}
