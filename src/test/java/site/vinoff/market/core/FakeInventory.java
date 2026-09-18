package site.vinoff.market.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import site.vinoff.market.core.port.InventoryPort;
import site.vinoff.market.storage.MarketRepository;

/**
 * An inventory the tests can control: it holds stacks, it can be full, it can refuse to flush, and it can be made to
 * throw at the exact moment a real server would crash.
 *
 * <p>It deliberately mirrors the parts of Bukkit behaviour the marketplace depends on: a fingerprint over the contents,
 * a limited number of slots, and all or nothing removal.
 */
public final class FakeInventory implements InventoryPort {

    public static final int DEFAULT_SLOTS = 36;

    private final Map<UUID, List<ItemBlob>> contents = new HashMap<>();
    private final Map<UUID, Integer> slots = new HashMap<>();
    private final Map<UUID, Boolean> ready = new HashMap<>();

    private int dataVersion = 4325;
    /** when set, the next call of this kind throws, which is what a crash looks like from the outside */
    private String crashOn;
    private boolean saveFails;

    public void give(UUID player, ItemBlob... items) {
        contents.computeIfAbsent(player, key -> new ArrayList<>()).addAll(List.of(items));
    }

    public List<ItemBlob> contentsOf(UUID player) {
        return List.copyOf(contents.getOrDefault(player, List.of()));
    }

    public int countOf(UUID player) {
        return contents.getOrDefault(player, List.of()).stream().mapToInt(ItemBlob::count).sum();
    }

    public void setSlots(UUID player, int free) {
        slots.put(player, free);
    }

    public void setReady(UUID player, boolean value) {
        ready.put(player, value);
    }

    public void setDataVersion(int version) {
        dataVersion = version;
    }

    /** Makes the next call of the named operation throw: "remove", "add" or "save". */
    public void crashOn(String operation) {
        crashOn = operation;
    }

    public void setSaveFails(boolean fails) {
        saveFails = fails;
    }

    private void maybeCrash(String operation) {
        if (operation.equals(crashOn)) {
            crashOn = null;
            throw new IllegalStateException("simulated crash during " + operation);
        }
    }

    @Override
    public Optional<String> digest(UUID player) {
        List<ItemBlob> items = contents.getOrDefault(player, List.of());
        StringBuilder text = new StringBuilder();
        for (ItemBlob item : items) {
            text.append(MarketRepository.sha256(item.data())).append('x').append(item.count()).append(';');
        }
        return Optional.of(MarketRepository.sha256(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Override
    public boolean readyForItems(UUID player) {
        return ready.getOrDefault(player, true);
    }

    @Override
    public boolean fits(UUID player, List<ItemBlob> items) {
        int used = contents.getOrDefault(player, List.of()).size();
        return used + items.size() <= slots.getOrDefault(player, DEFAULT_SLOTS);
    }

    @Override
    public boolean removeExactly(UUID player, List<ItemBlob> items) {
        maybeCrash("remove");
        List<ItemBlob> owned = contents.computeIfAbsent(player, key -> new ArrayList<>());
        List<ItemBlob> copy = new ArrayList<>(owned);
        for (ItemBlob item : items) {
            if (!copy.remove(item)) {
                return false;
            }
        }
        owned.clear();
        owned.addAll(copy);
        return true;
    }

    @Override
    public boolean addAll(UUID player, List<ItemBlob> items) {
        maybeCrash("add");
        if (!fits(player, items)) {
            return false;
        }
        contents.computeIfAbsent(player, key -> new ArrayList<>()).addAll(items);
        return true;
    }

    @Override
    public boolean save(UUID player) {
        maybeCrash("save");
        return !saveFails;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }
}
